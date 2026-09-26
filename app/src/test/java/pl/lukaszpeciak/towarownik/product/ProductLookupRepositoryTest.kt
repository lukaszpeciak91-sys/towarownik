package pl.lukaszpeciak.towarownik.product

import java.math.BigDecimal
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductLookupRepositoryTest {
    @Test
    fun `invalid OBIK is rejected before any HTTP request`() {
        MockWebServer().use { server ->
            val repository = ProductLookupRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            val result = repository.lookupObik("123")

            assertEquals(ProductLookupResult.InvalidObik("123"), result)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `HTTP not found maps to not found failure`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val repository = ProductLookupRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            val result = repository.lookupObik(OBIK)

            assertTrue(result is ProductLookupResult.Unavailable)
            assertEquals(
                ProductLookupFailure.NOT_FOUND,
                (result as ProductLookupResult.Unavailable).failure,
            )
        }
    }

    @Test
    fun `confirmed CloudFront edge 404 maps to network failure`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .addHeader("Server", "CloudFront")
                    .addHeader("x-cache", "Error from cloudfront")
                    .setBody(""),
            )
            val repository = ProductLookupRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            val result = repository.lookupObik(OBIK)

            assertTrue(result is ProductLookupResult.Unavailable)
            assertEquals(
                ProductLookupFailure.NETWORK,
                (result as ProductLookupResult.Unavailable).failure,
            )
        }
    }

    @Test
    fun `server failure maps to network failure`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            val repository = ProductLookupRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            val result = repository.lookupObik(OBIK)

            assertTrue(result is ProductLookupResult.Unavailable)
            assertEquals(
                ProductLookupFailure.NETWORK,
                (result as ProductLookupResult.Unavailable).failure,
            )
        }
    }

    @Test
    fun `missing expected selected store structure maps to data failure`() {
        MockWebServer().use { server ->
            val changedPayload = fixture("real-7313810-store-075.html")
                .replace("\"selectedStore\":3", "\"storeContext\":3")
            server.enqueue(MockResponse().setBody(changedPayload))
            val repository = ProductLookupRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            val result = repository.lookupObik(OBIK)

            assertTrue(result is ProductLookupResult.Unavailable)
            assertEquals(
                ProductLookupFailure.DATA,
                (result as ProductLookupResult.Unavailable).failure,
            )
        }
    }

    @Test
    fun `malformed successful response maps to data failure instead of not found`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>not an OBI payload</html>"))
            val repository = ProductLookupRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            val result = repository.lookupObik(OBIK)

            assertTrue(result is ProductLookupResult.Unavailable)
            assertEquals(
                ProductLookupFailure.DATA,
                (result as ProductLookupResult.Unavailable).failure,
            )
        }
    }

    @Test
    fun `successful lookup behavior is unchanged`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture("real-7313810-store-075.html")))
            val repository = ProductLookupRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            val result = repository.lookupObik(OBIK)

            assertTrue(result is ProductLookupResult.Found)
            val product = (result as ProductLookupResult.Found).product
            assertEquals(OBIK, product.obik)
            assertEquals("Produkt OBI 7313810", product.name)
            assertEquals(17, product.stock)
            assertEquals(BigDecimal("123.45"), product.grossPrice)
            assertEquals("075", product.storeNumber)
        }
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/obi/$name")).readText()

    private companion object {
        const val OBIK = "7313810"
    }
}
