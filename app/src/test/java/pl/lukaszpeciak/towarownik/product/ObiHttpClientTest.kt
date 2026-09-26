package pl.lukaszpeciak.towarownik.product

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObiHttpClientTest {
    @Test
    fun `production search uses proven browser compatible HTML profile`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>search</html>"))
            val client = ObiHttpClient(server.url("/"))

            assertTrue(client.fetchSearch("qbrick system") is ObiHttpResult.Success)

            val request = server.takeRequest()
            assertEquals(
                "/search/qbrick%20system/",
                request.requestUrl?.encodedPath,
            )
            assertEquals(
                ObiBrowserCompatibilityProfile.USER_AGENT,
                request.getHeader("User-Agent"),
            )
            assertEquals(
                ObiBrowserCompatibilityProfile.ACCEPT,
                request.getHeader("Accept"),
            )
            assertEquals(
                ObiBrowserCompatibilityProfile.ACCEPT_LANGUAGE,
                request.getHeader("Accept-Language"),
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `product lookup starts at unchanged store endpoint and preserves redirects cookies and profile`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/p/7313810")
                    .addHeader("Set-Cookie", "store=075; Path=/"),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(301)
                    .addHeader("Location", "/p/7313810/example-product"),
            )
            server.enqueue(MockResponse().setBody("<html>product</html>"))
            val client = ObiHttpClient(server.url("/").toString().toHttpUrl())

            assertTrue(client.fetchProduct("7313810", "075") is ObiHttpResult.Success)

            assertEquals(3, server.requestCount)
            val selection = server.takeRequest()
            val bareProduct = server.takeRequest()
            val canonicalProduct = server.takeRequest()

            assertEquals("/api/disc/store/change", selection.requestUrl?.encodedPath)
            assertEquals("075", selection.requestUrl?.queryParameter("storeNumber"))
            assertEquals("/p/7313810", selection.requestUrl?.queryParameter("redirectUrl"))
            assertEquals("/p/7313810", bareProduct.requestUrl?.encodedPath)
            assertEquals("/p/7313810/example-product", canonicalProduct.requestUrl?.encodedPath)

            listOf(selection, bareProduct, canonicalProduct).forEach { request ->
                assertEquals(
                    ObiBrowserCompatibilityProfile.USER_AGENT,
                    request.getHeader("User-Agent"),
                )
                assertEquals(
                    ObiBrowserCompatibilityProfile.ACCEPT,
                    request.getHeader("Accept"),
                )
                assertEquals(
                    ObiBrowserCompatibilityProfile.ACCEPT_LANGUAGE,
                    request.getHeader("Accept-Language"),
                )
            }

            assertNull(selection.getHeader("Cookie"))
            assertEquals("store=075", bareProduct.getHeader("Cookie"))
            assertEquals("store=075", canonicalProduct.getHeader("Cookie"))
        }
    }

    @Test
    fun `server response is classified separately from transport failure`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("temporary"))
            val result = ObiHttpClient(server.url("/")).fetchProduct("7313810", "075")
            assertEquals(
                ObiHttpResult.Failure(
                    ObiHttpFailureKind.SERVER,
                    "OBI returned HTTP 503",
                ),
                result,
            )
        }
    }

    @Test
    fun `confirmed empty CloudFront error 404 is infrastructure failure not not found`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .addHeader("Server", "CloudFront")
                    .addHeader("x-cache", "Error from cloudfront")
                    .setBody(""),
            )

            val result = ObiHttpClient(server.url("/")).fetchSearch("dedra")

            assertEquals(
                ObiHttpResult.Failure(
                    ObiHttpFailureKind.SERVER,
                    "OBI returned HTTP 404",
                ),
                result,
            )
        }
    }

    @Test
    fun `ordinary 404 remains explicitly not found`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            val result = ObiHttpClient(server.url("/")).fetchProduct("7313810", "075")
            assertEquals(
                ObiHttpResult.Failure(
                    ObiHttpFailureKind.NOT_FOUND,
                    "OBI returned HTTP 404",
                ),
                result,
            )
        }
    }

    @Test
    fun `CloudFront 404 with a body is not reclassified as confirmed edge failure`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .addHeader("Server", "CloudFront")
                    .addHeader("x-cache", "Error from cloudfront")
                    .setBody("application not found"),
            )

            val result = ObiHttpClient(server.url("/")).fetchSearch("missing")

            assertEquals(
                ObiHttpResult.Failure(
                    ObiHttpFailureKind.NOT_FOUND,
                    "OBI returned HTTP 404",
                ),
                result,
            )
        }
    }

    @Test
    fun `blank successful response is a data failure`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("   \n"))

            val result = ObiHttpClient(server.url("/")).fetchProduct("7313810", "075")

            assertEquals(
                ObiHttpResult.Failure(
                    ObiHttpFailureKind.DATA,
                    "OBI returned an empty product page",
                ),
                result,
            )
        }
    }
}
