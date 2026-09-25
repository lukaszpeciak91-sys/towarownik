package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchCandidate
import pl.lukaszpeciak.towarownik.product.ProductSearchInput
import pl.lukaszpeciak.towarownik.product.ProductSearchResult
import pl.lukaszpeciak.towarownik.product.classifyProductSearchInput

class ProductSearchControllerTest {
    @Test
    fun `classifies seven numeric digits as OBIK`() {
        assertEquals(
            ProductSearchInput.Obik("7313810"),
            classifyProductSearchInput("7313810"),
        )
    }

    @Test
    fun `classifies plausible numeric GTIN lengths as EAN`() {
        listOf("12345678", "123456789012", "5901238255642", "12345678901234").forEach { value ->
            assertEquals(ProductSearchInput.Ean(value), classifyProductSearchInput(value))
        }
    }

    @Test
    fun `classifies nonblank nonnumeric input as text`() {
        assertEquals(
            ProductSearchInput.Text("qbrick pro"),
            classifyProductSearchInput("  qbrick pro  "),
        )
        assertEquals(
            ProductSearchInput.Text("abc123"),
            classifyProductSearchInput("abc123"),
        )
    }

    @Test
    fun `blank and unsupported numeric lengths are invalid`() {
        listOf("", "   ", "123456", "123456789").forEach { value ->
            assertEquals(ProductSearchInput.Invalid, classifyProductSearchInput(value))
        }
    }

    @Test
    fun `OBIK lookup remains direct and does not invoke search`() = runBlocking {
        var searched = false
        val controller = ProductSearchController(
            lookupObik = { ProductLookupResult.Found(product(obik = it, ean = null)) },
            searchProducts = {
                searched = true
                error("OBIK must not use search")
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ProductSearchUiState>()

        controller.submit("7313810") { states += it }

        assertTrue(!searched)
        assertEquals(ProductSearchUiState.Loading, states.first())
        assertEquals("Test product", (states.last() as ProductSearchUiState.Success).name)
    }

    @Test
    fun `multiple EAN search candidates remain selectable and are not auto picked`() = runBlocking {
        var lookupCalls = 0
        val candidates = listOf(
            ProductSearchCandidate("7013998", "First"),
            ProductSearchCandidate("7014053", "Second"),
        )
        val controller = ProductSearchController(
            lookupObik = {
                lookupCalls += 1
                ProductLookupResult.Found(product(obik = it, ean = EAN))
            },
            searchProducts = { ProductSearchResult.Candidates(candidates) },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ProductSearchUiState>()

        controller.submit(EAN) { states += it }

        assertEquals(0, lookupCalls)
        assertEquals(
            ProductSearchUiState.SearchResults(
                listOf(
                    SearchResultItem("7013998", "First"),
                    SearchResultItem("7014053", "Second"),
                ),
            ),
            states.last(),
        )
    }

    @Test
    fun `single exact EAN candidate proceeds directly to product lookup`() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val controller = ProductSearchController(
            lookupObik = { obik ->
                lookedUp += obik
                ProductLookupResult.Found(product(obik = obik, ean = EAN))
            },
            searchProducts = {
                ProductSearchResult.Candidates(
                    listOf(ProductSearchCandidate("7013998", "Qbrick")),
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ProductSearchUiState>()

        controller.submit(EAN) { states += it }

        assertEquals(listOf("7013998"), lookedUp)
        assertTrue(states.last() is ProductSearchUiState.Success)
    }

    @Test
    fun `single EAN candidate is not auto opened when payload EAN does not match`() = runBlocking {
        val controller = ProductSearchController(
            lookupObik = { obik ->
                ProductLookupResult.Found(product(obik = obik, ean = "1111111111111"))
            },
            searchProducts = {
                ProductSearchResult.Candidates(
                    listOf(ProductSearchCandidate("7013998", "Qbrick")),
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ProductSearchUiState>()

        controller.submit(EAN) { states += it }

        assertEquals(
            ProductSearchUiState.SearchResults(
                listOf(SearchResultItem("7013998", "Qbrick")),
            ),
            states.last(),
        )
    }

    @Test
    fun `text search results stay selectable even when only one candidate is returned`() = runBlocking {
        var lookupCalls = 0
        val controller = ProductSearchController(
            lookupObik = {
                lookupCalls += 1
                ProductLookupResult.Found(product(obik = it, ean = null))
            },
            searchProducts = {
                ProductSearchResult.Candidates(
                    listOf(ProductSearchCandidate("7013998", "Qbrick")),
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ProductSearchUiState>()

        controller.submit("qbrick") { states += it }

        assertEquals(0, lookupCalls)
        assertTrue(states.last() is ProductSearchUiState.SearchResults)
    }

    @Test
    fun `no search results map to normal not found state`() = runBlocking {
        val controller = ProductSearchController(
            lookupObik = { error("Lookup must not run") },
            searchProducts = { ProductSearchResult.NotFound },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ProductSearchUiState>()

        controller.submit("missing product") { states += it }

        assertEquals(ProductSearchUiState.Error(NOT_FOUND_MESSAGE), states.last())
    }

    @Test
    fun `selecting a search result triggers existing product lookup`() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val controller = ProductSearchController(
            lookupObik = { obik ->
                lookedUp += obik
                ProductLookupResult.Found(product(obik = obik, ean = null))
            },
            searchProducts = { error("Selection must not search again") },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ProductSearchUiState>()

        controller.select(SearchResultItem("7014053", "Selected")) { states += it }

        assertEquals(listOf("7014053"), lookedUp)
        assertEquals(ProductSearchUiState.Loading, states.first())
        assertTrue(states.last() is ProductSearchUiState.Success)
    }

    private fun product(obik: String, ean: String?) = LocalProduct(
        obik = obik,
        name = "Test product",
        stock = 4,
        grossPrice = BigDecimal("19.99"),
        productUrl = "https://www.obi.pl/p/$obik",
        ean = ean,
    )

    private companion object {
        const val EAN = "5901238255642"
    }
}
