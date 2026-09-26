package pl.lukaszpeciak.towarownik.diagnostics

import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

internal data class DiagnosticCookieEvidence(
    val cookies: List<DiagnosticCookie>,
    val store075Match: Store075CookieMatch,
)

internal fun outgoingDiagnosticCookieEvidence(header: String?): DiagnosticCookieEvidence {
    var recognizedStoreCookie = false
    var store075Match = false
    val cookies = header.orEmpty()
        .split(';')
        .mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            if (name.isBlank()) return@mapNotNull null

            val value = part.substringAfter('=', missingDelimiterValue = "").trim()
            if (isRecognizableStoreCookie(name)) {
                recognizedStoreCookie = true
                if (value == EXPECTED_STORE_NUMBER) {
                    store075Match = true
                }
            }

            DiagnosticCookie(
                name = name,
                domain = null,
                path = null,
            )
        }

    return DiagnosticCookieEvidence(
        cookies = cookies,
        store075Match = storeMatch(
            recognized = recognizedStoreCookie,
            matched = store075Match,
        ),
    )
}

internal fun setDiagnosticCookieEvidence(
    requestUrl: okhttp3.HttpUrl,
    responseHeaders: Headers,
): DiagnosticCookieEvidence {
    val cookies = Cookie.parseAll(requestUrl, responseHeaders)
    val recognizableStoreCookies = cookies.filter { cookie ->
        isRecognizableStoreCookie(cookie.name)
    }
    return DiagnosticCookieEvidence(
        cookies = cookies.map { cookie ->
            DiagnosticCookie(
                name = cookie.name,
                domain = cookie.domain,
                path = cookie.path,
            )
        },
        store075Match = storeMatch(
            recognized = recognizableStoreCookies.isNotEmpty(),
            matched = recognizableStoreCookies.any { cookie ->
                cookie.value == EXPECTED_STORE_NUMBER
            },
        ),
    )
}

internal fun safeDiagnosticRequestHeaders(request: Request): Map<String, String> =
    SAFE_REQUEST_HEADERS.mapNotNull { header ->
        request.header(header)?.let { value ->
            header to if (header.equals("Referer", ignoreCase = true)) {
                sanitizeDiagnosticUrl(value) ?: "[redacted-url]"
            } else {
                value.take(MAX_HEADER_VALUE_LENGTH)
            }
        }
    }.toMap()

internal fun safeDiagnosticInfrastructureHeaders(response: Response): Map<String, String> =
    SAFE_INFRASTRUCTURE_HEADERS.mapNotNull { header ->
        response.header(header)?.let { value ->
            header to sanitizeDiagnosticHeaderValue(value)
        }
    }.toMap()

internal fun diagnosticProtocolLabel(protocol: Protocol): String = when (protocol.toString()) {
    "http/1.0" -> "HTTP/1.0"
    "http/1.1" -> "HTTP/1.1"
    "h2" -> "HTTP/2"
    "h2_prior_knowledge" -> "HTTP/2 prior knowledge"
    else -> protocol.toString()
}

private fun storeMatch(
    recognized: Boolean,
    matched: Boolean,
): Store075CookieMatch = when {
    matched -> Store075CookieMatch.MATCH
    recognized -> Store075CookieMatch.MISMATCH
    else -> Store075CookieMatch.UNKNOWN
}

private fun isRecognizableStoreCookie(name: String): Boolean =
    STORE_COOKIE_NAMES.any { expected -> name.equals(expected, ignoreCase = true) }

private fun sanitizeDiagnosticHeaderValue(value: String): String =
    redactQueryLikeValues(
        value
            .replace(IPV4, "[redacted-ip]")
            .replace(IPV6, "[redacted-ip]"),
    ).take(MAX_HEADER_VALUE_LENGTH)

private fun redactQueryLikeValues(value: String): String =
    QUERY_VALUE.replace(value) { match ->
        val prefix = match.groupValues[1]
        val name = match.groupValues[2]
        val rawValue = match.groupValues[3]
        if (
            name.equals("storeNumber", ignoreCase = true) &&
            STORE_NUMBER.matches(rawValue)
        ) {
            match.value
        } else {
            "$prefix$name=REDACTED"
        }
    }

private val SAFE_REQUEST_HEADERS = listOf(
    "User-Agent",
    "Accept",
    "Accept-Language",
    "Accept-Encoding",
    "Host",
    "Referer",
)

private val SAFE_INFRASTRUCTURE_HEADERS = listOf(
    "Server",
    "Via",
    "x-cache",
    "x-cache-hits",
    "cf-cache-status",
    "cf-ray",
    "x-amz-cf-pop",
    "x-amz-cf-id",
    "x-amz-error-code",
    "x-amz-error-message",
    "Age",
    "Cache-Control",
    "Vary",
)

private val STORE_COOKIE_NAMES = setOf(
    "store",
    "storeNumber",
    "selectedStore",
    "selected_store",
)

private val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
private val IPV6 = Regex("""(?i)\b(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}\b""")
private val QUERY_VALUE = Regex("""([?&])([A-Za-z0-9_.-]+)=([^&\s]+)""")
private val STORE_NUMBER = Regex("""\d{1,4}""")

private const val EXPECTED_STORE_NUMBER = "075"
private const val MAX_HEADER_VALUE_LENGTH = 300
