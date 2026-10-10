package pl.lukaszpeciak.towarownik.attachment

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

// Phase A1 transport contract; the current picker still selects images/PDFs only.
internal const val TEXT_ATTACHMENT_MAX_BYTES = 1024L * 1024L

private val TEXT_MIME_BY_EXTENSION: Map<String, Set<String>> = mapOf(
    "txt" to setOf("text/plain"),
    "md" to setOf("text/markdown", "text/plain"),
    "csv" to setOf("text/csv", "text/plain"),
    "json" to setOf("application/json", "text/json"),
    "xml" to setOf("application/xml", "text/xml"),
    "yaml" to setOf("application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml"),
    "yml" to setOf("application/yaml", "application/x-yaml", "text/yaml", "text/x-yaml"),
    "log" to setOf("text/plain"),
    "ini" to setOf("text/plain"),
    "conf" to setOf("text/plain"),
)

internal fun allowedTextAttachment(filename: String, mimeType: String): Boolean {
    val name = filename.lowercase(Locale.ROOT)
    val extension = name.substringAfterLast('.', "")
    if (extension.isEmpty() || '/' in name || '\\' in name) return false
    return mimeType in TEXT_MIME_BY_EXTENSION[extension].orEmpty()
}

/** Reject malformed UTF-8, terminal control bytes, NUL and common binary file headers. */
internal fun decodedTextAttachmentOrNull(bytes: ByteArray): String? {
    if (bytes.isEmpty() || bytes.size > TEXT_ATTACHMENT_MAX_BYTES) return null
    fun starts(vararg prefix: Int) =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it].toInt() and 0xff == prefix[it] }
    if (starts(0x25, 0x50, 0x44, 0x46, 0x2d) ||
        starts(0x50, 0x4b, 0x03, 0x04) ||
        starts(0x89, 0x50, 0x4e, 0x47) ||
        starts(0xff, 0xd8, 0xff) ||
        starts(0x1f, 0x8b) ||
        starts(0x7f, 0x45, 0x4c, 0x46)
    ) return null
    val text = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull() ?: return null
    if (text.any { c ->
        (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') ||
            c.code in 0x7f..0x9f
    }) return null
    return text
}
