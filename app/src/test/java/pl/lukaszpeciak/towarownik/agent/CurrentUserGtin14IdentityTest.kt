package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.*
import pl.lukaszpeciak.towarownik.product.provider.*

/** Production admission/execution, with synthetic provider facts and no live HTTP. */
class CurrentUserGtin14IdentityTest {
    private val gtin = "05901234123457"
    private val witness = "Sprawdź stan EAN $gtin w OBI 003"
    private val obi = OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }
    private val kwant = listOf(ProviderBranch(BranchId("205"), "Nowy Sącz"), ProviderBranch(BranchId("204"), "Tarnów"))
    private fun card(provider: String = "obi-pl", id: String = "3496072") = VerifiedProductSnapshot(
        obik = id, productId = id, providerId = provider, name = "Synthetic product",
        stock = 999, grossPrice = null, productUrl = "https://offline.invalid/product", verifiedAt = 1,
        storeNumber = if (provider == "obi-pl") "075" else "205",
        branchId = if (provider == "obi-pl") "075" else "205",
        articleNumber = if (provider == "kwant-pl") "MBN116E/HAG" else null,
    )
    private fun authority(text: String, current: List<VerifiedProductSnapshot> = listOf(card()),
        historical: List<VerifiedProductSnapshot> = emptyList(), history: List<AdvisorLocationHistoryMessage> = emptyList(),
        provider: String = "obi-pl", modelProvider: String = provider, modelId: String = "3496072",
    ) = AdvisorInventoryAuthorizer.resolve(
        AdvisorLocationArguments(modelProvider, modelId, listOf("999")), provider,
        if (provider == "obi-pl") "075" else "205", text, current, historical,
        if (provider == "obi-pl") obi else kwant, history,
    )
    private fun assertRefused(a: AdvisorInventoryAuthorization) {
        assertTrue("Expected refusal/clarification, got ${a.javaClass.simpleName}",
            a is AdvisorInventoryAuthorization.Denied || a is AdvisorInventoryAuthorization.ClarificationRequired)
    }

    @Test fun currentGtin14CannotAuthorizeUnrelatedVerifiedObik() {
        val a = authority(witness)
        val admitted = a as? AdvisorInventoryAuthorization.Inventory
        println("GTIN14_WITNESS evidence=${advisorUserProductEvidence(witness)} authority=${a.javaClass.simpleName} product=${admitted?.product?.effectiveProductId} branches=${admitted?.branches}")
        assertRefused(a)
    }

    @Test fun allExplicitGtin14TokensRetainLeadingZeroes() {
        for (text in listOf(witness, "Sprawdź stan $gtin w OBI 003", "EAN: $gtin", "produkt $gtin")) {
            assertEquals(text, setOf(gtin), advisorUserProductEvidence(text))
        }
        assertEquals(setOf(gtin, "00012345678905"), advisorUserProductEvidence("EAN $gtin i EAN 00012345678905"))
    }

    @Test fun longerNumbersAreNeverTruncatedIntoGtin14Evidence() {
        for (id in listOf("1$gtin", "${gtin}0")) {
            assertTrue(advisorUserProductEvidence("EAN $id").isEmpty())
        }
    }

    @Test fun unknownGtin14CannotHideBesideMatchingObik() {
        for (text in listOf("Sprawdź stan OBIK 3496072 EAN $gtin w OBI 003",
            "Sprawdź stan EAN $gtin OBIK 3496072 w OBI 003")) assertRefused(authority(text))
    }

    @Test fun leadingZeroCannotBeDroppedToMatchAnotherIdentity() {
        assertRefused(authority(witness, current = listOf(card(id = gtin.drop(1))), modelId = gtin.drop(1)))
    }

    @Test fun modelGtinClaimAndProductNameAreNotAliasProof() {
        assertRefused(authority(witness, modelId = gtin))
        assertRefused(authority(witness, current = listOf(card().copy(name = "EAN $gtin"))))
    }

    @Test fun staleHistoricalProductCannotSatisfyCurrentGtin14() {
        val old = card()
        val history = listOf(AdvisorLocationHistoryMessage("USER", "Sprawdź stan OBIK 3496072 w OBI 075"),
            AdvisorLocationHistoryMessage("ASSISTANT", "Synthetic historical card", listOf(old)))
        assertRefused(authority(witness, current = emptyList(), historical = listOf(old), history = history))
        assertRefused(authority(witness, historical = listOf(old), history = history))
    }

    @Test fun unknownHistoricalGtin14CannotReviveEarlierObikOnLocationFollowUp() {
        val old = card()
        val history = listOf(AdvisorLocationHistoryMessage("USER", "Sprawdź stan OBIK 3496072 w OBI 075"),
            AdvisorLocationHistoryMessage("ASSISTANT", "Synthetic historical card", listOf(old)),
            AdvisorLocationHistoryMessage("USER", witness), AdvisorLocationHistoryMessage("ASSISTANT", "Brak potwierdzenia"))
        assertRefused(authority("a w OBI 003?", current = emptyList(), historical = listOf(old), history = history))
    }

    @Test fun obik7AndEan8_12_13RemainIdentityEvidence() {
        for (id in listOf("3496072", "59012341", "012345678905", "5901234123457")) {
            assertEquals(setOf(id), advisorUserProductEvidence("Sprawdź stan EAN $id w OBI 003"))
            if (id != "3496072") assertRefused(authority("Sprawdź stan EAN $id w OBI 003"))
        }
        val a = authority("Sprawdź stan OBIK 3496072 w OBI 003") as AdvisorInventoryAuthorization.Inventory
        assertEquals(setOf("3496072"), a.currentUserProductEvidence)
        assertEquals(listOf(BranchId("003")), a.branches)
    }

    @Test fun measurementsAndBranchNumbersRemainNonIdentities() {
        for (text in listOf("1000 W", "produkt 1000 W", "25 m", "5 szt.", "OBI 003", "OBI 052", "OBI 075", "oddział 205")) {
            assertTrue(text, advisorUserProductEvidence(text).isEmpty())
        }
        assertEquals(setOf("7035"), advisorUserProductEvidence("Sprawdź stan produktu 7035 1000 W w oddziale 205"))
    }

    @Test fun wrongProviderStillRefuses() {
        assertRefused(authority(witness, modelProvider = "kwant-pl"))
        assertRefused(authority(witness, current = listOf(card("kwant-pl"))))
    }

    @Test fun kwantArticleAndShortIdLookupsRemainAuthorized() {
        val snapshot = card("kwant-pl", "580")
        for (text in listOf("Sprawdź stan MBN116E w Tarnowie", "Sprawdź stan produktu 580 w Tarnowie")) {
            val a = authority(text, listOf(snapshot), provider = "kwant-pl", modelId = "580") as AdvisorInventoryAuthorization.Inventory
            assertEquals("580", a.product.effectiveProductId)
            assertEquals(listOf(BranchId("204")), a.branches)
        }
        assertRefused(authority("Sprawdź stan EAN $gtin w Tarnowie", listOf(snapshot), provider = "kwant-pl", modelId = "580"))
    }

    @Test fun directToolRefusesBeforeAnyExactOrInventoryRead() = runBlocking {
        val reads = mutableListOf<String>()
        val provider = object : ProductProvider {
            override val providerId = OBI_PROVIDER_ID
            override fun branches() = ProviderBranchResult.Available(obi)
            override fun search(query: String, maxResults: Int): ProviderSearchResult = error("Unexpected search")
            override fun lookup(ref: ProductRef, branchId: BranchId): ProviderLookupResult {
                reads += "exact:${ref.productId}/${branchId.value}"
                return ProviderLookupResult.Found(ProviderProduct(ref, branchId, "Synthetic", 17,
                    grossPrice = null, priceScope = null, productUrl = "https://offline.invalid/product", ean = null))
            }
        }
        val adapter = object : ProductLocationsAdapter {
            override val providerId = OBI_PROVIDER_ID
            override suspend fun read(ref: ProductRef, requested: List<BranchId>, trustedProduct: ProviderProduct?): ProductLocationsResult {
                reads += "inventory:${ref.productId}"
                return ProductLocationsResult.Unavailable(LocationFailure.TRANSPORT)
            }
        }
        val tool = AdvisorLocationsTool(ProductLocationsService(listOf(adapter)),
            obiBranches = provider::branches, providers = ProductProviderRegistry(listOf(provider)))
        val evidence = tool.execute(AdvisorLocationArguments("obi-pl", "3496072", listOf("003")),
            "obi-pl", "075", witness, listOf(card()), emptyList())
        println("GTIN14_EXECUTION status=${evidence.status} reason=${evidence.reason} reads=$reads")
        assertTrue("GTIN-14 must stop all product reads: $reads", reads.isEmpty())
        assertEquals("rejected", evidence.status)
        assertEquals("untrusted_product", evidence.reason)
    }
}
