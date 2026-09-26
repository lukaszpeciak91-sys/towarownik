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
            requestedUrl = "https://www.obi.pl/api/disc/store/change?storeNumber=075&redirectUrl=/p/3496072",
            requestMethod = "GET",
        )
        recorder.recordNetworkRequest(
            id = id,
            userAgent = "okhttp/test",
            accept = null,
            acceptLanguage = null,
            outgoingCookies = listOf(
                DiagnosticCookie("store", null, null),
            ),
        )
        recorder.recordHttpHop(
            id = id,
            status = 404,
            url = "https://www.obi.pl/p/3496072",
            location = null,
            protocol = "HTTP/2",
            safeRequestHeaders = mapOf("User-Agent" to "okhttp/test"),
            contentType = "text/html",
            contentEncoding = null,
            declaredContentLength = 12,
            safeInfrastructureHeaders = mapOf("Server" to "test-edge"),
            outgoingCookies = listOf(
                DiagnosticCookie("store", null, null),
            ),
            setCookies = listOf(
                DiagnosticCookie("session_cookie", "www.obi.pl", "/"),
            ),
            outgoingStore075CookieMatch = Store075CookieMatch.MATCH,
            setCookieStore075Match = Store075CookieMatch.UNKNOWN,
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
        assertTrue(report.contains("storeNumber=075"))
        assertTrue(report.contains("redirectUrl=REDACTED"))
        assertTrue(report.contains("outgoingCookieNames=store"))
        assertTrue(report.contains("setCookieNames=session_cookie@www.obi.pl/"))
        assertTrue(report.contains("store075CookieMatch=true"))
        assertTrue(report.contains("finalStatus=404"))
        assertTrue(report.contains("UI NOT_FOUND"))
        assertFalse(report.contains("store=075"))
    }

    @Test
    fun `all recorded URLs redact unknown query values`() {
        val secret = "VERY_SECRET_TOKEN"
        val recorder = ObiDiagnosticRecorder(clock = { 1_000L })
        recorder.setEnabled(true)
        val id = recorder.startOperation(
            operation = ObiDiagnosticOperationType.PRODUCT_LOOKUP,
            inputType = ObiDiagnosticInputType.OBIK,
            identifier = "3496072",
            requestedUrl = "https://www.obi.pl/challenge?token=$secret&storeNumber=075",
            requestMethod = "GET",
        )
        recorder.recordHttpHop(
            id = id,
            status = 302,
            url = "https://www.obi.pl/challenge?token=$secret",
            location = "/next?token=$secret",
            protocol = "HTTP/1.1",
            safeRequestHeaders = emptyMap(),
            contentType = "text/html",
            contentEncoding = null,
            declaredContentLength = null,
            safeInfrastructureHeaders = emptyMap(),
            outgoingCookies = emptyList(),
            setCookies = emptyList(),
            outgoingStore075CookieMatch = Store075CookieMatch.UNKNOWN,
            setCookieStore075Match = Store075CookieMatch.UNKNOWN,
        )
        recorder.recordBodySignatures(
            id,
            diagnosticBodySignatures(
                canonicalUrl = "https://www.obi.pl/p/3496072?token=$secret",
            ),
        )
        recorder.finish(id)

        val report = recorder.report()

        assertFalse(report.contains(secret))
        assertTrue(report.contains("storeNumber=075"))
        assertTrue(report.contains("token=REDACTED"))
        assertTrue(report.contains("body.canonicalUrl=https://www.obi.pl/p/3496072?token=REDACTED"))
    }

    private fun diagnosticBodySignatures(
        canonicalUrl: String?,
    ) = DiagnosticBodySignatures(
        decodedBodyUtf8Bytes = 10,
        looksLikeHtml = true,
        title = "test",
        accessDeniedOrChallenge = false,
        challengeIndicators = emptyList(),
        containsNuxtData = false,
        containsRequestedObik = true,
        containsCanonicalProductUrl = canonicalUrl != null,
        canonicalUrl = canonicalUrl,
        containsSelectedStore = false,
        containsStore075 = false,
        recognizedProductLinkCount = 0,
        containsSearchResultsPhrase = null,
        detectedSearchResultCount = null,
        zeroResultPhraseNoResultsFound = null,
        zeroResultPhraseProductsNotFound = null,
        zeroResultPhraseNoResults = null,
        containsZeroCountToken = null,
    )
}
