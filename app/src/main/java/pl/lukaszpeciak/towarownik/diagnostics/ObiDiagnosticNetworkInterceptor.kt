package pl.lukaszpeciak.towarownik.diagnostics

import okhttp3.Interceptor
import okhttp3.Response

internal data class ObiDiagnosticRequestTag(val operationId: Long)

class ObiDiagnosticNetworkInterceptor(
    private val recorder: ObiDiagnosticRecorder,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val operationId = request.tag(ObiDiagnosticRequestTag::class.java)?.operationId
        val outgoingEvidence = outgoingDiagnosticCookieEvidence(request.header("Cookie"))

        recorder.recordNetworkRequest(
            id = operationId,
            userAgent = request.header("User-Agent"),
            accept = request.header("Accept"),
            acceptLanguage = request.header("Accept-Language"),
            outgoingCookies = outgoingEvidence.cookies,
        )

        val response = chain.proceed(request)
        val setCookieEvidence = setDiagnosticCookieEvidence(
            requestUrl = request.url,
            responseHeaders = response.headers,
        )
        recorder.recordHttpHop(
            id = operationId,
            status = response.code,
            url = request.url.toString(),
            location = response.header("Location"),
            protocol = diagnosticProtocolLabel(response.protocol),
            safeRequestHeaders = safeDiagnosticRequestHeaders(request),
            contentType = response.header("Content-Type"),
            contentEncoding = response.header("Content-Encoding"),
            declaredContentLength = response.header("Content-Length")?.toLongOrNull(),
            safeInfrastructureHeaders = safeDiagnosticInfrastructureHeaders(response),
            outgoingCookies = outgoingEvidence.cookies,
            setCookies = setCookieEvidence.cookies,
            outgoingStore075CookieMatch = outgoingEvidence.store075Match,
            setCookieStore075Match = setCookieEvidence.store075Match,
        )
        return response
    }
}
