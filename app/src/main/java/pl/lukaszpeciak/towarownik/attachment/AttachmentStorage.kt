package pl.lukaszpeciak.towarownik.attachment

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream
import java.util.UUID

internal const val ATTACHMENT_LOCAL_STORAGE_MAX_BYTES =
    16L * 1024L * 1024L

internal class AttachmentStorage private constructor(
    private val directory: File,
) {
    constructor(context: Context) : this(File(context.filesDir, DIRECTORY_NAME))

    fun importValidated(
        type: AttachmentType,
        displayName: String,
        mimeType: String,
        byteSize: Long,
        width: Int? = null,
        height: Int? = null,
        createdAt: Long = System.currentTimeMillis(),
        source: () -> InputStream,
        beforePublish: (AdvisorAttachment) -> Boolean = { true },
    ): AdvisorAttachment {
        require(byteSize <= ATTACHMENT_LOCAL_STORAGE_MAX_BYTES) {
            "Attachment exceeds local storage size limit"
        }
        val localId = UUID.randomUUID().toString().replace("-", "")
        val metadata = requireNotNull(
            validatedAttachmentOrNull(
                type.name,
                displayName,
                mimeType,
                localId,
                byteSize,
                width,
                height,
                createdAt,
            ),
        )
        directory.mkdirs()
        val temporary = File(directory, "$localId.tmp")
        val destination = file(localId) ?: error("Invalid generated attachment identifier")
        try {
            val copied = source().use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            require(copied == byteSize) { "Attachment size differs from validated metadata" }
            check(beforePublish(metadata)) {
                "Could not persist pending attachment ownership"
            }
            check(temporary.renameTo(destination)) { "Could not persist attachment" }
        } catch (failure: Throwable) {
            temporary.delete()
            destination.delete()
            throw failure
        }
        return metadata
    }

    fun open(localId: String): InputStream? =
        file(localId)?.takeIf(File::isFile)?.inputStream()

    fun contentUri(context: Context, localId: String): Uri? =
        file(localId)?.takeIf(File::isFile)?.let {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        }

    fun exists(localId: String): Boolean = file(localId)?.isFile == true

    fun delete(localId: String): Boolean {
        val target = file(localId) ?: return false
        return !target.exists() || target.delete()
    }

    fun deleteAll(localIds: Collection<String>) {
        localIds.distinct().forEach(::delete)
    }

    private fun file(localId: String): File? =
        localId.takeIf { LOCAL_ID.matches(it) }?.let { File(directory, it) }

    private companion object {
        const val DIRECTORY_NAME = "advisor_attachments"
        val LOCAL_ID = Regex("[a-f0-9]{32}")
    }
}
