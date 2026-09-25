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
            assertEquals("okhttp/4.12.0", operation.userAgent)
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
    fun `captures redirect followed by final 404`() {
        MockWebServer().use { server ->
            val recorder = enabledRecorder()
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/p/3496072"),
            )
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            val repository = productRepository(server, recorder)

            val result = repository.lookupObik("3496072")

            assertTrue(result is ProductLookupResult.Unavailable)
            assertEquals(
                listOf(302, 404),
                recorder.snapshots().single().hops.map { it.status },
            )
            assertTrue(
                recorder.snapshots().single().finalUrl!!.contains("/p/3496072"),
            )
        }
    }

    @Test
    fun `successful product page records parser decisions and sanitized cookie evidence`() {
        MockWebServer().use { server ->
            val recorder = enabledRecorder()
            val secret = "VERY_SECRET_COOKIE_VALUE"
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/p/7313810")
                    .addHeader("Set-Cookie", "store=$secret; Path=/"),
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
            assertTrue(operation.outgoingCookies.any { it.name == "store" })
            assertTrue(operation.setCookies.any { it.name == "store" })
            assertTrue(operation.storeContextCookiePresent)
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
            assertTrue(report.contains("outgoingCookieNames=store"))
            assertTrue(report.contains("setCookieNames=store"))
            assertFalse(report.contains(secret))
        }
    }

    @Test
    fun `search diagnostics expose candidates and unrelated zero token before current no result decision`() {
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

            assertEquals(ProductSearchResult.NotFound, result)
            val operation = recorder.snapshots().single()
            assertEquals(true, operation.bodySignatures!!.containsSearchResultsPhrase)
            assertEquals(true, operation.bodySignatures!!.containsZeroCountToken)
            assertTrue(operation.parserStages.contains("CANDIDATE_LINK_COUNT=2"))
            assertTrue(operation.parserStages.contains("CANDIDATE_COUNT_AFTER_DEDUPE=2"))
            assertTrue(operation.parserStages.contains("ZERO_RESULT_RULE=WYNIKI_DLA_PLUS_(0)"))
            assertTrue(operation.parserStages.contains("FINAL_PARSE_RESULT=NO_RESULTS"))
            assertTrue(operation.errorMappingTrace.contains("SearchParseResult.NoResults"))
            assertTrue(operation.errorMappingTrace.contains("UI NOT_FOUND"))
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
