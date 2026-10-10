package pl.lukaszpeciak.towarownik.attachment

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

// Shared Phase A1 wire contract and Phase A2 Android picker normalization.
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

/** Android document-provider MIME labels vary; normalize only for known extensions.
 * Wire metadata still uses the exact Phase A1 extension/MIME pair allowlist.
 */
internal fun normalizedPickedTextMime(filename: String, reportedMime: String?): String? {
    val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val allowed = TEXT_MIME_BY_EXTENSION[extension] ?: return null
    val reported = reportedMime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
    if (reported != null && reported in allowed) return reported
    val generic = reported == null || reported.isEmpty() ||
        reported == "application/octet-stream" || reported == "binary/octet-stream"
    val alias = when (extension) {
        "json" -> reported in setOf("text/plain", "application/x-json", "text/x-json")
        "xml" -> reported in setOf("text/plain", "application/x-xml")
        "yaml", "yml" -> reported in setOf("text/plain", "application/x-yml", "text/x-yml")
        "md" -> reported in setOf("text/x-markdown", "application/markdown")
        "csv" -> reported in setOf("application/csv", "application/vnd.ms-excel")
        "ini", "conf" -> reported in setOf("text/x-ini", "text/x-config")
        "log" -> reported == "text/x-log"
        else -> false
    }
    if (!generic && !alias) return null
    return when (extension) {
        "json" -> "application/json"
        "xml" -> "application/xml"
        "yaml", "yml" -> "application/yaml"
        "md" -> "text/markdown"
        "csv" -> "text/csv"
        else -> "text/plain"
    }
}

/** Preserve the final extension through sanitization and Worker's 128-char filename bound. */
internal fun sanitizedTextAttachmentName(sourceName: String): String? {
    val basename = sourceName.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\u0000-\\u001f\\u007f]"), "").trim()
    val extension = basename.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension !in TEXT_MIME_BY_EXTENSION) return null
    val suffix = ".$extension"
    val stem = basename.dropLast(suffix.length).take(128 - suffix.length).trim()
    if (stem.isBlank()) return null
    return stem + suffix
}

internal fun textAttachmentExtension(filename: String): String =
    filename.substringAfterLast('.', "").uppercase(Locale.ROOT)

internal val ADVISOR_FILE_PICKER_MIME_TYPES = arrayOf(
    "image/*", "application/pdf", "text/*", "application/json",
    "application/xml", "application/x-xml", "application/yaml",
    "application/x-yaml", "application/x-yml", "application/x-json",
    "application/markdown", "application/csv", "application/vnd.ms-excel",
    "application/octet-stream", "binary/octet-stream",
)
