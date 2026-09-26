package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.ManualProductSearchResult
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchCandidate

class ManualSearchControllerTest {
    @Test
    fun `manual OBIK search stays direct and independent from advisor proxy`() = runBlocking {
        var searched = false
        val controller = ManualSearchController(
            lookupObik = {
                ProductLookupResult.Found(product(obik = it))
            },
            searchProducts = {
                searched = true
                error("OBIK must not use search")
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit("7313810") { states += it }

        assertFalse(searched)
        assertEquals(ManualSearchUiState.Loading, states.first())
        val product = (states.last() as ManualSearchUiState.Product).item
        assertEquals("7313810", product.obik)
        assertEquals("https://example.invalid/p/7313810/canonical", product.productUrl)
    }

    @Test
    fun `manual text search exposes candidates in chunks of five`() = runBlocking {
        val candidates = (1..12).map { index ->
            ProductSearchCandidate(
                obik = (1_000_000 + index).toString(),
                name = "Synthetic $index",
            )
        }
        val controller = ManualSearchController(
            lookupObik = { error("Text search must remain selectable") },
            searchProducts = {
                ManualProductSearchResult.Candidates(
                    items = candidates,
                    reportedTotalCount = 706,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit("synthetic") { states += it }

        var results = states.last() as ManualSearchUiState.SearchResults
        assertEquals(706, results.reportedTotalCount)
        assertEquals(5, results.visibleItems.size)
        assertTrue(results.canShowMore)

        results = results.showMore()
        assertEquals(10, results.visibleItems.size)
        assertTrue(results.canShowMore)

        results = results.showMore()
        assertEquals(12, results.visibleItems.size)
        assertFalse(results.canShowMore)
    }

    @Test
    fun `single exact EAN candidate keeps existing verification semantics`() = runBlocking {
        val controller = ManualSearchController(
            lookupObik = { obik ->
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        ean = EAN,
                    ),
                )
            },
            searchProducts = {
                ManualProductSearchResult.Candidates(
                    items = listOf(
                        ProductSearchCandidate("7013998", "Candidate"),
                    ),
                    reportedTotalCount = 1,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit(EAN) { states += it }

        assertTrue(states.last() is ManualSearchUiState.Product)
    }

    @Test
    fun `single mismatched EAN candidate stays selectable`() = runBlocking {
        val controller = ManualSearchController(
            lookupObik = { obik ->
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        ean = "1111111111111",
                    ),
                )
            },
            searchProducts = {
                ManualProductSearchResult.Candidates(
                    items = listOf(
                        ProductSearchCandidate("7013998", "Candidate"),
                    ),
                    reportedTotalCount = 1,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit(EAN) { states += it }

        val results = states.last() as ManualSearchUiState.SearchResults
        assertEquals(listOf("7013998"), results.items.map { it.obik })
    }

    @Test
    fun `selected candidate uses exact product url without reconstruction`() = runBlocking {
        val expectedUrl = "https://www.obi.pl/p/1234567/exact-canonical-product"
        val controller = ManualSearchController(
            lookupObik = { obik ->
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        productUrl = expectedUrl,
                    ),
                )
            },
            searchProducts = { error("Selection must not search again") },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.select(
            ManualSearchResultItem(
                obik = "1234567",
                name = "Candidate",
            ),
        ) { states += it }

        val exact = (states.last() as ManualSearchUiState.Product).item
        assertEquals(expectedUrl, exact.productUrl)
        assertEquals("1234567", exact.obik)
    }

    @Test
    fun `stock zero and unknown stock have distinct display semantics`() {
        assertEquals(
            "Stan Nowy Sącz: 0 szt. — brak na stanie",
            formatStore075Stock(0),
        )
        assertEquals(
            "Stan Nowy Sącz: brak danych",
            formatStore075Stock(null),
        )
        assertEquals(
            "Stan Nowy Sącz: 7 szt.",
            formatStore075Stock(7),
        )
    }

    @Test
    fun `unknown price stays unavailable`() {
        assertEquals(
            "Cena Nowy Sącz: brak danych",
            formatStore075Price(null),
        )
        assertEquals(
            "Cena Nowy Sącz: 14.99 zł",
            formatStore075Price(BigDecimal("14.99")),
        )
    }

    private fun product(
        obik: String,
        ean: String? = null,
        productUrl: String = "https://example.invalid/p/$obik/canonical",
    ) = LocalProduct(
        obik = obik,
        name = "Exact synthetic product",
        stock = 4,
        grossPrice = BigDecimal("19.99"),
        productUrl = productUrl,
        ean = ean,
    )

    private companion object {
        const val EAN = "5901238255642"
    }
}
