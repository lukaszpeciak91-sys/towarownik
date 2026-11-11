package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.provider.*

/** Actual Controller and discovery tool; synthetic provider, no HTTP/model calls. */
class ScopedDiscoveryCacheCoverageTest {
    private val query = "Karlik MINI taupe"
    private val user = "Sprawdź Karlik MINI taupe w KWANT Nowy Sącz i w KWANT Tarnów"
    private val conditional = "Sprawdź Karlik MINI taupe w KWANT Nowy Sącz 205, a brakujące w KWANT Tarnów 204. Podsumuj wyniki."
    private fun request(limit: Int, branch: String = "205", extra: List<AdvisorToolQuery> = emptyList()) =
        AdvisorToolArguments(branch, listOf(AdvisorToolQuery(query, limit)) + extra, "kwant-pl")
    private data class Seen(
        val executed: List<AdvisorToolArguments>, val outputs: List<AdvisorToolContinuation>, val state: AdvisorUiState,
    ) {
        fun groups(index: Int) = (outputs[index] as AdvisorToolContinuation.Verified).result.results
    }
    private fun run(
        requests: List<AdvisorToolArguments>, text: String = user, candidateCount: Int = 5,
        unavailableLimits: Set<Int> = emptySet(), primaryStock: Int? = 7,
        wrongThirdFallbackProduct: Boolean = false,
    ): Seen = runBlocking {
        val branches = listOf(
            ProviderBranch(BranchId("205"), "Nowy Sącz"), ProviderBranch(BranchId("204"), "Tarnów"),
        )
        val executed = mutableListOf<AdvisorToolArguments>()
        val outputs = mutableListOf<AdvisorToolContinuation>()
        val articleById = mutableMapOf<String, String>()
        var executingBranch = "205"
        val provider = object : ProductProvider {
            override val providerId = ProviderId("kwant-pl")
            override fun branches() = ProviderBranchResult.Available(branches)
            override fun search(query: String, maxResults: Int): ProviderSearchResult {
                if (maxResults in unavailableLimits) return ProviderSearchResult.Unavailable(ProductProviderFailure.NETWORK, "Synthetic")
                if (candidateCount == 0) return ProviderSearchResult.NotFound
                val base = if (query.trim().equals(this@ScopedDiscoveryCacheCoverageTest.query, true)) 90000 else 90100
                return ProviderSearchResult.Candidates((1..minOf(candidateCount, maxResults)).map { index ->
                    val id = (base + index + if (wrongThirdFallbackProduct && executingBranch == "204" && index == 3) 1000 else 0).toString()
                    articleById[id] = "SYNTHETIC_$id"
                    ProviderProductCandidate(ProductRef(providerId, id), "Synthetic", articleById[id])
                })
            }
            override fun lookup(ref: ProductRef, branchId: BranchId): ProviderLookupResult = ProviderLookupResult.Found(
                ProviderProduct(ref, branchId, "Synthetic ${ref.productId}", if (branchId.value == "205") primaryStock else 9,
                    grossPrice = BigDecimal.ONE, priceScope = ProviderPriceScope.ONLINE,
                    productUrl = "https://offline.invalid/${ref.productId}", ean = null, articleNumber = articleById[ref.productId]),
            )
        }
        val tool = FindProviderProductsTool(ProductProviderRegistry(listOf(provider)), now = { 8000L })
        var index = 0
        fun next(): AdvisorProxyCallResult {
            if (index < requests.size) {
                val n = index++
                return AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest("resp_$n", "call_$n", requests[n]))
            }
            val refs = outputs.filterIsInstance<AdvisorToolContinuation.Verified>().flatMap { out ->
                out.result.products.map { AdvisorProductRef(out.result.branchId, it.productId, "kwant-pl") }
            }.distinct().take(5)
            return AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_final", "Zweryfikowane przykłady, bez potwierdzenia kompletnego asortymentu.", refs))
        }
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { message, p, b -> assertEquals(text, message); assertEquals("kwant-pl", p); assertEquals("205", b); next() },
            messageAgent = { _, _, _, _ -> error("New conversation only") },
            continueAgent = { _, _, p, b, _, result -> assertEquals("kwant-pl", p); assertEquals("205", b); outputs += result; next() },
            executeObiTool = { error("OBI execution forbidden") },
            executeProviderTool = { args -> executingBranch = args.branchId; executed += args; tool.execute(args) },
            executeLocationsTool = { error("Inventory execution forbidden") },
            branchDirectory = { ProviderBranchResult.Available(branches) },
        )
        val state = controller.runTurn(text, null, "205", "kwant-pl") {}
        println("CACHE_COVERAGE requested=${requests.map { it.branchId to it.queries }} executed=${executed.map { it.branchId to it.queries }}")
        Seen(executed, outputs, state)
    }

    @Test fun increasingLimitRequiresFreshBoundedSearchAndAllVerifiedSnapshots() {
        val r = run(listOf(request(1), request(3)))
        assertEquals("Limit 1 is not coverage for limit 3", listOf(1, 3), r.executed.map { it.queries.single().limit })
        assertEquals(listOf(1, 3), r.outputs.indices.map { r.groups(it).single().products.size })
        val cards = (r.state as AdvisorUiState.Success).products
        assertEquals(setOf("90001", "90002", "90003"), cards.map { it.effectiveProductId }.toSet())
        assertTrue(cards.all { it.verifiedAt == 8000L && it.stock == 7 && it.effectiveBranchId == "205" })
    }
    @Test fun decreasingLimitReusesTheExistingLargerRead() {
        val r = run(listOf(request(3), request(1)))
        assertEquals(listOf(3), r.executed.map { it.queries.single().limit })
        assertEquals(listOf(3, 1), r.outputs.indices.map { r.groups(it).single().products.size })
    }
    @Test fun smallerProjectionDoesNotDestroyLargerCachedCoverage() {
        val r = run(listOf(request(3), request(1), request(3)))
        assertEquals(listOf(3), r.executed.map { it.queries.single().limit })
        assertEquals(listOf(3, 1, 3), r.outputs.indices.map { r.groups(it).single().products.size })
    }
    @Test fun fewerActualResultsDoNotProveExhaustionForALargerRequest() {
        val r = run(listOf(request(3), request(5)), candidateCount = 1)
        assertEquals(listOf(3, 5), r.executed.map { it.queries.single().limit })
        assertEquals(listOf(1, 1), r.outputs.indices.map { r.groups(it).single().products.size })
        assertEquals(1, (r.state as AdvisorUiState.Success).products.size)
    }
    @Test fun notFoundDoesNotInventLargerCoverageOrZeroStock() {
        val r = run(listOf(request(1), request(3)), candidateCount = 0)
        assertEquals(listOf(1, 3), r.executed.map { it.queries.single().limit })
        assertTrue(r.outputs.indices.all { r.groups(it).single().status == AdvisorQueryResultStatus.NOT_FOUND })
        assertTrue((r.state as AdvisorUiState.Success).products.isEmpty())
    }
    @Test fun overlappingBatchRefreshesOnlyTheInsufficientQuery() {
        val extra = listOf(AdvisorToolQuery("Synthetic frame", 1))
        val r = run(listOf(request(1, extra = extra), request(3, extra = extra)))
        assertEquals(listOf(listOf(query, "Synthetic frame"), listOf(query)), r.executed.map { a -> a.queries.map { it.query } })
        assertEquals(listOf(3, 1), r.groups(1).map { it.products.size })
        assertEquals(4, (r.state as AdvisorUiState.Success).products.size)
    }
    @Test fun anotherAuthorizedBranchNeverBorrowsTheOtherBranchRead() {
        val r = run(listOf(request(3), request(1, "204")))
        assertEquals(listOf("205", "204"), r.executed.map { it.branchId })
        val cards = (r.state as AdvisorUiState.Success).products
        assertEquals(setOf("205", "204"), cards.map { it.effectiveBranchId }.toSet())
        assertTrue(cards.all { it.stock == if (it.effectiveBranchId == "205") 7 else 9 })
    }
    @Test fun threeLogicalCallsRemainTheBudgetEvenWhenCoverageGrows() {
        val r = run(listOf(request(1), request(3), request(3), request(5)))
        assertEquals(listOf(1, 3), r.executed.map { it.queries.single().limit })
        assertEquals(4, r.outputs.size)
        assertTrue(r.outputs.last() is AdvisorToolContinuation.LocalToolLimitReached)
    }
    @Test fun duplicateMixedLimitsRefreshOnceAndReturnEachRequestedProjection() {
        for (limits in listOf(listOf(1, 3), listOf(3, 1))) {
            val second = request(limits.first(), extra = listOf(AdvisorToolQuery(query, limits.last())))
            val r = run(listOf(request(1), second))
            assertEquals(listOf(1, 3), r.executed.map { it.queries.single().limit })
            assertEquals(limits, r.groups(1).map { it.products.size })
        }
    }
    @Test fun failedLargerReadNeverUpgradesCachedCoverage() {
        val r = run(listOf(request(1), request(3), request(3)), unavailableLimits = setOf(3))
        assertEquals(listOf(1, 3, 3), r.executed.map { it.queries.single().limit })
        assertEquals(AdvisorQueryResultStatus.UNAVAILABLE, r.groups(1).single().status)
        assertEquals(AdvisorQueryResultStatus.UNAVAILABLE, r.groups(2).single().status)
    }
    @Test fun conditionalFallbackCannotBorrowASmallerPrimarySearch() {
        val r = run(listOf(request(1), request(3, "204")), text = conditional, primaryStock = 0)
        assertEquals(listOf("205"), r.executed.map { it.branchId })
        assertTrue(r.outputs.last() is AdvisorToolContinuation.RejectedStore)
    }
    @Test fun sufficientPrimaryZeroCoverageStillAllowsSmallerFallback() {
        val r = run(listOf(request(3), request(1, "204")), text = conditional, primaryStock = 0)
        assertEquals(listOf("205", "204"), r.executed.map { it.branchId })
        assertEquals(9, r.groups(1).single().products.single().stock)
    }
    @Test fun requestedCoverageIsIndependentOfTheActualResultCount() {
        val r = run(listOf(request(3), request(1)), candidateCount = 1)
        assertEquals(listOf(3), r.executed.map { it.queries.single().limit })
        assertEquals(listOf(1, 1), r.outputs.indices.map { r.groups(it).single().products.size })
    }
    @Test fun sufficientPrimaryCoverageAllowsFreshLargerFallback() {
        val r = run(listOf(request(3), request(1, "204"), request(3, "204")), text = conditional, primaryStock = 0)
        assertEquals(listOf("205" to 3, "204" to 1, "204" to 3), r.executed.map { it.branchId to it.queries.single().limit })
        assertEquals(setOf("90001", "90002", "90003"), r.groups(2).single().products.map { it.productId }.toSet())
    }
    @Test fun refreshedFallbackStillValidatesIdentityWhenASmallerCachedReadExists() {
        val r = run(listOf(request(3), request(1, "204"), request(3, "204")), text = conditional, primaryStock = 0, wrongThirdFallbackProduct = true)
        assertEquals(3, r.executed.size)
        assertEquals(AdvisorQueryResultStatus.UNAVAILABLE, r.groups(2).single().status)
        assertTrue(r.groups(2).single().products.isEmpty())
        assertTrue((r.state as AdvisorUiState.Success).products.none { it.effectiveProductId == "91003" })
    }
}
