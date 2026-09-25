package pl.lukaszpeciak.towarownik.diagnostics

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun sanitizeDiagnosticUrl(rawUrl: String?): String? {
    if (rawUrl == null) return null

    rawUrl.toHttpUrlOrNull()?.let { url ->
        return sanitizeAbsoluteUrl(url).toString()
    }

    val base = "https://diagnostic.invalid/".toHttpUrlOrNull() ?: return "[redacted-url]"
    val resolved = base.resolve(rawUrl) ?: return "[redacted-url]"
    val sanitized = sanitizeAbsoluteUrl(resolved)
    return if (rawUrl.startsWith("//")) {
        sanitized.toString()
    } else {
        buildString {
            append(sanitized.encodedPath)
            sanitized.encodedQuery?.let { append('?').append(it) }
        }
    }
}

private fun sanitizeAbsoluteUrl(url: HttpUrl): HttpUrl {
    val builder = url.newBuilder()
        .query(null)
        .fragment(null)

    url.queryParameterNames.forEach { name ->
        url.queryParameterValues(name).forEach { value ->
            builder.addQueryParameter(
                name,
                if (isSafeDiagnosticQueryValue(name, value)) {
                    value
                } else {
                    REDACTED_QUERY_VALUE
                },
            )
        }
    }

    return builder.build()
}

private fun isSafeDiagnosticQueryValue(name: String, value: String?): Boolean =
    name.equals("storeNumber", ignoreCase = true) &&
        value != null &&
        STORE_NUMBER.matches(value)

private val STORE_NUMBER = Regex("""\d{1,4}""")
private const val REDACTED_QUERY_VALUE = "REDACTED"
