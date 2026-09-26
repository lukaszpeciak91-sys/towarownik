package pl.lukaszpeciak.towarownik.product

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.diagnostics.DiagnosticDeviceContext
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticInputType
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticRecorder
import pl.lukaszpeciak.towarownik.diagnostics.Store075CookieMatch

class ObiDiagnosticsIntegrationTest {
    @Test
    fun `captures one response without redirect`() {
        MockWebServer().use { server ->
            val recorder = enabledRecorder()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody("<html><title>Search</title><body>Wyniki dla qbrick (1)</body></html>"),
            )
            val client = ObiHttpClient(
                baseUrl = server.url("/"),
                diagnostics = recorder,
            )

            val result = client.fetchSearch(
                query = "qbrick",
                inputType = ObiDiagnosticInputType.TEXT,
            )
            recorder.finish(result.diagnosticId())

            val operation = recorder.snapshots().single()
            assertEquals(listOf(200), operation.hops.map { it.status })
            assertEquals(200, operation.finalStatus)
            assertTrue(operation.finalUrl!!.contains("/search/qbrick/"))
            assertEquals(ObiBrowserCompatibilityProfile.USER_AGENT, operation.userAgent)
            assertTrue(operation.bodySignatures!!.looksLikeHtml)
        }
    }

    @Test
    fun `captures bounded multi hop redirect chain`() {
        MockWebServer().use { server ->
            val recorder = enabledRecorder()
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/first"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/second"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("<html><body>done</body></html>"),
            )
            val client = ObiHttpClient(
                baseUrl = server.url("/"),
                diagnostics = recorder,
            )

            val result = client.fetchSearch(
                query = "qbrick",
                inputType = ObiDiagnosticInputType.TEXT,
            )
            recorder.finish(result.diagnosticId())

            val operation = recorder.snapshots().single()
            assertEquals(listOf(302, 302, 200), operation.hops.map { it.status })
            assertEquals("/first", operation.hops[0].location)
            assertEquals("/second", operation.hops[1].location)
            assertTrue(operation.finalUrl!!.endsWith("/second"))
        }
    }

    @Test
    fun `captures initial store endpoint 404 and final error mapping`() {
        MockWebServer().use { server ->
            val recorder = enabledRecorder()
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            val repository = productRepository(server, recorder)

            val result = repository.lookupObik("3496072")

            assertTrue(result is ProductLookupResult.Unavailable)
            assertEquals(
                ProductLookupFailure.NOT_FOUND,
                (result as ProductLookupResult.Unavailable).failure,
            )
            val operation = recorder.snapshots().single()
            assertEquals(listOf(404), operation.hops.map { it.status })
            assertTrue(operation.hops.single().url.contains("/api/disc/store/change"))
            assertTrue(operation.errorMappingTrace.contains("ObiHttpFailureKind.NOT_FOUND"))
            assertTrue(operation.errorMappingTrace.contains("ProductLookupFailure.NOT_FOUND"))
            assertTrue(operation.errorMappingTrace.contains("UI NOT_FOUND"))
        }
    }

    @Test
    fun `redirect final 404 captures challenge signatures and redacts URL secrets`() {
        MockWebServer().use { server ->
            val recorder = enabledRecorder()
            val secret = "VERY_SECRET_CHALLENGE_TOKEN"
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/challenge?token=$secret"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .addHeader("Content-Type", "text/html; charset=utf-8")
                    .setBody(
                        """
                        <!doctype html>
                        <html>
                        <head>
                          <title>Access Denied</title>
                          <link rel="canonical" href="https://www.obi.pl/p/3496072?token=$secret">
                        </head>
                        <body>
                          challenge __NUXT_DATA__ 3496072 selectedStore 075
                        </body>
                        </html>
                        """.trimIndent(),
                    ),
            )
            val repository = productRepository(server, recorder)

            val result = repository.lookupObik("3496072")

            assertTrue(result is ProductLookupResult.Unavailable)
            assertEquals(
                ProductLookupFailure.NOT_FOUND,
                (result as ProductLookupResult.Unavailable).failure,
            )
            val operation = recorder.snapshots().single()
            assertEquals(listOf(302, 404), operation.hops.map { it.status })
            assertEquals("/challenge?token=REDACTED", operation.hops[0].location)
            assertTrue(operation.finalUrl!!.contains("/challenge?token=REDACTED"))

            val body = operation.bodySignatures!!
            assertEquals("Access Denied", body.title)
            assertTrue(body.accessDeniedOrChallenge)
            assertTrue(body.containsNuxtData)
            assertEquals(true, body.containsRequestedObik)
            assertEquals(
                "https://www.obi.pl/p/3496072?token=REDACTED",
                body.canonicalUrl,
            )
            assertTrue(body.decodedBodyUtf8Bytes > 0)

            val report = recorder.report()
            assertFalse(report.contains(secret))
            assertTrue(report.contains("body.accessDeniedOrChallenge=true"))
            assertTrue(report.contains("body.containsNuxtData=true"))
            assertTrue(report.contains("body.containsRequestedObik=true"))
            assertTrue(report.contains("finalStatus=404"))
        }
    }

    @Test
    fun `successful product page records matching store 075 before product request`() {
        MockWebServer().use { server ->
            val recorder = enabledRecorder()
            val sessionSecret = "VERY_SECRET_SESSION_VALUE"
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/p/7313810")
                    .addHeader("Set-Cookie", "store=075; Path=/")
                    .addHeader("Set-Cookie", "session=$sessionSecret; Path=/"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Content-Type", "text/html")
                    .setBody(fixture("real-7313810-store-075.html")),
            )
            val repository = productRepository(server, recorder)

            val result = repository.lookupObik("7313810")

            assertTrue(result is ProductLookupResult.Found)
            val operation = recorder.snapshots().single()
            assertEquals(listOf(302, 200), operation.hops.map { it.status })
            assertEquals(
                Store075CookieMatch.MATCH,
                operation.hops[0].setCookieStore075Match,
            )
            assertEquals(
                Store075CookieMatch.MATCH,
                operation.hops[1].outgoingStore075CookieMatch,
            )
            assertEquals(Store075CookieMatch.MATCH, operation.store075CookieMatch)
            assertTrue(operation.outgoingCookies.any { it.name == "store" })
            assertTrue(operation.setCookies.any { it.name == "store" })
            assertTrue(operation.bodySignatures!!.containsNuxtData)
            assertEquals(true, operation.bodySignatures!!.containsSelectedStore)
            assertEquals(true, operation.bodySignatures!!.containsStore075)
            assertTrue(operation.parserStages.contains("NUXT_JSON_PARSE_OK"))
            assertTrue(operation.parserStages.contains("PRODUCT_ID_MATCH"))
            assertTrue(operation.parserStages.contains("STORE_075_MATCH"))
            assertTrue(operation.parserStages.contains("STOCK_PRESENT"))
            assertTrue(operation.parserStages.contains("PRICE_PRESENT"))
            assertTrue(operation.parserStages.contains("FINAL_PARSE_RESULT=SUCCESS"))

            val report = recorder.report()
            assertTrue(report.contains("store075CookieMatch=true"))
            assertTrue(report.contains("outgoingStore075CookieMatch=true"))
            assertTrue(report.contains("setCookieStore075Match=true"))
            assertFalse(report.contains("store=075"))
            assertFalse(report.contains(sessionSecret))
        }
    }

    @Test
    fun `different store cookie is reported false without exposing its value`() {
        MockWebServer().use { server ->
            val recorder = enabledRecorder()
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/p/7313810")
                    .addHeader("Set-Cookie", "store=999; Path=/"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("<html><body>product</body></html>"),
            )
            val client = ObiHttpClient(
                baseUrl = server.url("/"),
                diagnostics = recorder,
            )

            val result = client.fetchProduct("7313810", "075")
            recorder.finish(result.diagnosticId())

            val operation = recorder.snapshots().single()
            assertEquals(
                Store075CookieMatch.MISMATCH,
                operation.hops.last().outgoingStore075CookieMatch,
            )
            assertEquals(Store075CookieMatch.MISMATCH, operation.store075CookieMatch)
            val report = recorder.report()
            assertTrue(report.contains("store075CookieMatch=false"))
            assertFalse(report.contains("store=999"))
        }
    }

    @Test
    fun `unrecognized cookie keeps store 075 match unknown and hides cookie value`() {
        MockWebServer().use { server ->
            val recorder = enabledRecorder()
            val secret = "VERY_SECRET_SESSION"
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/p/7313810")
                    .addHeader("Set-Cookie", "session=$secret; Path=/"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("<html><body>product</body></html>"),
            )
            val client = ObiHttpClient(
                baseUrl = server.url("/"),
                diagnostics = recorder,
            )

            val result = client.fetchProduct("7313810", "075")
            recorder.finish(result.diagnosticId())

            val operation = recorder.snapshots().single()
            assertEquals(
                Store075CookieMatch.UNKNOWN,
                operation.hops.last().outgoingStore075CookieMatch,
            )
            assertEquals(Store075CookieMatch.UNKNOWN, operation.store075CookieMatch)
            val report = recorder.report()
            assertTrue(report.contains("store075CookieMatch=unknown"))
            assertTrue(report.contains("session"))
            assertFalse(report.contains(secret))
        }
    }

    @Test
    fun `search diagnostics use positive result count and ignore unrelated zero token`() {
        MockWebServer().use { server ->
            val recorder = enabledRecorder()
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        <html><body>
                        <h1>Wyniki dla dedra (2)</h1>
                        <a href="/p/1234567/dedra-one">Dedra One</a>
                        <a href="/p/2345678/dedra-two">Dedra Two</a>
                        <span class="cart">Koszyk (0)</span>
                        </body></html>
                        """.trimIndent(),
                    ),
            )
            val repository = ProductSearchRepository(
                httpClient = ObiHttpClient(
                    baseUrl = server.url("/"),
                    diagnostics = recorder,
                ),
                parser = ObiSearchParser(diagnostics = recorder),
                diagnostics = recorder,
            )

            val result = repository.search("dedra")

            assertTrue(result is ProductSearchResult.Candidates)
            assertEquals(
                listOf("1234567", "2345678"),
                (result as ProductSearchResult.Candidates).items.map { it.obik },
            )
            val operation = recorder.snapshots().single()
            assertEquals(true, operation.bodySignatures!!.containsSearchResultsPhrase)
            assertEquals(2, operation.bodySignatures!!.detectedSearchResultCount)
            assertEquals(true, operation.bodySignatures!!.containsZeroCountToken)
            assertTrue(operation.parserStages.contains("CANDIDATE_LINK_COUNT=2"))
            assertTrue(operation.parserStages.contains("CANDIDATE_COUNT_AFTER_DEDUPE=2"))
            assertTrue(operation.parserStages.contains("SEARCH_RESULT_COUNT=2"))
            assertTrue(operation.parserStages.contains("ZERO_RESULT_RULE=NONE"))
            assertTrue(operation.parserStages.contains("FINAL_PARSE_RESULT=RESULTS"))
            assertEquals(listOf("HTTP 200"), operation.errorMappingTrace)
        }
    }

    private fun productRepository(
        server: MockWebServer,
        recorder: ObiDiagnosticRecorder,
    ): ProductLookupRepository =
        ProductLookupRepository(
            httpClient = ObiHttpClient(
                baseUrl = server.url("/"),
                diagnostics = recorder,
            ),
            parser = ObiPayloadParser(diagnostics = recorder),
            diagnostics = recorder,
        )

    private fun enabledRecorder(): ObiDiagnosticRecorder =
        ObiDiagnosticRecorder().apply {
            configureDeviceContext(
                DiagnosticDeviceContext(
                    versionName = "test",
                    versionCode = 1,
                    androidVersion = "test",
                    apiLevel = 33,
                    manufacturer = "test",
                    model = "test",
                ),
            )
            setEnabled(true)
        }

    private fun ObiHttpResult.diagnosticId(): Long? = when (this) {
        is ObiHttpResult.Success -> diagnosticId
        is ObiHttpResult.Failure -> diagnosticId
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/obi/$name")).readText()
}
