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

        recorder.recordNetworkRequest(
            id = operationId,
            userAgent = request.header("User-Agent"),
            accept = request.header("Accept"),
            acceptLanguage = request.header("Accept-Language"),
            outgoingCookies = parseOutgoingCookies(request.header("Cookie")),
        )

        val response = chain.proceed(request)
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
            setCookies = Cookie.parseAll(request.url, response.headers).map { cookie ->
                DiagnosticCookie(
                    name = cookie.name,
                    domain = cookie.domain,
                    path = cookie.path,
                )
            },
        )
        return response
    }

    private fun parseOutgoingCookies(header: String?): List<DiagnosticCookie> =
        header.orEmpty()
            .split(';')
            .mapNotNull { part ->
                val name = part.substringBefore('=').trim()
                name.takeIf(String::isNotBlank)?.let {
                    DiagnosticCookie(
                        name = it,
                        domain = null,
                        path = null,
                    )
                }
            }

    private fun sanitizeInfrastructureValue(value: String): String =
        value
            .replace(IPV4, "[redacted-ip]")
            .replace(IPV6, "[redacted-ip]")
            .take(MAX_INFRASTRUCTURE_VALUE_LENGTH)

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
        val IPV4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")
        val IPV6 = Regex("""(?i)\b(?:[0-9a-f]{1,4}:){2,7}[0-9a-f]{0,4}\b""")
        const val MAX_INFRASTRUCTURE_VALUE_LENGTH = 200
    }
}
