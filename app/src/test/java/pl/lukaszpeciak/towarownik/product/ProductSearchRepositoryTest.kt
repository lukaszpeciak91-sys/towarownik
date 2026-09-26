package pl.lukaszpeciak.towarownik.product

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductSearchRepositoryTest {
    @Test
    fun `search returns at most five ordered candidates`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture("search-qbrick-results.html")))
            val repository = ProductSearchRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            val result = repository.search("qbrick system")

            assertTrue(result is ProductSearchResult.Candidates)
            assertEquals(
                listOf("7013998", "7014053", "6647598", "1234567", "2345678"),
                (result as ProductSearchResult.Candidates).items.map { it.obik },
            )
        }
    }

    @Test
    fun `explicit empty search maps to not found`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture("search-empty.html")))
            val repository = ProductSearchRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            assertEquals(ProductSearchResult.NotFound, repository.search("missing"))
        }
    }

    @Test
    fun `HTTP 404 maps to not found`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val repository = ProductSearchRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            assertEquals(ProductSearchResult.NotFound, repository.search("missing"))
        }
    }

    @Test
    fun `confirmed CloudFront edge 404 is network failure not not found`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .addHeader("Server", "CloudFront")
                    .addHeader("x-cache", "Error from cloudfront")
                    .setBody(""),
            )
            val repository = ProductSearchRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            val result = repository.search("dedra")

            assertTrue(result is ProductSearchResult.Unavailable)
            assertEquals(
                ProductLookupFailure.NETWORK,
                (result as ProductSearchResult.Unavailable).failure,
            )
        }
    }

    @Test
    fun `malformed search payload maps to data failure not not found`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(fixture("search-malformed.html")))
            val repository = ProductSearchRepository(
                httpClient = ObiHttpClient(server.url("/")),
            )

            val result = repository.search("qbrick")

            assertTrue(result is ProductSearchResult.Unavailable)
            assertEquals(
                ProductLookupFailure.DATA,
                (result as ProductSearchResult.Unavailable).failure,
            )
        }
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/obi/$name")).readText()
}
