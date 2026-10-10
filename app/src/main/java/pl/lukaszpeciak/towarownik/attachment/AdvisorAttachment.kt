package pl.lukaszpeciak.towarownik.attachment

internal enum class AttachmentType {
    IMAGE,
    PDF,
    TEXT,
}

internal data class AdvisorAttachment(
    val type: AttachmentType,
    val displayName: String,
    val mimeType: String,
    val localId: String,
    val byteSize: Long,
    val width: Int? = null,
    val height: Int? = null,
    val createdAt: Long,
)

internal fun validatedAttachmentOrNull(
    type: String,
    displayName: String,
    mimeType: String,
    localId: String,
    byteSize: Long,
    width: Int?,
    height: Int?,
    createdAt: Long,
): AdvisorAttachment? {
    val category = runCatching { AttachmentType.valueOf(type) }.getOrNull()
        ?: return null
    if (displayName.isBlank() || displayName.length > 255) return null
    if (!LOCAL_ID.matches(localId) || byteSize < 0L || createdAt < 0L) return null
    return when (category) {
        AttachmentType.IMAGE -> {
            if (!mimeType.startsWith("image/") || width == null || height == null || width <= 0 || height <= 0) {
                null
            } else {
                AdvisorAttachment(category, displayName, mimeType, localId, byteSize, width, height, createdAt)
            }
        }
        AttachmentType.PDF -> {
            if (mimeType != "application/pdf" || width != null || height != null) {
                null
            } else {
                AdvisorAttachment(category, displayName, mimeType, localId, byteSize, null, null, createdAt)
            }
        }
        AttachmentType.TEXT -> {
            if (!allowedTextAttachment(displayName, mimeType) ||
                byteSize < 1 || byteSize > TEXT_ATTACHMENT_MAX_BYTES ||
                width != null || height != null
            ) {
                null
            } else {
                AdvisorAttachment(category, displayName, mimeType, localId, byteSize, null, null, createdAt)
            }
        }
    }
}

private val LOCAL_ID = Regex("[a-f0-9]{32}")
