package pl.lukaszpeciak.towarownik.product

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

            val result = repository.lookupObik("7313810")

            assertTrue(result is ProductLookupResult.Unavailable)
            assertEquals(
                ProductLookupFailure.NOT_FOUND,
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

            val result = repository.lookupObik("7313810")

            assertTrue(result is ProductLookupResult.Unavailable)
            assertEquals(
                ProductLookupFailure.NETWORK,
                (result as ProductLookupResult.Unavailable).failure,
            )
        }
    }

    @Test
    fun `malformed successful response maps to data failure`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>not an OBI payload</html>"))
            val repository = ProductLookupRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            val result = repository.lookupObik("7313810")

            assertTrue(result is ProductLookupResult.Unavailable)
            assertEquals(
                ProductLookupFailure.DATA,
                (result as ProductLookupResult.Unavailable).failure,
            )
        }
    }
}
