package pl.lukaszpeciak.towarownik.product

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObiHttpClientTest {
    @Test
    fun `store selection redirect preserves session cookie`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/p/7313810")
                    .addHeader("Set-Cookie", "store=075; Path=/"),
            )
            server.enqueue(MockResponse().setBody("<html>product</html>"))
            val client = ObiHttpClient(server.url("/").toString().toHttpUrl())

            assertTrue(client.fetchProduct("7313810", "075") is ObiHttpResult.Success)
            val selection = server.takeRequest()
            val product = server.takeRequest()
            assertEquals("075", selection.requestUrl?.queryParameter("storeNumber"))
            assertEquals("/p/7313810", selection.requestUrl?.queryParameter("redirectUrl"))
            assertEquals("/p/7313810", product.requestUrl?.encodedPath)
            assertEquals("store=075", product.headers["Cookie"])
        }
    }

    @Test
    fun `non successful response is a clean failure`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("temporary"))
            val result = ObiHttpClient(server.url("/")).fetchProduct("7313810", "075")
            assertEquals(ObiHttpResult.Failure("OBI returned HTTP 503"), result)
        }
    }
}
