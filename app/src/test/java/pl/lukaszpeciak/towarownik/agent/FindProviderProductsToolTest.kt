package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.TechnicalFact
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
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

class FindProviderProductsToolTest {
    @Test
    fun `KWANT product tool preserves provider branch article stock and online price scope`() = runBlocking {
        val provider = FakeKwantProvider()
        val tool = FindProviderProductsTool(
            providers = ProductProviderRegistry(listOf(provider)),
            ioDispatcher = Dispatchers.Unconfined,
            now = { 1234L },
        )

        val result = tool.execute(
            AdvisorToolArguments(
                providerId = "kwant-pl",
                storeNumber = "205",
                queries = listOf(
                    AdvisorToolQuery(
                        query = "MBN116E",
                        limit = 1,
                    ),
                ),
            ),
        ) as AdvisorToolExecutionResult.Success

        assertEquals(1, provider.searchCalls)
        assertEquals(listOf("205"), provider.lookupBranches)

        val verified = result.result.results.single()
            .products.single()
        assertEquals("580", verified.productId)
        assertEquals("MBN116E/HAG", verified.articleNumber)
        assertEquals(140, verified.stock)
        assertEquals(BigDecimal("14.55"), verified.price)
        assertEquals("online", verified.priceScope)

        val snapshot = result.snapshots.single()
        assertEquals("kwant-pl", snapshot.providerId)
        assertEquals("205", snapshot.branchId)
        assertEquals("580", snapshot.productId)
        assertEquals("MBN116E/HAG", snapshot.articleNumber)
        assertEquals(140, snapshot.stock)
        assertEquals(ProviderPriceScope.ONLINE, snapshot.priceScope)
        assertEquals(1234L, snapshot.verifiedAt)
        assertTrue(result.searchActions.isEmpty())
    }

    @Test
    fun `provider tool rescues one clearly query relevant late fact`() = runBlocking {
        val facts = (1..6).map {
            TechnicalFact("Parametr $it", "wartość $it")
        } + TechnicalFact("Prąd znamionowy", "16 A")
        val provider = FakeKwantProvider(technicalFacts = facts)
        val tool = FindProviderProductsTool(
            providers = ProductProviderRegistry(listOf(provider)),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = tool.execute(
            AdvisorToolArguments(
                providerId = "kwant-pl",
                storeNumber = "205",
                queries = listOf(
                    AdvisorToolQuery("wyłącznik 16 A", 1),
                ),
            ),
        ) as AdvisorToolExecutionResult.Success

        assertEquals(
            listOf(
                "Parametr 1",
                "Parametr 2",
                "Parametr 3",
                "Parametr 4",
                "Parametr 5",
                "Prąd znamionowy",
            ),
            result.result.products.single().technicalFacts.map { it.label },
        )
        assertEquals(
            "16 A",
            result.result.products.single().technicalFacts.last().value,
        )
    }

    @Test
    fun `unknown KWANT branch is rejected without search or lookup fallback`() = runBlocking {
        val provider = FakeKwantProvider()
        val tool = FindProviderProductsTool(
            providers = ProductProviderRegistry(listOf(provider)),
            ioDispatcher = Dispatchers.Unconfined,
        )

        val result = tool.execute(
            AdvisorToolArguments(
                providerId = "kwant-pl",
                storeNumber = "075",
                queries = listOf(
                    AdvisorToolQuery("MBN116E", 1),
                ),
            ),
        )

        assertEquals(
            AdvisorToolExecutionResult.UnsupportedStore,
            result,
        )
        assertEquals(0, provider.searchCalls)
        assertTrue(provider.lookupBranches.isEmpty())
    }

    @Test
    fun `explicit other KWANT branch resolves uniquely for one-off lookup`() = runBlocking {
        val provider = FakeKwantProvider()
        val result = FindProviderProductsTool(
            providers = ProductProviderRegistry(listOf(provider)),
            ioDispatcher = Dispatchers.Unconfined,
        ).execute(
            AdvisorToolArguments(
                providerId = "kwant-pl",
                storeNumber = "205",
                requestedBranch = "Tarnów",
                queries = listOf(AdvisorToolQuery("MBN116E", 1)),
            ),
        ) as AdvisorToolExecutionResult.Success

        assertEquals("310", result.result.branchId)
        assertEquals(listOf("310"), provider.lookupBranches)
    }

    @Test
    fun `ambiguous or unknown other branch is never guessed`() = runBlocking {
        val provider = FakeKwantProvider()
        val tool = FindProviderProductsTool(
            providers = ProductProviderRegistry(listOf(provider)),
            ioDispatcher = Dispatchers.Unconfined,
        )

        listOf("Tarnów Centrum", "Nieznany").forEach { requested ->
            assertEquals(
                AdvisorToolExecutionResult.UnsupportedStore,
                tool.execute(
                    AdvisorToolArguments(
                        providerId = "kwant-pl",
                        storeNumber = "205",
                        requestedBranch = requested,
                        queries = listOf(AdvisorToolQuery("MBN116E", 1)),
                    ),
                ),
            )
        }
        assertEquals(0, provider.searchCalls)
    }

    private class FakeKwantProvider(
        private val technicalFacts: List<TechnicalFact> = emptyList(),
    ) : ProductProvider {
        override val providerId: ProviderId = KWANT_PROVIDER_ID
        var searchCalls: Int = 0
        val lookupBranches = mutableListOf<String>()

        override fun branches(): ProviderBranchResult =
            ProviderBranchResult.Available(
                listOf(
                    ProviderBranch(
                        branchId = BranchId("205"),
                        name = "Nowy Sącz",
                        address = "33-300 Tarnowska 149",
                    ),
                    ProviderBranch(
                        branchId = BranchId("310"),
                        name = "Tarnów",
                    ),
                    ProviderBranch(
                        branchId = BranchId("311"),
                        name = "Tarnów Centrum",
                    ),
                    ProviderBranch(
                        branchId = BranchId("312"),
                        name = "Tarnów Centrum",
                    ),
                ),
            )

        override fun search(
            query: String,
            maxResults: Int,
        ): ProviderSearchResult {
            searchCalls += 1
            return ProviderSearchResult.Candidates(
                items = listOf(
                    ProviderProductCandidate(
                        ref = ProductRef(
                            providerId = providerId,
                            productId = "580",
                        ),
                        name = "Wyłącznik nadprądowy B16",
                        articleNumber = "MBN116E/HAG",
                    ),
                ),
                reportedTotalCount = null,
            )
        }

        override fun lookup(
            ref: ProductRef,
            branchId: BranchId,
        ): ProviderLookupResult {
            lookupBranches += branchId.value
            return ProviderLookupResult.Found(
                ProviderProduct(
                    ref = ref,
                    branchId = branchId,
                    name = "Wyłącznik nadprądowy B16",
                    stock = 140,
                    grossPrice = BigDecimal("14.55"),
                    priceScope = ProviderPriceScope.ONLINE,
                    productUrl =
                        "https://kwant.net.pl/produkt/test-580",
                    ean = "3250614312762",
                    articleNumber = "MBN116E/HAG",
                    brand = "Hager",
                    technicalFacts = technicalFacts,
                ),
            )
        }
    }
}
