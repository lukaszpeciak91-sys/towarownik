package pl.lukaszpeciak.towarownik.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObiDiagnosticRecorderTest {
    @Test
    fun `history is bounded to configured number of completed operations`() {
        val recorder = ObiDiagnosticRecorder(maxHistory = 2, clock = { 1_000L })
        recorder.setEnabled(true)

        repeat(3) { index ->
            val id = recorder.startOperation(
                operation = ObiDiagnosticOperationType.SEARCH,
                inputType = ObiDiagnosticInputType.TEXT,
                identifier = "query-$index",
                requestedUrl = "https://www.obi.pl/search/query-$index/",
                requestMethod = "GET",
            )
            recorder.finish(id)
        }

        assertEquals(
            listOf("query-1", "query-2"),
            recorder.snapshots().map { it.identifier },
        )
    }

    @Test
    fun `report contains device operation and safe metadata only`() {
        val recorder = ObiDiagnosticRecorder(clock = { 1_700_000_000_000L })
        recorder.configureDeviceContext(
            DiagnosticDeviceContext(
                versionName = "0.1.0",
                versionCode = 1,
                androidVersion = "13",
                apiLevel = 33,
                manufacturer = "TestMaker",
                model = "TestModel",
            ),
        )
        recorder.setEnabled(true)
        val id = recorder.startOperation(
            operation = ObiDiagnosticOperationType.PRODUCT_LOOKUP,
            inputType = ObiDiagnosticInputType.OBIK,
            identifier = "3496072",
            requestedUrl = "https://www.obi.pl/api/disc/store/change",
            requestMethod = "GET",
        )
        recorder.recordNetworkRequest(
            id = id,
            userAgent = "okhttp/test",
            accept = null,
            acceptLanguage = null,
            outgoingCookies = listOf(
                DiagnosticCookie("store", "www.obi.pl", "/"),
            ),
        )
        recorder.recordHttpHop(
            id = id,
            status = 404,
            url = "https://www.obi.pl/api/disc/store/change",
            location = null,
            contentType = "text/html",
            contentEncoding = null,
            declaredContentLength = 12,
            safeInfrastructureHeaders = mapOf("Server" to "test-edge"),
            setCookies = listOf(
                DiagnosticCookie("session_cookie", "www.obi.pl", "/"),
            ),
        )
        recorder.recordDuration(id, 42)
        recorder.mappingTrace(id, "HTTP 404")
        recorder.mappingTrace(id, "UI NOT_FOUND")
        recorder.finish(id)

        val report = recorder.report()

        assertTrue(report.contains("app=0.1.0 (1)"))
        assertTrue(report.contains("android=13 API 33"))
        assertTrue(report.contains("operation=PRODUCT_LOOKUP"))
        assertTrue(report.contains("identifier=3496072"))
        assertTrue(report.contains("outgoingCookieNames=store@www.obi.pl/"))
        assertTrue(report.contains("setCookieNames=session_cookie@www.obi.pl/"))
        assertTrue(report.contains("finalStatus=404"))
        assertTrue(report.contains("UI NOT_FOUND"))
        assertFalse(report.contains("cookieValue"))
    }
}
