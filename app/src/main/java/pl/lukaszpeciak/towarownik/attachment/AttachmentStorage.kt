package pl.lukaszpeciak.towarownik.attachment

import android.content.Context
import java.io.File
import java.io.InputStream
import java.util.UUID

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
    ): AdvisorAttachment {
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
