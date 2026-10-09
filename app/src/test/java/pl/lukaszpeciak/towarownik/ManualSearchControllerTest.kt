package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.conversation.PersistedSearchAction
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.ManualProductSearchResult
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchCandidate
import pl.lukaszpeciak.towarownik.product.toVerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProductProvider
import pl.lukaszpeciak.towarownik.product.provider.ProductProviderRegistry
import pl.lukaszpeciak.towarownik.product.provider.ProductRef
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.ProviderLookupResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct
import pl.lukaszpeciak.towarownik.product.provider.ProviderProductCandidate
import pl.lukaszpeciak.towarownik.product.provider.ProviderSearchResult
import pl.lukaszpeciak.towarownik.product.provider.WorkingProfile

class ManualSearchControllerTest {
    @Test
    fun `manual OBIK search stays direct and independent from advisor proxy`() = runBlocking {
        var searched = false
        val controller = ManualSearchController(
            lookupObik = { obik, _ ->
                ProductLookupResult.Found(product(obik = obik))
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
    fun `first five visible text results are enriched for active store`() = runBlocking {
        val candidates = (1..12).map { index ->
            ProductSearchCandidate(
                obik = (1_000_000 + index).toString(),
                name = "Synthetic $index",
            )
        }
        val lookups = mutableListOf<Pair<String, String>>()
        val controller = ManualSearchController(
            lookupObik = { obik, storeNumber ->
                lookups += obik to storeNumber
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        storeNumber = storeNumber,
                    ),
                )
            },
            searchProducts = {
                ManualProductSearchResult.Candidates(
                    items = candidates,
                    reportedTotalCount = 706,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit(
            input = "synthetic",
            storeNumber = "074",
        ) { states += it }

        val results = states.last() as ManualSearchUiState.SearchResults
        assertEquals(706, results.reportedTotalCount)
        assertEquals(5, results.visibleItems.size)
        assertEquals(
            candidates.take(5).map { it.obik },
            lookups.map { it.first },
        )
        assertTrue(lookups.all { it.second == "074" })
        assertTrue(
            results.visibleItems.all {
                it.enrichment is ManualResultEnrichment.Verified
            },
        )
        assertTrue(
            results.items.drop(5).all {
                it.enrichment is ManualResultEnrichment.Pending
            },
        )
    }

    @Test
    fun `advisor search action reuses exact query and store through existing manual paging`() = runBlocking {
        val action = PersistedSearchAction(
            query = "Czarne trytytki 200 mm",
            storeNumber = "074",
            reportedTotalCount = 27,
        )
        val request = advisorSearchActionOpenRequest(action)
        val candidates = (1..6).map { index ->
            ProductSearchCandidate(
                obik = (1_100_000 + index).toString(),
                name = "Synthetic $index",
            )
        }
        var searchedQuery: String? = null
        val lookedUpStores = mutableListOf<String>()
        val controller = ManualSearchController(
            lookupObik = { obik, storeNumber ->
                lookedUpStores += storeNumber
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        storeNumber = storeNumber,
                    ),
                )
            },
            searchProducts = { query ->
                searchedQuery = query
                ManualProductSearchResult.Candidates(
                    items = candidates,
                    reportedTotalCount = action.reportedTotalCount,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val initialStates = mutableListOf<ManualSearchUiState>()

        controller.submit(
            input = request.query,
            storeNumber = request.storeNumber,
        ) { initialStates += it }

        val initial =
            initialStates.last() as ManualSearchUiState.SearchResults
        assertEquals(action.query, searchedQuery)
        assertEquals(27, initial.reportedTotalCount)
        assertEquals(5, initial.visibleCount)
        assertEquals(List(5) { "074" }, lookedUpStores)

        val moreStates = mutableListOf<ManualSearchUiState>()
        controller.showMore(
            current = initial,
            storeNumber = request.storeNumber,
        ) { moreStates += it }

        val expanded =
            moreStates.last() as ManualSearchUiState.SearchResults
        assertEquals(6, expanded.visibleCount)
        assertEquals(List(6) { "074" }, lookedUpStores)
    }

    @Test
    fun `visible batch sorts positive stock descending then zero unknown and unavailable`() = runBlocking {
        val candidates = (1..5).map { index ->
            ProductSearchCandidate(
                obik = (1_000_000 + index).toString(),
                name = "Synthetic $index",
            )
        }
        val controller = ManualSearchController(
            lookupObik = { obik, storeNumber ->
                when (obik) {
                    "1000001" -> ProductLookupResult.Found(
                        product(
                            obik = obik,
                            stock = 0,
                            storeNumber = storeNumber,
                        ),
                    )
                    "1000002",
                    "1000003",
                    -> ProductLookupResult.Found(
                        product(
                            obik = obik,
                            stock = 7,
                            storeNumber = storeNumber,
                        ),
                    )
                    "1000004" -> ProductLookupResult.Found(
                        product(
                            obik = obik,
                            stock = null,
                            storeNumber = storeNumber,
                        ),
                    )
                    else -> ProductLookupResult.Unavailable(
                        failure = pl.lukaszpeciak.towarownik.product
                            .ProductLookupFailure.NETWORK,
                        reason = "synthetic",
                    )
                }
            },
            searchProducts = {
                ManualProductSearchResult.Candidates(
                    items = candidates,
                    reportedTotalCount = candidates.size,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit("synthetic", "075") { states += it }

        val resultStates =
            states.filterIsInstance<ManualSearchUiState.SearchResults>()
        val final = resultStates.last()
        assertEquals(
            listOf(
                "1000002",
                "1000003",
                "1000001",
                "1000004",
                "1000005",
            ),
            final.visibleItems.map { it.obik },
        )
        assertEquals(
            candidates.map { it.obik },
            resultStates[resultStates.lastIndex - 1]
                .visibleItems
                .map { it.obik },
        )
    }

    @Test
    fun `show more re-sorts all visible verified results and leaves hidden candidates untouched`() = runBlocking {
        val candidates = (1..12).map { index ->
            ProductSearchCandidate(
                obik = (1_000_000 + index).toString(),
                name = "Synthetic $index",
            )
        }
        val stockByObik = mapOf(
            "1000001" to 5,
            "1000002" to 4,
            "1000003" to 3,
            "1000004" to 2,
            "1000005" to 1,
            "1000006" to 10,
            "1000007" to 8,
            "1000008" to 0,
            "1000009" to null,
            "1000010" to 6,
        )
        val controller = ManualSearchController(
            lookupObik = { obik, storeNumber ->
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        stock = stockByObik[obik],
                        storeNumber = storeNumber,
                    ),
                )
            },
            searchProducts = {
                ManualProductSearchResult.Candidates(
                    items = candidates,
                    reportedTotalCount = candidates.size,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val initialStates = mutableListOf<ManualSearchUiState>()
        controller.submit("synthetic", "075") {
            initialStates += it
        }
        val initial =
            initialStates.last() as ManualSearchUiState.SearchResults

        val moreStates = mutableListOf<ManualSearchUiState>()
        controller.showMore(initial, "075") {
            moreStates += it
        }

        val expanded =
            moreStates.last() as ManualSearchUiState.SearchResults
        assertEquals(
            listOf(
                "1000006",
                "1000007",
                "1000010",
                "1000001",
                "1000002",
                "1000003",
                "1000004",
                "1000005",
                "1000008",
                "1000009",
            ),
            expanded.visibleItems.map { it.obik },
        )
        assertEquals(
            listOf("1000011", "1000012"),
            expanded.items.drop(expanded.visibleCount).map { it.obik },
        )
        assertTrue(
            expanded.items.drop(expanded.visibleCount).all {
                it.enrichment is ManualResultEnrichment.Pending
            },
        )
    }

    @Test
    fun `show more is blocked while visible enrichment is unfinished`() = runBlocking {
        var lookups = 0
        val controller = ManualSearchController(
            lookupObik = { _, _ ->
                lookups += 1
                error("Show more must not lookup while visible batch is unfinished")
            },
            searchProducts = {
                error("Show more must not search")
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val current = ManualSearchUiState.SearchResults(
            items = (1..10).map { index ->
                ManualSearchResultItem(
                    obik = (1_000_000 + index).toString(),
                    name = "Synthetic $index",
                    enrichment = when (index) {
                        1 -> ManualResultEnrichment.Loading
                        in 2..5 -> ManualResultEnrichment.Verified(
                            product(
                                obik = (1_000_000 + index).toString(),
                            ).toVerifiedProductSnapshot(verifiedAt = 0L)
                                .toVerifiedProductUiModel(),
                        )
                        else -> ManualResultEnrichment.Pending
                    },
                )
            },
            reportedTotalCount = 10,
            visibleCount = 5,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.showMore(current, "075") { states += it }

        assertFalse(current.canShowMore)
        assertEquals(0, lookups)
        assertEquals(listOf(current), states)
        assertEquals(5, current.visibleCount)
    }

    @Test
    fun `show more enriches only newly visible next five`() = runBlocking {
        val candidates = (1..12).map { index ->
            ProductSearchCandidate(
                obik = (1_000_000 + index).toString(),
                name = "Synthetic $index",
            )
        }
        val lookups = mutableListOf<String>()
        val controller = ManualSearchController(
            lookupObik = { obik, storeNumber ->
                lookups += obik
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        storeNumber = storeNumber,
                    ),
                )
            },
            searchProducts = {
                ManualProductSearchResult.Candidates(
                    items = candidates,
                    reportedTotalCount = 12,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val initialStates = mutableListOf<ManualSearchUiState>()
        controller.submit("synthetic", "075") {
            initialStates += it
        }
        val initial =
            initialStates.last() as ManualSearchUiState.SearchResults
        assertEquals(5, lookups.size)

        val moreStates = mutableListOf<ManualSearchUiState>()
        controller.showMore(initial, "075") {
            moreStates += it
        }

        val expanded =
            moreStates.last() as ManualSearchUiState.SearchResults
        assertEquals(10, expanded.visibleCount)
        assertEquals(
            candidates.take(10).map { it.obik },
            lookups,
        )
        assertTrue(
            expanded.items.take(10).all {
                it.enrichment is ManualResultEnrichment.Verified
            },
        )
        assertTrue(
            expanded.items.drop(10).all {
                it.enrichment is ManualResultEnrichment.Pending
            },
        )
    }

    @Test
    fun `one exact enrichment failure does not destroy result list`() = runBlocking {
        val candidates = (1..5).map { index ->
            ProductSearchCandidate(
                obik = (1_000_000 + index).toString(),
                name = "Synthetic $index",
            )
        }
        val controller = ManualSearchController(
            lookupObik = { obik, storeNumber ->
                if (obik == "1000003") {
                    ProductLookupResult.Unavailable(
                        failure = pl.lukaszpeciak.towarownik.product
                            .ProductLookupFailure.NETWORK,
                        reason = "synthetic",
                    )
                } else {
                    ProductLookupResult.Found(
                        product(
                            obik = obik,
                            storeNumber = storeNumber,
                        ),
                    )
                }
            },
            searchProducts = {
                ManualProductSearchResult.Candidates(
                    items = candidates,
                    reportedTotalCount = 5,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit("synthetic", "075") { states += it }

        val results =
            states.last() as ManualSearchUiState.SearchResults
        assertEquals(5, results.items.size)
        assertTrue(
            results.items.single { it.obik == "1000003" }
                .enrichment is ManualResultEnrichment.Unavailable,
        )
        assertTrue(
            results.items.filter { it.obik != "1000003" }
                .all {
                    it.enrichment is
                        ManualResultEnrichment.Verified
                },
        )
        assertEquals(
            "1000003",
            results.visibleItems.last().obik,
        )
    }

    @Test
    fun `verified enrichment preserves zero and unknown store facts and trusted url`() = runBlocking {
        val candidates = listOf(
            ProductSearchCandidate("1000001", "Zero"),
            ProductSearchCandidate("1000002", "Unknown"),
        )
        val controller = ManualSearchController(
            lookupObik = { obik, storeNumber ->
                ProductLookupResult.Found(
                    if (obik == "1000001") {
                        product(
                            obik = obik,
                            stock = 0,
                            grossPrice = BigDecimal("29.99"),
                            productUrl =
                                "https://www.obi.pl/p/$obik/trusted",
                            storeNumber = storeNumber,
                        )
                    } else {
                        product(
                            obik = obik,
                            stock = null,
                            grossPrice = null,
                            productUrl =
                                "https://www.obi.pl/p/$obik/trusted",
                            storeNumber = storeNumber,
                        )
                    },
                )
            },
            searchProducts = {
                ManualProductSearchResult.Candidates(
                    items = candidates,
                    reportedTotalCount = 2,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit("synthetic", "075") { states += it }

        val results =
            states.last() as ManualSearchUiState.SearchResults
        val zero = (
            results.items[0].enrichment as
                ManualResultEnrichment.Verified
            ).product
        assertEquals(0, zero.stock)
        assertEquals(BigDecimal("29.99"), zero.grossPrice)
        assertEquals(
            "https://www.obi.pl/p/1000001/trusted",
            zero.productUrl,
        )
        assertEquals("075", zero.storeNumber)

        val unknown = (
            results.items[1].enrichment as
                ManualResultEnrichment.Verified
            ).product
        assertEquals(null, unknown.stock)
        assertEquals(null, unknown.grossPrice)
    }

    @Test
    fun `verified exact product name replaces discovery candidate name`() {
        val item = ManualSearchResultItem(
            obik = "1234567",
            name = "Discovery name",
            enrichment = ManualResultEnrichment.Verified(
                VerifiedProductUiModel(
                    name = "Exact verified name",
                    obik = "1234567",
                    grossPrice = BigDecimal("10.00"),
                    stock = 1,
                    productUrl = "https://www.obi.pl/p/1234567/exact",
                    storeNumber = "075",
                ),
            ),
        )

        assertEquals(
            "Exact verified name",
            manualResultDisplayName(item),
        )
    }

    @Test
    fun `verified exact product name is shown when candidate name is null`() {
        val item = ManualSearchResultItem(
            obik = "1234567",
            name = null,
            enrichment = ManualResultEnrichment.Verified(
                VerifiedProductUiModel(
                    name = "Exact verified name",
                    obik = "1234567",
                    grossPrice = null,
                    stock = null,
                    productUrl = "https://www.obi.pl/p/1234567/exact",
                    storeNumber = "075",
                ),
            ),
        )

        assertEquals(
            "Exact verified name",
            manualResultDisplayName(item),
        )
    }

    @Test
    fun `LocalProduct snapshot and UI model preserve primary image url`() {
        val imageUrl =
            "https://bilder.obi.pl/example/pr08A/image.jpeg"
        val local = product(
            obik = "1234567",
            primaryImageUrl = imageUrl,
        )

        val snapshot = local.toVerifiedProductSnapshot(
            verifiedAt = 123L,
        )
        val ui = snapshot.toVerifiedProductUiModel()

        assertEquals(imageUrl, local.primaryImageUrl)
        assertEquals(imageUrl, snapshot.primaryImageUrl)
        assertEquals(imageUrl, ui.primaryImageUrl)
    }

    @Test
    fun `manual exact enrichment carries primary image url`() = runBlocking {
        val imageUrl =
            "https://bilder.obi.pl/example/pr08A/image.jpeg"
        val controller = ManualSearchController(
            lookupObik = { obik, storeNumber ->
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        storeNumber = storeNumber,
                        primaryImageUrl = imageUrl,
                    ),
                )
            },
            searchProducts = {
                ManualProductSearchResult.Candidates(
                    items = listOf(
                        ProductSearchCandidate(
                            "1234567",
                            "Candidate",
                        ),
                    ),
                    reportedTotalCount = 1,
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit("candidate", "075") {
            states += it
        }

        val results =
            states.last() as ManualSearchUiState.SearchResults
        val verified = (
            results.items.single().enrichment as
                ManualResultEnrichment.Verified
            ).product
        assertEquals(imageUrl, verified.primaryImageUrl)
    }

    @Test
    fun `single exact EAN candidate keeps existing verification semantics`() = runBlocking {
        val controller = ManualSearchController(
            lookupObik = { obik, _ ->
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
            lookupObik = { obik, _ ->
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
            lookupObik = { obik, _ ->
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
        assertEquals(expectedUrl, verifiedProductOpenUrl(exact))
        assertEquals("1234567", exact.obik)
    }

    @Test
    fun `direct OBIK and EAN exact verification use selected store`() = runBlocking {
        val lookedUpStores = mutableListOf<String>()
        val controller = ManualSearchController(
            lookupObik = { obik, storeNumber ->
                lookedUpStores += storeNumber
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        ean = EAN,
                        storeNumber = storeNumber,
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

        val direct = mutableListOf<ManualSearchUiState>()
        controller.submit(
            input = "7313810",
            storeNumber = "074",
        ) { direct += it }
        assertEquals(
            "074",
            (direct.last() as ManualSearchUiState.Product)
                .item
                .storeNumber,
        )

        val ean = mutableListOf<ManualSearchUiState>()
        controller.submit(
            input = EAN,
            storeNumber = "078",
        ) { ean += it }
        assertEquals(
            "078",
            (ean.last() as ManualSearchUiState.Product)
                .item
                .storeNumber,
        )
        assertEquals(listOf("074", "078"), lookedUpStores)
    }

    @Test
    fun `stock null zero and positive select distinct localized resources`() {
        assertEquals(
            R.string.product_stock_unknown,
            stockStringRes(null),
        )
        assertEquals(
            R.string.product_stock_zero,
            stockStringRes(0),
        )
        assertEquals(
            R.string.product_stock_count,
            stockStringRes(7),
        )
    }

    @Test
    fun `price null and present select distinct localized resources`() {
        assertEquals(
            R.string.product_price_unknown,
            priceStringRes(null),
        )
        assertEquals(
            R.string.product_price,
            priceStringRes(BigDecimal("14.99")),
        )
    }

    @Test
    fun `KWANT manual direct product keeps separate branch and central quantities`() = runBlocking {
        val profile = WorkingProfile(KWANT_PROVIDER_ID, BranchId("205"))
        val cases: List<Pair<Int?, Int?>> = listOf(
            4 to 100,
            0 to 100,
            0 to null,
            null to 100,
        )
        for ((branchStock, centralStock) in cases) {
            val controller = manualProviderController(
                providerId = KWANT_PROVIDER_ID,
                branchId = "205",
                quantities = linkedMapOf("580" to (branchStock to centralStock)),
            )
            val states = mutableListOf<ManualSearchUiState>()
            controller.select(
                item = ManualSearchResultItem(
                    ref = ProductRef(KWANT_PROVIDER_ID, "580"),
                    name = "Synthetic KWANT product",
                    branchId = profile.branchId,
                    branchLabel = "Nowy Sącz",
                ),
                workingProfile = profile,
            ) { states += it }

            val ui = (states.last() as ManualSearchUiState.Product).item
            assertEquals("kwant-pl", ui.providerId)
            assertEquals("205", ui.branchId)
            assertEquals("Nowy Sącz", ui.branchLabel)
            assertEquals(branchStock, ui.stock)
            assertEquals(centralStock, ui.centralStock)
        }
    }

    @Test
    fun `KWANT manual search and show more retain central stock but sort by selected branch`() = runBlocking {
        val quantities: LinkedHashMap<String, Pair<Int?, Int?>> = linkedMapOf(
            "580" to (0 to 1000),
            "581" to (3 to 1),
            "582" to (1 to 100),
            "583" to (2 to 50),
            "584" to (null to 999),
            "585" to (4 to 2),
            "586" to (0 to 1500),
        )
        val controller = manualProviderController(
            providerId = KWANT_PROVIDER_ID,
            branchId = "205",
            quantities = quantities,
        )
        val profile = WorkingProfile(KWANT_PROVIDER_ID, BranchId("205"))
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit(
            input = "wyłącznik nadprądowy",
            workingProfile = profile,
            branchLabel = "Nowy Sącz",
        ) { states += it }

        val initial = states.last() as ManualSearchUiState.SearchResults
        assertEquals(
            listOf("581", "583", "582", "580", "584"),
            initial.visibleItems.map { it.ref.productId },
        )
        assertTrue(initial.canShowMore)
        val zero = initial.visibleItems.single { it.ref.productId == "580" }
            .enrichment as ManualResultEnrichment.Verified
        assertEquals(0, zero.product.stock)
        assertEquals(1000, zero.product.centralStock)
        assertEquals(3, (
            initial.visibleItems.first().enrichment as ManualResultEnrichment.Verified
        ).product.stock)
        assertEquals(1, (
            initial.visibleItems.first().enrichment as ManualResultEnrichment.Verified
        ).product.centralStock)

        val moreStates = mutableListOf<ManualSearchUiState>()
        controller.showMore(
            current = initial,
            workingProfile = profile,
        ) { moreStates += it }
        val expanded = moreStates.last() as ManualSearchUiState.SearchResults
        assertEquals(
            listOf("585", "581", "583", "582", "580", "586", "584"),
            expanded.visibleItems.map { it.ref.productId },
        )
        for (item in expanded.visibleItems) {
            val verified = item.enrichment as ManualResultEnrichment.Verified
            val expected = quantities.getValue(item.ref.productId)
            assertEquals(expected.first, verified.product.stock)
            assertEquals(expected.second, verified.product.centralStock)
        }
    }

    @Test
    fun `OBI manual direct lookup retains null central stock`() = runBlocking {
        val controller = manualProviderController(
            providerId = OBI_PROVIDER_ID,
            branchId = "075",
            quantities = linkedMapOf("7313810" to (6 to null)),
        )
        val states = mutableListOf<ManualSearchUiState>()
        controller.submit(
            input = "7313810",
            workingProfile = WorkingProfile(OBI_PROVIDER_ID, BranchId("075")),
        ) { states += it }

        val ui = (states.last() as ManualSearchUiState.Product).item
        assertEquals(OBI_PROVIDER_ID.value, ui.providerId)
        assertEquals(6, ui.stock)
        assertEquals(null, ui.centralStock)
    }

    private fun manualProviderController(
        providerId: ProviderId,
        branchId: String,
        quantities: Map<String, Pair<Int?, Int?>>,
    ): ManualSearchController {
        val provider = object : ProductProvider {
            override val providerId: ProviderId = providerId

            override fun branches(): ProviderBranchResult =
                ProviderBranchResult.Available(
                    listOf(
                        ProviderBranch(
                            branchId = BranchId(branchId),
                            name = if (providerId == KWANT_PROVIDER_ID) {
                                "Nowy Sącz"
                            } else {
                                "OBI"
                            },
                        ),
                    ),
                )

            override fun search(query: String, maxResults: Int): ProviderSearchResult =
                ProviderSearchResult.Candidates(
                    items = quantities.keys.take(maxResults).map { productId ->
                        ProviderProductCandidate(
                            ref = ProductRef(providerId, productId),
                            name = "Synthetic product $productId",
                        )
                    },
                    reportedTotalCount = quantities.size,
                )

            override fun lookup(ref: ProductRef, branchId: BranchId): ProviderLookupResult {
                if (ref.providerId != providerId || branchId.value != branchId) {
                    return ProviderLookupResult.WrongProvider(ref)
                }
                val (local, central) = quantities.getValue(ref.productId)
                return ProviderLookupResult.Found(
                    ProviderProduct(
                        ref = ref,
                        branchId = branchId,
                        name = "Verified product ${ref.productId}",
                        stock = local,
                        centralStock = central,
                        grossPrice = BigDecimal("14.55"),
                        priceScope = if (providerId == KWANT_PROVIDER_ID) {
                            ProviderPriceScope.ONLINE
                        } else {
                            ProviderPriceScope.BRANCH
                        },
                        productUrl = "https://example.invalid/product/${ref.productId}",
                        ean = null,
                    ),
                )
            }
        }
        return ManualSearchController(
            providers = ProductProviderRegistry(listOf(provider)),
            ioDispatcher = Dispatchers.Unconfined,
        )
    }

    private fun product(
        obik: String,
        ean: String? = null,
        productUrl: String = "https://example.invalid/p/$obik/canonical",
        storeNumber: String = "075",
        stock: Int? = 4,
        grossPrice: BigDecimal? = BigDecimal("19.99"),
        primaryImageUrl: String? = null,
    ) = LocalProduct(
        obik = obik,
        name = "Exact synthetic product",
        stock = stock,
        grossPrice = grossPrice,
        productUrl = productUrl,
        ean = ean,
        storeNumber = storeNumber,
        primaryImageUrl = primaryImageUrl,
    )

    private companion object {
        const val EAN = "5901238255642"
    }
}
