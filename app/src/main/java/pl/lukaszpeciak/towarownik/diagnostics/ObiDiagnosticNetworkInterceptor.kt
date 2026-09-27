package pl.lukaszpeciak.towarownik.diagnostics

import okhttp3.Interceptor
import okhttp3.Response

internal data class ObiDiagnosticRequestTag(
    val operationId: Long,
    val storeNumber: String?,
)

class ObiDiagnosticNetworkInterceptor(
    private val recorder: ObiDiagnosticRecorder,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val tag = request.tag(ObiDiagnosticRequestTag::class.java)
        val operationId = tag?.operationId
        val expectedStoreNumber = tag?.storeNumber
        val outgoingEvidence = outgoingDiagnosticCookieEvidence(
            request.header("Cookie"),
            expectedStoreNumber,
        )

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
            expectedStoreNumber = expectedStoreNumber,
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
            outgoingStoreCookieMatch = outgoingEvidence.storeMatchResult,
            setCookieStoreMatch = setCookieEvidence.storeMatchResult,
        )
        return response
    }
}
