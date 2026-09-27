package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.ProductLookupFailure
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchCandidate
import pl.lukaszpeciak.towarownik.product.ProductSearchResult

class FindObiProductsToolTest {
    @Test
    fun `not found becomes verified empty products result`() = runBlocking {
        val tool = tool(
            search = { ProductSearchResult.NotFound },
            lookup = { _, _ -> error("lookup must not run") },
        )

        val result = tool.execute(arguments())

        assertEquals(
            AdvisorToolExecutionResult.Success(
                result = AdvisorVerifiedToolResult(
                    query = "klej",
                    products = emptyList(),
                ),
                snapshots = emptyList(),
            ),
            result,
        )
    }

    @Test
    fun `search unavailable is tool failure not empty result`() = runBlocking {
        val tool = tool(
            search = {
                ProductSearchResult.Unavailable(
                    ProductLookupFailure.NETWORK,
                    "synthetic",
                )
            },
            lookup = { _, _ -> error("lookup must not run") },
        )

        assertEquals(
            AdvisorToolExecutionResult.Failure,
            tool.execute(arguments()),
        )
    }

    @Test
    fun `candidate list invokes exact OBIK lookup and exact name is authoritative`() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate("1234567", "Candidate name"),
                    ),
                )
            },
            lookup = { obik, _ ->
                lookedUp += obik
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        name = "Exact product name",
                        stock = 4,
                        price = BigDecimal("19.99"),
                    ),
                )
            },
        )

        val result = tool.execute(arguments()) as AdvisorToolExecutionResult.Success

        assertEquals(listOf("1234567"), lookedUp)
        assertEquals("Exact product name", result.result.products.single().name)
    }

    @Test
    fun `tool respects requested limit and searches sequential candidates`() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate("1234567", null),
                        ProductSearchCandidate("2345678", null),
                        ProductSearchCandidate("3456789", null),
                    ),
                )
            },
            lookup = { obik, _ ->
                lookedUp += obik
                ProductLookupResult.Found(
                    product(obik = obik),
                )
            },
        )

        val result = tool.execute(arguments(limit = 2)) as AdvisorToolExecutionResult.Success

        assertEquals(listOf("1234567", "2345678"), lookedUp)
        assertEquals(2, result.result.products.size)
    }

    @Test
    fun `advisor tool remains capped at five even when more candidates are supplied`() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val candidates = (1..25).map { index ->
            ProductSearchCandidate(
                obik = (5_000_000 + index).toString(),
                name = "Synthetic $index",
            )
        }
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(candidates)
            },
            lookup = { obik, _ ->
                lookedUp += obik
                ProductLookupResult.Found(product(obik = obik))
            },
        )

        val result = tool.execute(arguments(limit = 5)) as AdvisorToolExecutionResult.Success

        assertEquals(5, lookedUp.size)
        assertEquals(5, result.result.products.size)
    }

    @Test
    fun `stock zero remains zero and unknown stock and price remain null`() = runBlocking {
        val products = mapOf(
            "1234567" to product(
                obik = "1234567",
                stock = 0,
                price = BigDecimal("0.99"),
            ),
            "2345678" to product(
                obik = "2345678",
                stock = null,
                price = null,
            ),
        )
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    products.keys.map { ProductSearchCandidate(it, null) },
                )
            },
            lookup = { obik, _ ->
                ProductLookupResult.Found(checkNotNull(products[obik]))
            },
        )

        val result = tool.execute(arguments(limit = 2)) as AdvisorToolExecutionResult.Success

        assertEquals(0, result.result.products[0].stock)
        assertEquals(null, result.result.products[1].stock)
        assertEquals(null, result.result.products[1].price)
    }

    @Test
    fun `failed exact lookup never creates fake product`() = runBlocking {
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(ProductSearchCandidate("1234567", "Candidate")),
                )
            },
            lookup = { _, _ ->
                ProductLookupResult.Unavailable(
                    ProductLookupFailure.DATA,
                    "synthetic",
                )
            },
        )

        assertEquals(
            AdvisorToolExecutionResult.Failure,
            tool.execute(arguments()),
        )
    }

    @Test
    fun `successful products plus failed candidates return only verified successes`() = runBlocking {
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate("1234567", "First"),
                        ProductSearchCandidate("2345678", "Second"),
                    ),
                )
            },
            lookup = { obik, _ ->
                if (obik == "1234567") {
                    ProductLookupResult.Found(
                        product(obik = obik, name = "Verified"),
                    )
                } else {
                    ProductLookupResult.Unavailable(
                        ProductLookupFailure.NETWORK,
                        "synthetic",
                    )
                }
            },
        )

        val result = tool.execute(arguments(limit = 2)) as AdvisorToolExecutionResult.Success

        assertEquals(1, result.result.products.size)
        assertEquals("1234567", result.result.products.single().obik)
        assertEquals("Verified", result.result.products.single().name)
    }

    @Test
    fun `all exact lookups fail so result is failure not verified empty`() = runBlocking {
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate("1234567", null),
                        ProductSearchCandidate("2345678", null),
                    ),
                )
            },
            lookup = { _, _ ->
                ProductLookupResult.Unavailable(
                    ProductLookupFailure.NETWORK,
                    "synthetic",
                )
            },
        )

        assertEquals(
            AdvisorToolExecutionResult.Failure,
            tool.execute(arguments(limit = 2)),
        )
    }

    @Test
    fun `exact lookup also retains trusted local snapshot with product url`() = runBlocking {
        val trustedUrl = "https://www.obi.pl/p/1234567/trusted-canonical"
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(ProductSearchCandidate("1234567", "Candidate")),
                )
            },
            lookup = { _, _ ->
                ProductLookupResult.Found(
                    product(
                        obik = "1234567",
                        name = "Verified exact",
                        stock = 2,
                        price = BigDecimal("12.30"),
                        productUrl = trustedUrl,
                    ),
                )
            },
        )

        val result = tool.execute(arguments()) as AdvisorToolExecutionResult.Success
        val snapshot = result.snapshots.single()

        assertEquals("1234567", snapshot.obik)
        assertEquals("Verified exact", snapshot.name)
        assertEquals(2, snapshot.stock)
        assertEquals(BigDecimal("12.30"), snapshot.grossPrice)
        assertEquals(trustedUrl, snapshot.productUrl)
        assertEquals(1_234_567L, snapshot.verifiedAt)
    }

    @Test
    fun `alternate store propagates to every exact lookup and compact result`() = runBlocking {
        val stores = mutableListOf<String>()
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(ProductSearchCandidate("3496072", "Candidate")),
                )
            },
            lookup = { obik, storeNumber ->
                stores += storeNumber
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        stock = 13,
                        storeNumber = storeNumber,
                    ),
                )
            },
        )

        val result = tool.execute(
            arguments(storeNumber = "074"),
        ) as AdvisorToolExecutionResult.Success

        assertEquals(listOf("074"), stores)
        assertEquals("074", result.result.storeNumber)
        assertEquals("074", result.snapshots.single().storeNumber)
    }

    @Test
    fun `unsupported store fails before search or lookup`() = runBlocking {
        var searched = false
        var lookedUp = false
        val tool = tool(
            search = {
                searched = true
                ProductSearchResult.NotFound
            },
            lookup = { _, _ ->
                lookedUp = true
                error("must not run")
            },
        )

        val result = tool.execute(
            arguments(storeNumber = "999"),
        )

        assertEquals(
            AdvisorToolExecutionResult.UnsupportedStore,
            result,
        )
        assertEquals(false, searched)
        assertEquals(false, lookedUp)
    }

    @Test
    fun `compact result exposes only verified product fields`() = runBlocking {
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(ProductSearchCandidate("1234567", "Candidate")),
                )
            },
            lookup = { _, _ ->
                ProductLookupResult.Found(
                    product(
                        obik = "1234567",
                        name = "Verified",
                        stock = 7,
                        price = BigDecimal("12.34"),
                    ),
                )
            },
        )

        val result = tool.execute(arguments()) as AdvisorToolExecutionResult.Success
        val verified = result.result.products.single()

        assertEquals("1234567", verified.obik)
        assertEquals("Verified", verified.name)
        assertEquals(7, verified.stock)
        assertEquals(BigDecimal("12.34"), verified.price)
        assertTrue(verified.toString().contains("productUrl").not())
    }

    private fun tool(
        search: (String) -> ProductSearchResult,
        lookup: (String, String) -> ProductLookupResult,
    ): FindObiProductsTool =
        FindObiProductsTool(
            searchProducts = search,
            lookupObik = lookup,
            ioDispatcher = Dispatchers.Unconfined,
            now = { 1_234_567L },
        )

    private fun arguments(
        query: String = "klej",
        storeNumber: String = "075",
        limit: Int = 5,
    ) = AdvisorToolArguments(
        query = query,
        storeNumber = storeNumber,
        limit = limit,
    )

    private fun product(
        obik: String,
        name: String = "Synthetic exact product",
        stock: Int? = 3,
        price: BigDecimal? = BigDecimal("10.00"),
        productUrl: String = "https://example.invalid/p/$obik",
        storeNumber: String = "075",
    ) = LocalProduct(
        obik = obik,
        name = name,
        stock = stock,
        grossPrice = price,
        productUrl = productUrl,
        ean = null,
        storeNumber = storeNumber,
    )
}
