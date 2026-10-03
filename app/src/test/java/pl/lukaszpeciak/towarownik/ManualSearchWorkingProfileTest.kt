package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProductProvider
import pl.lukaszpeciak.towarownik.product.provider.ProductProviderFailure
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

class ManualSearchWorkingProfileTest {
    @Test
    fun `manual search resolves selected provider and branch without fallback`() = runBlocking {
        val obi = FakeProvider(OBI_PROVIDER_ID, "075", "obi-product")
        val kwant = FakeProvider(KWANT_PROVIDER_ID, "205", "kwant-product")
        val controller = ManualSearchController(
            providers = ProductProviderRegistry(listOf(obi, kwant)),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit(
            input = "MBN116E",
            workingProfile = WorkingProfile(
                providerId = KWANT_PROVIDER_ID,
                branchId = BranchId("205"),
            ),
            branchLabel = "Nowy Sącz",
        ) { states += it }

        assertEquals(0, obi.searchCalls)
        assertEquals(0, obi.lookupCalls)
        assertEquals(1, kwant.searchCalls)
        assertEquals(1, kwant.lookupCalls)
        val result = states.last() as ManualSearchUiState.SearchResults
        val verified = (
            result.items.single().enrichment as
                ManualResultEnrichment.Verified
            ).product
        assertEquals(KWANT_PROVIDER_ID.value, verified.providerId)
        assertEquals("205", verified.branchId)
        assertEquals("ART-580", verified.articleNumber)
    }

    @Test
    fun `unknown provider fails instead of falling back to OBI`() = runBlocking {
        val obi = FakeProvider(OBI_PROVIDER_ID, "075", "obi-product")
        val controller = ManualSearchController(
            providers = ProductProviderRegistry(listOf(obi)),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit(
            input = "synthetic",
            workingProfile = WorkingProfile(
                providerId = ProviderId("unknown-provider"),
                branchId = BranchId("075"),
            ),
        ) { states += it }

        assertEquals(0, obi.searchCalls)
        assertEquals(0, obi.lookupCalls)
        assertEquals(
            SearchUiError.INVALID_INPUT,
            (states.last() as ManualSearchUiState.Error).error,
        )
    }

    @Test
    fun `invalid selected branch is surfaced and never substituted`() = runBlocking {
        val kwant = FakeProvider(KWANT_PROVIDER_ID, "205", "kwant-product")
        val controller = ManualSearchController(
            providers = ProductProviderRegistry(listOf(kwant)),
            ioDispatcher = Dispatchers.Unconfined,
        )
        val states = mutableListOf<ManualSearchUiState>()

        controller.submit(
            input = "MBN116E",
            workingProfile = WorkingProfile(
                providerId = KWANT_PROVIDER_ID,
                branchId = BranchId("999"),
            ),
        ) { states += it }

        val results = states.last() as ManualSearchUiState.SearchResults
        assertTrue(
            results.items.single().enrichment is
                ManualResultEnrichment.Unavailable,
        )
        assertEquals(listOf("999"), kwant.lookupBranches)
    }

    private class FakeProvider(
        override val providerId: ProviderId,
        private val validBranch: String,
        private val productId: String,
    ) : ProductProvider {
        var searchCalls = 0
        var lookupCalls = 0
        val lookupBranches = mutableListOf<String>()

        override fun branches(): ProviderBranchResult =
            ProviderBranchResult.Available(
                listOf(
                    ProviderBranch(
                        branchId = BranchId(validBranch),
                        name = if (providerId == KWANT_PROVIDER_ID) {
                            "Nowy Sącz"
                        } else {
                            validBranch
                        },
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
                        ref = ProductRef(providerId, productId),
                        name = "Synthetic",
                        articleNumber =
                            if (providerId == KWANT_PROVIDER_ID) {
                                "ART-580"
                            } else {
                                null
                            },
                    ),
                ),
                reportedTotalCount =
                    if (providerId == OBI_PROVIDER_ID) 1 else null,
            )
        }

        override fun lookup(
            ref: ProductRef,
            branchId: BranchId,
        ): ProviderLookupResult {
            lookupCalls += 1
            lookupBranches += branchId.value
            if (ref.providerId != providerId) {
                return ProviderLookupResult.WrongProvider(ref)
            }
            if (branchId.value != validBranch) {
                return ProviderLookupResult.InvalidBranch(branchId)
            }
            return ProviderLookupResult.Found(
                ProviderProduct(
                    ref = ref,
                    branchId = branchId,
                    name = "Synthetic",
                    stock = 4,
                    grossPrice = BigDecimal("14.55"),
                    priceScope =
                        if (providerId == KWANT_PROVIDER_ID) {
                            ProviderPriceScope.ONLINE
                        } else {
                            ProviderPriceScope.BRANCH
                        },
                    productUrl = "https://example.invalid/product",
                    ean = null,
                    articleNumber =
                        if (providerId == KWANT_PROVIDER_ID) {
                            "ART-580"
                        } else {
                            null
                        },
                ),
            )
        }
    }
}
