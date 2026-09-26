package pl.lukaszpeciak.towarownik.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.ObiHttpClient

class ObiLiveProbeTest {
    @Test
    fun `profiles A through G apply only intended header differences`() {
        MockWebServer().use { server ->
            val urls = ObiProbeUrls(server.url("/"))
            val nativeUa = "Towarownik/Test (Android test)"

            ObiProbeProfile.entries.forEach { profile ->
                server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))
                val step = ObiProbeSession(
                    baseUrl = server.url("/"),
                    profile = profile,
                    nativeUserAgent = nativeUa,
                ).get("profile", urls.search(), ObiProbeBodyKind.SEARCH)
                val request = server.takeRequest()

                when (profile) {
                    ObiProbeProfile.A_BASELINE -> {
                        assertEquals("okhttp/4.12.0", request.getHeader("User-Agent"))
                        assertNull(request.getHeader("Accept"))
                        assertNull(request.getHeader("Accept-Language"))
                    }
                    ObiProbeProfile.B_UA_ONLY -> {
                        assertEquals(nativeUa, request.getHeader("User-Agent"))
                        assertNull(request.getHeader("Accept"))
                        assertNull(request.getHeader("Accept-Language"))
                    }
                    ObiProbeProfile.C_ACCEPT_ONLY -> {
                        assertEquals("okhttp/4.12.0", request.getHeader("User-Agent"))
                        assertEquals(HTML_ACCEPT_VALUE, request.getHeader("Accept"))
                        assertNull(request.getHeader("Accept-Language"))
                    }
                    ObiProbeProfile.D_LANGUAGE_ONLY -> {
                        assertEquals("okhttp/4.12.0", request.getHeader("User-Agent"))
                        assertNull(request.getHeader("Accept"))
                        assertEquals(POLISH_LANGUAGE_VALUE, request.getHeader("Accept-Language"))
                    }
                    ObiProbeProfile.E_COMBINED_HTML -> {
                        assertEquals(nativeUa, request.getHeader("User-Agent"))
                        assertEquals(HTML_ACCEPT_VALUE, request.getHeader("Accept"))
                        assertEquals(POLISH_LANGUAGE_VALUE, request.getHeader("Accept-Language"))
                    }
                    ObiProbeProfile.F_BROWSER_UA_ONLY -> {
                        assertEquals(SYNTHETIC_BROWSER_USER_AGENT, request.getHeader("User-Agent"))
                        assertNull(request.getHeader("Accept"))
                        assertNull(request.getHeader("Accept-Language"))
                    }
                    ObiProbeProfile.G_BROWSER_HTML -> {
                        assertEquals(SYNTHETIC_BROWSER_USER_AGENT, request.getHeader("User-Agent"))
                        assertEquals(HTML_ACCEPT_VALUE, request.getHeader("Accept"))
                        assertEquals(POLISH_LANGUAGE_VALUE, request.getHeader("Accept-Language"))
                    }
                }

                val hopHeaders = step.hops.single().safeRequestHeaders
                assertEquals(request.getHeader("User-Agent"), hopHeaders["User-Agent"])
                assertEquals(request.getHeader("Accept-Encoding"), hopHeaders["Accept-Encoding"])
                assertEquals(request.getHeader("Host"), hopHeaders["Host"])
            }
        }
    }

    @Test
    fun `baseline probe request profile matches current production search profile`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))
            ObiProbeSession(
                baseUrl = server.url("/"),
                profile = ObiProbeProfile.A_BASELINE,
                nativeUserAgent = "unused",
            ).get(
                "baseline",
                ObiProbeUrls(server.url("/")).search(),
                ObiProbeBodyKind.SEARCH,
            )
            val probeRequest = server.takeRequest()

            server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))
            ObiHttpClient(baseUrl = server.url("/")).fetchSearch("dedra")
            val productionRequest = server.takeRequest()

            assertEquals(productionRequest.path, probeRequest.path)
            assertFalse(productionRequest.getHeader("User-Agent") == SYNTHETIC_BROWSER_USER_AGENT)
            listOf("User-Agent", "Accept", "Accept-Language", "Accept-Encoding").forEach { header ->
                assertEquals(
                    "Header $header",
                    productionRequest.getHeader(header),
                    probeRequest.getHeader(header),
                )
            }
        }
    }

    @Test
    fun `endpoint probe URLs preserve exact control paths`() {
        val urls = ObiProbeUrls("https://www.obi.pl/".toHttpUrl())

        assertEquals("/", urls.root().encodedPath)
        assertEquals("/search/dedra/", urls.search().encodedPath)
        assertEquals("/p/3496072", urls.bareProduct().encodedPath)
        assertEquals(
            "/p/3496072/dragon-klej-uniwersalny-butapren-50-ml",
            urls.canonicalProduct().encodedPath,
        )

        val store = urls.storeChange(ObiProbeUrls.BARE_PRODUCT_REDIRECT)
        assertEquals("/api/disc/store/change", store.encodedPath)
        assertEquals("075", store.queryParameter("storeNumber"))
        assertEquals("/p/3496072", store.queryParameter("redirectUrl"))
    }

    @Test
    fun `isolated sessions do not share cookies while steps in one session do`() {
        MockWebServer().use { server ->
            val urls = ObiProbeUrls(server.url("/"))
            val firstSession = ObiProbeSession(
                server.url("/"),
                ObiProbeProfile.A_BASELINE,
                "unused",
            )

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "session=SECRET_ONE; Path=/")
                    .setBody("<html></html>"),
            )
            firstSession.get("bootstrap", urls.root(), ObiProbeBodyKind.GENERIC)

            server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))
            val sameSessionStep = firstSession.get(
                "same-session",
                urls.search(),
                ObiProbeBodyKind.SEARCH,
            )

            val secondSession = ObiProbeSession(
                server.url("/"),
                ObiProbeProfile.A_BASELINE,
                "unused",
            )
            server.enqueue(MockResponse().setResponseCode(200).setBody("<html></html>"))
            val isolatedStep = secondSession.get(
                "isolated",
                urls.search(),
                ObiProbeBodyKind.SEARCH,
            )

            assertTrue(sameSessionStep.hops.single().outgoingCookies.any { it.name == "session" })
            assertTrue(isolatedStep.hops.single().outgoingCookies.none { it.name == "session" })

            val report = ObiLiveProbeReport(
                sections = listOf(
                    ObiProbeSection("session-test", listOf(sameSessionStep, isolatedStep)),
                ),
            ).render()
            assertFalse(report.contains("SECRET_ONE"))
        }
    }

    @Test
    fun `A E and G sequence sessions remain isolated while bootstrap cookies stay local`() {
        listOf(
            ObiProbeProfile.A_BASELINE,
            ObiProbeProfile.E_COMBINED_HTML,
            ObiProbeProfile.G_BROWSER_HTML,
        ).forEach { profile ->
            MockWebServer().use { server ->
                val urls = ObiProbeUrls(server.url("/"))
                val s0 = ObiProbeSession(server.url("/"), profile, "Towarownik/Test")
                val s1 = ObiProbeSession(server.url("/"), profile, "Towarownik/Test")
                val s2 = ObiProbeSession(server.url("/"), profile, "Towarownik/Test")

                server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
                val s0Store = s0.get(
                    "s0-store",
                    urls.storeChange(ObiProbeUrls.BARE_PRODUCT_REDIRECT),
                    ObiProbeBodyKind.PRODUCT,
                )

                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("Set-Cookie", "s1=SECRET_ONE; Path=/")
                        .setBody("<html></html>"),
                )
                s1.get("s1-root", urls.root(), ObiProbeBodyKind.GENERIC)
                server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
                val s1Store = s1.get(
                    "s1-store",
                    urls.storeChange(ObiProbeUrls.BARE_PRODUCT_REDIRECT),
                    ObiProbeBodyKind.PRODUCT,
                )

                server.enqueue(
                    MockResponse()
                        .setResponseCode(200)
                        .addHeader("Set-Cookie", "s2=SECRET_TWO; Path=/")
                        .setBody("<html></html>"),
                )
                s2.get("s2-product", urls.canonicalProduct(), ObiProbeBodyKind.PRODUCT)
                server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
                val s2Store = s2.get(
                    "s2-store",
                    urls.storeChange(ObiProbeUrls.BARE_PRODUCT_REDIRECT),
                    ObiProbeBodyKind.PRODUCT,
                )

                assertTrue(s0Store.hops.single().outgoingCookies.isEmpty())
                assertTrue(s1Store.hops.single().outgoingCookies.any { it.name == "s1" })
                assertTrue(s1Store.hops.single().outgoingCookies.none { it.name == "s2" })
                assertTrue(s2Store.hops.single().outgoingCookies.any { it.name == "s2" })
                assertTrue(s2Store.hops.single().outgoingCookies.none { it.name == "s1" })

                val report = ObiLiveProbeReport(
                    sections = listOf(
                        ObiProbeSection("s0", listOf(s0Store)),
                        ObiProbeSection("s1", listOf(s1Store)),
                        ObiProbeSection("s2", listOf(s2Store)),
                    ),
                ).render()
                assertFalse(report.contains("SECRET_ONE"))
                assertFalse(report.contains("SECRET_TWO"))
            }
        }
    }

    @Test
    fun `probe report redacts sensitive query and Location values`() {
        MockWebServer().use { server ->
            val secret = "VERY_SECRET"
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/challenge?token=$secret"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("x-amz-cf-pop", "WAW50-P1")
                    .addHeader("x-amz-error-code", "Example")
                    .setBody("<html><title>Challenge</title></html>"),
            )

            val step = ObiProbeSession(
                server.url("/"),
                ObiProbeProfile.A_BASELINE,
                "unused",
            ).get(
                "secret",
                server.url("/start?token=$secret"),
                ObiProbeBodyKind.GENERIC,
            )
            val report = ObiLiveProbeReport(
                sections = listOf(ObiProbeSection("redaction", listOf(step))),
            ).render()

            assertFalse(report.contains(secret))
            assertTrue(report.contains("token=REDACTED"))
            assertEquals("WAW50-P1", step.hops.last().safeInfrastructureHeaders["x-amz-cf-pop"])
            assertEquals("Example", step.hops.last().safeInfrastructureHeaders["x-amz-error-code"])
            assertEquals("HTTP/1.1", step.hops.last().protocol)
        }
    }

    @Test
    fun `bare and canonical product controls remain distinguishable in report`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            val urls = ObiProbeUrls(server.url("/"))
            val bare = ObiProbeSession(server.url("/"), ObiProbeProfile.A_BASELINE, "unused")
                .get("product-bare", urls.bareProduct(), ObiProbeBodyKind.PRODUCT)
            val canonical = ObiProbeSession(server.url("/"), ObiProbeProfile.A_BASELINE, "unused")
                .get("product-canonical", urls.canonicalProduct(), ObiProbeBodyKind.PRODUCT)

            val report = ObiLiveProbeReport(
                sections = listOf(ObiProbeSection("endpoint-baseline", listOf(bare, canonical))),
            ).render()

            assertTrue(report.contains("step=product-bare"))
            assertTrue(report.contains("/p/3496072"))
            assertTrue(report.contains("step=product-canonical"))
            assertTrue(report.contains("/p/3496072/dragon-klej-uniwersalny-butapren-50-ml"))
        }
    }

    @Test
    fun `full runner keeps probe in separate grouped matrix`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(404).setBody("")
            }

            val report = ObiLiveProbeRunner(
                baseUrl = server.url("/"),
                nativeUserAgent = "Towarownik/Test",
                ioDispatcher = Dispatchers.Unconfined,
            ).run()
            val rendered = report.render()

            assertTrue(rendered.contains("=== OBI LIVE PROBE ==="))
            assertTrue(rendered.contains("[endpoint-baseline]"))
            assertTrue(rendered.contains("[profile-A]"))
            assertTrue(rendered.contains("[profile-B]"))
            assertTrue(rendered.contains("[profile-C]"))
            assertTrue(rendered.contains("[profile-D]"))
            assertTrue(rendered.contains("[profile-E]"))
            assertTrue(rendered.contains("[profile-F]"))
            assertTrue(rendered.contains("[profile-G]"))
            assertTrue(rendered.contains("[session-S0-baseline]"))
            assertTrue(rendered.contains("[session-S1-baseline]"))
            assertTrue(rendered.contains("[session-S2-baseline]"))
            assertTrue(rendered.contains("[session-S0-profile-E]"))
            assertTrue(rendered.contains("[session-S1-profile-E]"))
            assertTrue(rendered.contains("[session-S2-profile-E]"))
            assertTrue(rendered.contains("[session-S0-profile-G]"))
            assertTrue(rendered.contains("[session-S1-profile-G]"))
            assertTrue(rendered.contains("[session-S2-profile-G]"))
            assertTrue(rendered.contains("synthetic-browser-like-android-chrome-ua-only"))
            assertTrue(rendered.contains("synthetic-browser-like-android-chrome-html-navigation"))
            assertEquals(27, server.requestCount)
        }
    }

    @Test
    fun `live probe reports bounded preview size and honest truncation semantics`() {
        MockWebServer().use { server ->
            val urls = ObiProbeUrls(server.url("/"))

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("a".repeat(70_000)),
            )
            val truncated = ObiProbeSession(
                server.url("/"),
                ObiProbeProfile.A_BASELINE,
                "unused",
            ).get("large", urls.root(), ObiProbeBodyKind.GENERIC)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("short"),
            )
            val complete = ObiProbeSession(
                server.url("/"),
                ObiProbeProfile.A_BASELINE,
                "unused",
            ).get("short", urls.root(), ObiProbeBodyKind.GENERIC)

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("b".repeat(65_536)),
            )
            val exactLimit = ObiProbeSession(
                server.url("/"),
                ObiProbeProfile.A_BASELINE,
                "unused",
            ).get("exact-limit", urls.root(), ObiProbeBodyKind.GENERIC)

            assertEquals(65_536, truncated.bodyPreview!!.previewLimitBytes)
            assertEquals(true, truncated.bodyPreview!!.previewTruncated)
            assertTrue(truncated.bodyPreview!!.previewUtf8Bytes <= 65_536)

            assertEquals(false, complete.bodyPreview!!.previewTruncated)
            assertEquals(5, complete.bodyPreview!!.previewUtf8Bytes)

            assertNull(exactLimit.bodyPreview!!.previewTruncated)

            val report = ObiLiveProbeReport(
                sections = listOf(
                    ObiProbeSection("preview", listOf(truncated, complete, exactLimit)),
                ),
            ).render()
            assertTrue(report.contains("body.previewLimitBytes=65536"))
            assertTrue(report.contains("body.previewTruncated=true"))
            assertTrue(report.contains("body.previewTruncated=false"))
            assertTrue(report.contains("body.previewTruncated=unknown"))
            assertFalse(report.contains("body.decodedBodyUtf8Bytes="))
        }
    }

    @Test
    fun `browser profile G can supply store opportunity for redirect target control`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val isBrowserHtml =
                        request.getHeader("User-Agent") == SYNTHETIC_BROWSER_USER_AGENT &&
                            request.getHeader("Accept") == HTML_ACCEPT_VALUE &&
                            request.getHeader("Accept-Language") == POLISH_LANGUAGE_VALUE
                    val isStore = request.requestUrl?.encodedPath == "/api/disc/store/change"

                    return if (isStore && isBrowserHtml) {
                        MockResponse().setResponseCode(200).setBody("<html>store ok</html>")
                    } else {
                        MockResponse().setResponseCode(404).setBody("")
                    }
                }
            }

            val report = ObiLiveProbeRunner(
                baseUrl = server.url("/"),
                nativeUserAgent = "Towarownik/Test",
                ioDispatcher = Dispatchers.Unconfined,
            ).run()
            val rendered = report.render()

            assertTrue(
                rendered.contains(
                    "[store-redirect-target-control-profile-G-bootstrap-none]",
                ),
            )
            assertTrue(rendered.contains("step=redirect-target-bare"))
            assertTrue(rendered.contains("step=redirect-target-canonical"))
            assertTrue(rendered.contains("profile=G"))
            assertTrue(rendered.contains("redirectUrl=REDACTED"))
            assertEquals(29, server.requestCount)
        }
    }

    private companion object {
        const val HTML_ACCEPT_VALUE =
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val POLISH_LANGUAGE_VALUE = "pl-PL,pl;q=0.9"
    }
}
