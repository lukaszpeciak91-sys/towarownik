package pl.lukaszpeciak.towarownik.diagnostics

import okhttp3.Cookie
import okhttp3.Interceptor
import okhttp3.Response

internal data class ObiDiagnosticRequestTag(val operationId: Long)

class ObiDiagnosticNetworkInterceptor(
    private val recorder: ObiDiagnosticRecorder,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val operationId = request.tag(ObiDiagnosticRequestTag::class.java)?.operationId
        val outgoingEvidence = outgoingCookieEvidence(request.header("Cookie"))

        recorder.recordNetworkRequest(
            id = operationId,
            userAgent = request.header("User-Agent"),
            accept = request.header("Accept"),
            acceptLanguage = request.header("Accept-Language"),
            outgoingCookies = outgoingEvidence.cookies,
        )

        val response = chain.proceed(request)
        val setCookieEvidence = setCookieEvidence(
            Cookie.parseAll(request.url, response.headers),
        )
        recorder.recordHttpHop(
            id = operationId,
            status = response.code,
            url = request.url.toString(),
            location = response.header("Location"),
            contentType = response.header("Content-Type"),
            contentEncoding = response.header("Content-Encoding"),
            declaredContentLength = response.header("Content-Length")?.toLongOrNull(),
            safeInfrastructureHeaders = SAFE_INFRASTRUCTURE_HEADERS.mapNotNull { header ->
                response.header(header)?.let { value ->
                    header to sanitizeInfrastructureValue(value)
                }
            }.toMap(),
            outgoingCookies = outgoingEvidence.cookies,
            setCookies = setCookieEvidence.cookies,
            outgoingStore075CookieMatch = outgoingEvidence.store075Match,
            setCookieStore075Match = setCookieEvidence.store075Match,
        )
        return response
    }

    private fun outgoingCookieEvidence(header: String?): CookieEvidence {
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

        return CookieEvidence(
            cookies = cookies,
            store075Match = storeMatch(
                recognized = recognizedStoreCookie,
                matched = store075Match,
            ),
        )
    }

    private fun setCookieEvidence(cookies: List<Cookie>): CookieEvidence {
        val recognizableStoreCookies = cookies.filter { cookie ->
            isRecognizableStoreCookie(cookie.name)
        }
        return CookieEvidence(
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

    private fun sanitizeInfrastructureValue(value: String): String =
        value
            .replace(IPV4, "[redacted-ip]")
            .replace(IPV6, "[redacted-ip]")
            .take(MAX_INFRASTRUCTURE_VALUE_LENGTH)

    private data class CookieEvidence(
        val cookies: List<DiagnosticCookie>,
        val store075Match: Store075CookieMatch,
    )

    private companion object {
        val SAFE_INFRASTRUCTURE_HEADERS = listOf(
            "Server",
            "Via",
            "x-cache",
            "x-cache-hits",
            "cf-cache-status",
            "cf-ray",
            "Age",
        )
        val STORE_COOKIE_NAMES = setOf(
            "store",
            "storeNumber",
            "selectedStore",
            "selected_store",
        )
        val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        val IPV6 = Regex("""(?i)\b(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}\b""")
        const val EXPECTED_STORE_NUMBER = "075"
        const val MAX_INFRASTRUCTURE_VALUE_LENGTH = 200
    }
}
