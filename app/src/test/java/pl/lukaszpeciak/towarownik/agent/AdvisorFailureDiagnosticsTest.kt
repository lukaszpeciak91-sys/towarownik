package pl.lukaszpeciak.towarownik.agent

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorFailureDiagnosticsTest {
    @Test
    fun `known transport failures classify without exception text`() {
        val cases = listOf(
            UnknownHostException("SECRET-DNS") to AdvisorFailureClass.DNS,
            SSLHandshakeException("SECRET-TLS") to AdvisorFailureClass.TLS,
            SocketTimeoutException("SECRET-TIMEOUT") to AdvisorFailureClass.TIMEOUT,
            IOException("SECRET-IO") to AdvisorFailureClass.NETWORK_IO,
        )
        cases.forEach { (error, expected) ->
            val category = advisorFailureClass(error)
            assertEquals(expected, category)
            val report = AdvisorFailureDiagnostic(
                kind = AdvisorProxyFailureKind.NETWORK,
                endpoint = "continue",
                failureClass = category,
            ).reportValue()
            assertTrue(report.contains("failureClass=${expected.name}"))
            assertFalse(report.contains("SECRET"))
        }
    }

    @Test
    fun `502 retains HTTP stage and valid trace without leaking body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(502)
                    .setHeader("X-Taksula-Trace-Id", "123e4567-e89b-12d3-a456-426614174000")
                    .setBody("""{"error":"upstream_failure","private":"SECRET-CANARY"}"""),
            )
            val client = AdvisorProxyClient(
                appToken = "synthetic",
                baseUrl = server.url("/").toString().toHttpUrl(),
            )
            val result = client.start("test", "obi-pl", "075")
            assertTrue(result is AdvisorProxyCallResult.Failure)
            val failure = result as AdvisorProxyCallResult.Failure
            assertEquals(502, failure.httpStatus)
            assertEquals("start", failure.endpoint)
            assertEquals(AdvisorFailureClass.HTTP_ERROR, failure.failureClass)
            assertEquals("upstream_failure", failure.proxyErrorCode)
            assertEquals("123e4567-e89b-12d3-a456-426614174000", failure.traceId)
            assertFalse(failure.diagnosticOrNull()!!.reportValue().contains("SECRET"))
        }
    }

    @Test
    fun `missing and malformed traces never enter report`() {
        val failure = AdvisorProxyCallResult.Failure(
            kind = AdvisorProxyFailureKind.NETWORK,
            endpoint = "message",
            traceId = "SECRET-CANARY",
            failureClass = AdvisorFailureClass.TIMEOUT,
        )
        val diagnostic = failure.diagnosticOrNull()!!
        assertEquals(null, diagnostic.traceId)
        assertFalse(diagnostic.reportValue().contains("SECRET"))
        assertEquals("message", diagnostic.endpoint)
    }
}
