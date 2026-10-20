package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.*
import pl.lukaszpeciak.towarownik.product.provider.*

/** Current production resolver, authority and executor; all stock is synthetic. */
class ObiLocationScopeR1Test {
    private val directory = OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }
    private val product = VerifiedProductSnapshot(obik = "3496072", name = "Synthetic", stock = 999,
        grossPrice = null, productUrl = "https://offline.invalid/product", verifiedAt = 1, storeNumber = "075")
    private val history = listOf(
        AdvisorLocationHistoryMessage("USER", "Sprawdź stan produktu 3496072 w OBI 003"),
        AdvisorLocationHistoryMessage("ASSISTANT", "Synthetic verified product", listOf(product)),
    )
    private inner class Fixture {
        val exact = mutableListOf<String>()
        val inventory = mutableListOf<List<String>>()
        private val adapter = object : ProductLocationsAdapter {
            override val providerId = OBI_PROVIDER_ID
            override suspend fun read(ref: ProductRef, requested: List<BranchId>, trustedProduct: ProviderProduct?): ProductLocationsResult {
                assertEquals("3496072", ref.productId)
                inventory += requested.map { it.value }
                return ProductLocationsResult.Available(ref,
                    requested.mapIndexed { index, id -> LocationStock(directory.single { it.branchId == id }, if (index == 0) 0 else 7) },
                    LocationCoverage(LocationCoverageKind.REQUESTED_SUBSET, requested, requested, 1), 2000)
            }
        }
        val tool = AdvisorLocationsTool(ProductLocationsService(listOf(adapter)),
            obiBranches = { ProviderBranchResult.Available(directory) },
            providers = exactLookupProviders { ref, branch ->
                assertEquals(ProductRef(OBI_PROVIDER_ID, "3496072"), ref)
                exact += branch.value
                exactLookupFound(ref, branch, 0)
            })
        fun authority(text: String, hints: List<String>, historical: Boolean) = AdvisorInventoryAuthorizer.resolve(
            AdvisorLocationArguments("obi-pl", "3496072", hints), "obi-pl", "075", text,
            if (historical) emptyList() else listOf(product), if (historical) listOf(product) else emptyList(),
            directory, if (historical) history else emptyList(),
        )
        suspend fun read(text: String, expected: Set<String>, hints: List<String> = listOf("075", "072", "999"), historical: Boolean = false) {
            val raw = BranchResolver.resolveObiMarketScope(text, directory, BranchId("075"))
            val a = authority(text, hints, historical)
            println("R1_SCOPE text=$text resolver=${raw.javaClass.simpleName} cityWide=${(raw as? BranchResolution.Ambiguous)?.isCityWideScope} authority=${a.javaClass.simpleName}")
            assertTrue("$text: expected inventory, got ${a.javaClass.simpleName}", a is AdvisorInventoryAuthorization.Inventory)
            a as AdvisorInventoryAuthorization.Inventory
            assertEquals(text, expected, a.branches.map { it.value }.toSet())
            assertFalse(a.fullNetwork)
            val evidence = tool.execute(a)
            assertEquals(text, "verified", evidence.status)
            assertNull(evidence.reason)
            assertEquals(expected, evidence.checkedIds.toSet())
            assertEquals(expected, evidence.authorizedBranchIds.toSet())
            assertEquals(expected, evidence.locations.map { it.branchId }.toSet())
            assertEquals(expected, (exact + inventory.flatten()).toSet())
            assertFalse(evidence.locations.any { it.stock == 999 })
            if (expected.size == 1) {
                assertEquals(listOf(expected.single()), exact)
                assertTrue(inventory.isEmpty())
                assertEquals(0, evidence.locations.single().stock)
            } else {
                assertTrue(exact.isEmpty())
                assertEquals(1, inventory.size)
            }
        }
        suspend fun refuse(text: String) {
            val result = tool.execute(AdvisorLocationArguments("obi-pl", "3496072", listOf("003")),
                "obi-pl", "075", text, listOf(product), emptyList())
            println("R1_NEGATIVE text=$text status=${result.status} reason=${result.reason} exact=$exact inventory=$inventory")
            assertEquals(text, "rejected", result.status)
            assertTrue(text, exact.isEmpty())
            assertTrue(text, inventory.isEmpty())
        }
    }

    @Test fun streetBeforeCityWithCourtesy() = runBlocking {
        Fixture().read("Sprawdź stan produktu 3496072 na Wielickiej w Krakowie proszę", setOf("003"))
    }
    @Test fun streetBeforeCityWithSeparateStockAction() = runBlocking {
        Fixture().read("Sprawdź stan produktu 3496072 na Wielickiej w Krakowie i pokaż aktualny stan magazynowy", setOf("003"))
    }
    @Test fun cityBeforeStreet() = runBlocking {
        Fixture().read("Sprawdź stan produktu 3496072 w Krakowie na Wielickiej", setOf("003"))
    }
    @Test fun matchingMarketIdBeforeStreetCityRemainsValid() = runBlocking {
        Fixture().read("Sprawdź stan produktu 3496072 w OBI 003 na Wielickiej w Krakowie", setOf("003"))
    }
    @Test fun streetBeforeCityShortHistoricalFollowUp() = runBlocking {
        Fixture().read("a na Wielickiej w Krakowie?", setOf("003"), historical = true)
    }
    @Test fun conflictingStreetCityCannotReadWithOrWithoutSuffix() = runBlocking {
        for (city in listOf("Warszawie", "Gdańsku")) for (tail in listOf("", " proszę", " i pokaż aktualny stan magazynowy")) {
            Fixture().refuse("Sprawdź stan produktu 3496072 na Wielickiej w $city$tail")
        }
    }
    @Test fun cityMarketConflictsCannotRead() = runBlocking {
        for (place in listOf("w Krakowie market OBI 075", "w OBI 075 na Wielickiej w Krakowie", "na Wielickiej w Krakowie OBI 075")) {
            Fixture().refuse("Sprawdź stan produktu 3496072 $place")
        }
    }
    @Test fun unknownStreetOrLocalityCannotRead() = runBlocking {
        for (place in listOf("na Nieznanej w Krakowie", "na Wielickiej w Atlantydzie", "w Krakowie na Nieznanej",
            "na Wielickiej w Krakowie i w Atlantydzie", "na Wielickiej w Krakowie i pokaż aktualny stan magazynowy w Atlantydzie")) {
            Fixture().refuse("Sprawdź stan produktu 3496072 $place")
        }
    }
    @Test fun arbitraryTrailingTextCannotBecomeCourtesyOrStockAction() = runBlocking {
        Fixture().refuse("Sprawdź stan produktu 3496072 na Wielickiej w Krakowie banan")
    }
    @Test fun explicitMarketSubsetSurvivesClassificationAndModelHints() = runBlocking {
        // The resolver's city-wide flag must not turn two named markets into all Kraków markets.
        for (hints in listOf(emptyList(), listOf("075"), listOf("003", "019", "059", "072", "999"))) {
            Fixture().read("Sprawdź stan produktu 3496072 w OBI 003 i OBI 019", setOf("003", "019"), hints)
        }
    }
    @Test fun cityOnlyKrakowReadsExactlyItsFourMarkets() = runBlocking {
        Fixture().read("Sprawdź stan produktu 3496072 w Krakowie", setOf("003", "019", "059", "072"))
    }
    @Test fun explicitSingleMarketKeepsExactLookup() = runBlocking {
        Fixture().read("Sprawdź stan produktu 3496072 w OBI 003", setOf("003"))
    }
    @Test fun canonicalCityStreetKeepsExactLookup() = runBlocking {
        Fixture().read("Kraków Wielicka", setOf("003"), historical = true)
    }

    @Test fun realDeviceExplicitMarketNumberAndAddressMustVerifyExact003() = runBlocking {
        // Real-device F2 / F3; never replace these USER strings with easier synonyms.
        for (input in listOf(
            "Sprawdź stan produktu OBIK 3496072 w markecie OBI nr 003 (Kraków, ul. Wielicka).",
            "Sprawdź stan produktu OBIK 3496072 w markecie OBI nr 003",
        )) Fixture().read(input, setOf("003"))
    }

    @Test fun realDeviceContextualSameProductFollowUpMustVerify003() = runBlocking {
        // Real-device F1. Identity belongs to the prior verified USER conversation.
        Fixture().read(
            "A ile tego samego produktu jest w OBI Kraków przy ulicy Wielickiej?",
            setOf("003"), historical = true,
        )
    }


    @Test fun realDeviceLocationNegativeControlsStayFailClosed() = runBlocking {
        for (input in listOf(
            "Sprawdź stan produktu OBIK 3496072 w OBI 075 na Wielickiej.",
            "Sprawdź stan produktu OBIK 3496072 w markecie OBI nr 075 (Kraków, ul. Wielicka).",
            "Sprawdź stan produktu OBIK 3496072 w nieznanym mieście.",
            "Sprawdź stan produktu OBIK 3496072 w Krakowie przy ulicy Nieznanej.",
        )) Fixture().refuse(input)
        // An unqualified city names every canonical Kraków market, not just 003.
        Fixture().read("Sprawdź stan produktu OBIK 3496072 w Krakowie",
            setOf("003", "019", "059", "072"))
        Fixture().read("Sprawdź stan produktu OBIK 3496072 w Krakowie na Bora-Komorowskiego?",
            setOf("019"))
        Fixture().read("Sprawdź stan produktu OBIK 3496072 w Krakowie na Wielickiej.",
            setOf("003"))
    }

}
