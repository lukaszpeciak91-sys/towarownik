package pl.lukaszpeciak.towarownik.attachment

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

/** Conservative OCR-friendly bound; intentionally centralized for review before transport is added. */
internal const val ATTACHMENT_IMAGE_MAX_DIMENSION = 4096
private const val JPEG_QUALITY = 92

internal enum class AttachmentImportError {
    UNSUPPORTED_TYPE,
    TOO_LARGE,
    IMAGE_UNREADABLE,
    CANNOT_OPEN,
    CAMERA_FAILED,
}

internal sealed interface AttachmentImportResult {
    data class Success(val attachment: AdvisorAttachment) : AttachmentImportResult
    data class Failure(val error: AttachmentImportError) : AttachmentImportResult
}

internal class AttachmentImporter(
    private val resolver: ContentResolver,
    private val storage: AttachmentStorage,
    private val beforePublish: (AdvisorAttachment) -> Boolean = { true },
) {
    fun import(uri: Uri, suggestedName: String? = null): AttachmentImportResult {
        val metadata = queryMetadata(uri)
        if (metadata.size != null && metadata.size > ATTACHMENT_LOCAL_STORAGE_MAX_BYTES) {
            return AttachmentImportResult.Failure(AttachmentImportError.TOO_LARGE)
        }
        val resolvedType = resolver.getType(uri)?.lowercase()
        return when {
            resolvedType == "application/pdf" -> importPdf(uri, metadata.name ?: suggestedName ?: "document.pdf")
            resolvedType?.startsWith("image/") == true -> importImage(uri, metadata.name ?: suggestedName ?: "photo")
            else -> AttachmentImportResult.Failure(AttachmentImportError.UNSUPPORTED_TYPE)
        }
    }

    private fun importPdf(uri: Uri, name: String): AttachmentImportResult {
        val bytes = readBounded(uri) ?: return AttachmentImportResult.Failure(AttachmentImportError.CANNOT_OPEN)
        if (bytes.size.toLong() > ATTACHMENT_LOCAL_STORAGE_MAX_BYTES) {
            return AttachmentImportResult.Failure(AttachmentImportError.TOO_LARGE)
        }
        if (bytes.size < 5 || !bytes.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) {
            return AttachmentImportResult.Failure(AttachmentImportError.UNSUPPORTED_TYPE)
        }
        return runCatching {
            storage.importValidated(
                type = AttachmentType.PDF,
                displayName = safeName(name, "document.pdf"),
                mimeType = "application/pdf",
                byteSize = bytes.size.toLong(),
                source = { ByteArrayInputStream(bytes) },
                beforePublish = beforePublish,
            )
        }.fold(
            onSuccess = { AttachmentImportResult.Success(it) },
            onFailure = { AttachmentImportResult.Failure(AttachmentImportError.CANNOT_OPEN) },
        )
    }

    private fun importImage(uri: Uri, name: String): AttachmentImportResult {
        val sourceBytes = readBounded(uri) ?: return AttachmentImportResult.Failure(AttachmentImportError.CANNOT_OPEN)
        if (sourceBytes.size.toLong() > ATTACHMENT_LOCAL_STORAGE_MAX_BYTES) {
            return AttachmentImportResult.Failure(AttachmentImportError.TOO_LARGE)
        }
        val normalized = normalizeImage(sourceBytes)
            ?: return AttachmentImportResult.Failure(AttachmentImportError.IMAGE_UNREADABLE)
        if (normalized.bytes.size.toLong() > ATTACHMENT_LOCAL_STORAGE_MAX_BYTES) {
            return AttachmentImportResult.Failure(AttachmentImportError.TOO_LARGE)
        }
        return runCatching {
            storage.importValidated(
                type = AttachmentType.IMAGE,
                displayName = imageDisplayName(name, normalized.mimeType),
                mimeType = normalized.mimeType,
                byteSize = normalized.bytes.size.toLong(),
                width = normalized.width,
                height = normalized.height,
                source = { ByteArrayInputStream(normalized.bytes) },
                beforePublish = beforePublish,
            )
        }.fold(
            onSuccess = { AttachmentImportResult.Success(it) },
            onFailure = { AttachmentImportResult.Failure(AttachmentImportError.CANNOT_OPEN) },
        )
    }

    private fun readBounded(uri: Uri): ByteArray? = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > ATTACHMENT_LOCAL_STORAGE_MAX_BYTES) {
                    return@use ByteArray((ATTACHMENT_LOCAL_STORAGE_MAX_BYTES + 1).toInt())
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }.getOrNull()

    private fun queryMetadata(uri: Uri): SourceMetadata = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use SourceMetadata(null, null)
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            SourceMetadata(
                name = nameIndex.takeIf { it >= 0 }?.let(cursor::getString),
                size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
            )
        }
    }.getOrNull() ?: SourceMetadata(null, null)
}

internal data class NormalizedImage(val bytes: ByteArray, val mimeType: String, val width: Int, val height: Int)

internal fun normalizeImage(bytes: ByteArray): NormalizedImage? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val orientation = runCatching {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val sampleSize = imageSampleSize(bounds.outWidth, bounds.outHeight, ATTACHMENT_IMAGE_MAX_DIMENSION)
    val decoded = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: return null
    val oriented = applyExifOrientation(decoded, orientation)
    val scaled = scaleWithin(oriented, ATTACHMENT_IMAGE_MAX_DIMENSION)
    val hasAlpha = scaled.hasAlpha()
    val output = ByteArrayOutputStream()
    val format = if (hasAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
    if (!scaled.compress(format, if (hasAlpha) 100 else JPEG_QUALITY, output)) return null
    val result = NormalizedImage(
        output.toByteArray(),
        if (hasAlpha) "image/png" else "image/jpeg",
        scaled.width,
        scaled.height,
    )
    if (scaled !== oriented) scaled.recycle()
    if (oriented !== decoded) oriented.recycle()
    decoded.recycle()
    return result
}

internal fun imageSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sample = 1
    while (max(width / sample, height / sample) > maxDimension * 2) sample *= 2
    return sample
}

private fun scaleWithin(bitmap: Bitmap, bound: Int): Bitmap {
    val largest = max(bitmap.width, bitmap.height)
    if (largest <= bound) return bitmap
    val scale = bound.toFloat() / largest
    return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
}

private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        else -> return bitmap
    }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

internal class CameraCapture(private val context: Context) {
    private val directory = File(context.cacheDir, "advisor_camera_capture")
    var file: File? = null
        private set

    fun createUri(): Uri {
        cleanup()
        directory.mkdirs()
        val capture = File.createTempFile("capture-", ".jpg", directory)
        file = capture
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", capture)
    }

    fun cleanup() {
        file?.delete()
        file = null
    }
}

private data class SourceMetadata(val name: String?, val size: Long?)
private fun safeName(name: String, fallback: String) = name.substringAfterLast('/').take(255).ifBlank { fallback }
private fun imageDisplayName(name: String, mime: String): String {
    val base = safeName(name, "photo").substringBeforeLast('.', safeName(name, "photo"))
    return "$base.${if (mime == "image/png") "png" else "jpg"}".take(255)
}
