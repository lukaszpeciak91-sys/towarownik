package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.*

/** Ordered synthetic USER/ASSISTANT history; inventory fetcher never touches OBI. */
class AdvisorLocationFollowUpReportsTest {
    private fun snapshot(id: String, provider: String = "obi-pl", article: String? = null) =
        VerifiedProductSnapshot(
            obik = id, productId = id, providerId = provider,
            articleNumber = article, name = "Fixture $id", stock = 2,
            grossPrice = null, productUrl = "https://www.obi.pl/p/$id",
            verifiedAt = 100L, storeNumber = "003",
            branchId = if (provider == "obi-pl") "003" else "205",
        )

    private fun user(text: String) = AdvisorLocationHistoryMessage("USER", text)
    private fun assistant(vararg products: VerifiedProductSnapshot) =
        AdvisorLocationHistoryMessage("ASSISTANT", "Verified cards", products.toList())
    private val portable = snapshot("6117543")
    private val fixed = snapshot("5524079")
    private val obiHistory = listOf(
        assistant(portable, fixed),
        user("Sprawdź OBIK 6117543 w market OBI 003"),
        assistant(portable),
    )

    private class Inventory {
        val http = mutableListOf<List<String>>()
        val reads = mutableListOf<List<String>>()
        private val fetcher = LocationsHttpFetcher { url: HttpUrl ->
            val ids = url.queryParameter("storeIds")!!.split(",")
            http += ids
            LocationsHttpResult.Success(ids.joinToString(prefix = "[", postfix = "]") {
                """{"storeId":"$it","availableQuantity":2}"""
            })
        }
        private val obi = ObiProductLocationsAdapter(fetcher = fetcher, now = { 1234L })
        private val adapter = object : ProductLocationsAdapter {
            override val providerId = OBI_PROVIDER_ID
            override suspend fun read(
                ref: ProductRef, requested: List<BranchId>, trustedProduct: ProviderProduct?,
            ): ProductLocationsResult {
                reads += requested.map { it.value }
                return obi.read(ref, requested, trustedProduct)
            }
        }
        val tool = AdvisorLocationsTool(
            locationsService = ProductLocationsService(listOf(adapter)),
        )
    }

    private suspend fun lookup(
        message: String, history: List<AdvisorLocationHistoryMessage>,
        id: String = "6117543",
    ): Pair<AdvisorLocationEvidence, Inventory> {
        val inventory = Inventory()
        val cards = history.asReversed().filter { it.role == "ASSISTANT" }.take(3)
            .flatMap { it.products }
        val branches = (ObiProductProvider().branches() as ProviderBranchResult.Available).branches
        val followUp = resolveAdvisorLocationFollowUp(message, history, cards, branches)
        val result = inventory.tool.execute(
            AdvisorLocationArguments("obi-pl", id, emptyList()),
            "obi-pl", "075", followUp.authorizedText,
            emptyList(), followUp.historicalProducts,
        )
        return result to inventory
    }

    @Test fun reportA_canonical052AndSelectedPortableProduct() = runBlocking {
        val (result, inventory) = lookup("a w Miejscu Piastowym?", obiHistory)
        assertEquals("verified", result.status)
        assertEquals("6117543", result.productId)
        assertEquals(listOf("052"), result.checkedIds)
        assertEquals(listOf(listOf("052")), inventory.http)
    }

    @Test fun reportA_conflicting054RequiresClarificationAndZeroHttp() = runBlocking {
        val h = obiHistory + user("a w Miejscu Piastowym?") + assistant()
        val (result, inventory) = lookup(
            "no jak market w Miejscu Piastowym 054 bodajże", h,
        )
        assertEquals("rejected", result.status)
        assertTrue(result.reason.orEmpty().startsWith("location_conflict_054_vs_052"))
        assertTrue(inventory.http.isEmpty())
    }

    @Test fun reportA_relativeBroadRequestChecks61AndSevenHttp() = runBlocking {
        val h = obiHistory + user("a w Miejscu Piastowym?") + assistant() +
            user("no jak market w Miejscu Piastowym 054 bodajże") + assistant()
        val (result, inventory) = lookup("ok podaj markety w których jest dostępny", h)
        assertEquals("verified", result.status)
        assertEquals("6117543", result.productId)
        assertEquals("all_other_locations", result.coverage)
        assertEquals(61, result.checkedIds.size)
        assertFalse(result.checkedIds.contains("075"))
        assertEquals(listOf(20, 20, 20, 1), inventory.reads.map { it.size })
        assertEquals(7, inventory.http.size)
    }

    @Test fun reportA_modelCannotSwitchToOldFixedSocket() = runBlocking {
        val (result, inventory) = lookup("a w Miejscu Piastowym?", obiHistory, "5524079")
        assertEquals("rejected", result.status)
        assertEquals("untrusted_product", result.reason)
        assertTrue(inventory.http.isEmpty())
    }

    @Test fun reportA_genuinelyAmbiguousTwoCardsFailClosed() = runBlocking {
        val history = listOf(
            assistant(portable, fixed),
            user("Sprawdź stany tych dwóch produktów w OBI 003"),
            assistant(),
        )
        val (result, inventory) = lookup("a w Miejscu Piastowym?", history)
        assertEquals("rejected", result.status)
        assertEquals("ambiguous_product", result.reason)
        assertTrue(inventory.http.isEmpty())
    }

    @Test fun reportB_bothAndZamoscAndTarnowRetainUserAuthorizations() {
        val first = snapshot("7035", "kwant-pl", "921871")
        val second = snapshot("7027", "kwant-pl", "921861")
        val kwantBranches = listOf(
            ProviderBranch(BranchId("205"), "Nowy Sącz"),
            ProviderBranch(BranchId("128"), "Zamość"),
            ProviderBranch(BranchId("216"), "Tarnów"),
        )
        val initial = listOf(
            assistant(first, second),
            user("a jaki jest stan tego gniazda 16 na 4 w oddziale w Zamościu?"),
            assistant(),
        )
        val both = resolveAdvisorLocationFollowUp("obu", initial, listOf(first, second), kwantBranches)
        assertEquals(setOf("7035", "7027"), both.bothProductIds)
        val afterBoth = initial + user("obu") + assistant()
        val city = resolveAdvisorLocationFollowUp("Zamość", afterBoth, listOf(first, second), kwantBranches)
        assertEquals(setOf("7035", "7027"), city.bothProductIds)
        assertEquals("Sprawdź stan produktu w Zamość", city.authorizedText)
        val afterCity = afterBoth + user("Zamość") + assistant()
        val explicit = resolveAdvisorLocationFollowUp(
            "sprawdź stan obu gniazd w Zamościu", afterCity, listOf(first, second), kwantBranches,
        )
        assertEquals(setOf("7035", "7027"), explicit.bothProductIds)
        val tarnow = resolveAdvisorLocationFollowUp(
            "a w oddziale Tarnów?",
            afterCity + user("sprawdź stan obu gniazd w Zamościu") + assistant(),
            listOf(first, second),
            kwantBranches,
        )
        assertEquals(setOf("7035", "7027"), tarnow.bothProductIds)
        assertTrue(tarnow.authorizedText.startsWith("Sprawdź stan produktu:"))
    }

    @Test fun noPriorInventoryOrOrdinaryAdviceDoesNotAuthorize() = runBlocking {
        for ((message, history) in listOf(
            "Miejsce Piastowe" to listOf(assistant(portable)),
            "a w Miejscu Piastowym?" to listOf(
                assistant(portable), user("Jak zamontować gniazdo w Krakowie?"), assistant(),
            ),
            "Jak zamontować ten produkt w Krakowie?" to obiHistory,
            "Sprawdź w Tarnowie" to obiHistory,
            "Sprawdź market OBI 054" to obiHistory,
        )) {
            val (result, inventory) = lookup(message, history)
            assertEquals(message, "rejected", result.status)
            assertTrue(message, inventory.http.isEmpty())
        }
    }

    @Test fun unrelatedShortPriceTurnBreaksPendingInventoryPermission() = runBlocking {
        val unrelated = obiHistory + user("pokaż ceny") + assistant()
        val (rejected, inventory) = lookup("a w Krakowie?", unrelated)
        assertEquals("rejected", rejected.status)
        assertEquals("location_intent_required", rejected.reason)
        assertTrue(inventory.reads.isEmpty())
        assertTrue(inventory.http.isEmpty())

        // An ASSISTANT invitation to check a different store does not grant permission.
        val suggestion = listOf(
            assistant(portable),
            AdvisorLocationHistoryMessage(
                "ASSISTANT", "Mogę sprawdzić dostępność w Krakowie",
            ),
        )
        val (unsolicited, noHttp) = lookup("a w Krakowie?", suggestion)
        assertEquals("location_intent_required", unsolicited.reason)
        assertTrue(noHttp.http.isEmpty())
    }

    @Test fun currentExactVerifiedProductOverridesOlderUserSelection() = runBlocking {
        val (actual, inventory) = lookup(
            "Sprawdź stan produktu OBIK 5524079 w market OBI 003",
            obiHistory,
            id = "5524079",
        )
        assertEquals("verified", actual.status)
        assertEquals("5524079", actual.productId)
        assertEquals(listOf("003"), actual.checkedIds)
        assertEquals(listOf(listOf("003")), inventory.http)
        val cards = listOf(portable, fixed)
        val context = resolveAdvisorLocationFollowUp(
            "Sprawdź stan produktu OBIK 5524079 w market OBI 003",
            obiHistory, cards,
            (ObiProductProvider().branches() as ProviderBranchResult.Available).branches,
        )
        assertEquals(listOf("5524079"), context.historicalProducts.map { it.effectiveProductId })
    }

    @Test fun currentUnknownProductAndTwoExplicitProductsCannotInheritOldSelection() = runBlocking {
        for ((message, expected) in listOf(
            "Sprawdź stan produktu OBIK 9999999 w market OBI 003" to "untrusted_product",
            "Sprawdź stan produktów OBIK 6117543 i 5524079 w market OBI 003" to "ambiguous_product",
        )) {
            val (actual, inventory) = lookup(message, obiHistory, id = "6117543")
            assertEquals(message, "rejected", actual.status)
            assertEquals(message, expected, actual.reason)
            assertTrue(message, inventory.http.isEmpty())
        }
    }



    @Test fun priceAndAdviceLocationQuestionsCannotBorrowStockAuthority() = runBlocking {
        for (message in listOf(
            "a w Krakowie ile kosztuje?",
            "a w Krakowie cena?",
            "a w Krakowie jak zamontować?",
            "a w Krakowie kompatybilność?",
        )) {
            val (result, inventory) = lookup(message, obiHistory)
            assertEquals(message, "rejected", result.status)
            assertEquals(message, "location_intent_required", result.reason)
            assertTrue(message, inventory.reads.isEmpty())
            assertTrue(message, inventory.http.isEmpty())
            val aliases = (ObiProductProvider().branches() as ProviderBranchResult.Available).branches
            val context = resolveAdvisorLocationFollowUp(
                message, obiHistory, listOf(portable, fixed), aliases,
            )
            assertEquals(message, message, context.authorizedText)
        }
        val (allowed, inventory) = lookup("a w Krakowie?", obiHistory)
        assertEquals("verified", allowed.status)
        assertTrue(allowed.checkedIds.isNotEmpty())
        assertTrue(inventory.http.isNotEmpty())
    }

    @Test fun shorthandMiejscePiastowe054MustNotRead052() = runBlocking {
        val (result, inventory) = lookup("a w Miejscu Piastowym 054?", obiHistory)
        assertEquals("rejected", result.status)
        assertEquals("location_conflict_054_vs_052", result.reason)
        assertTrue(inventory.reads.isEmpty())
        assertTrue(inventory.http.isEmpty())
    }

    /** Synthetic KWANT adapter: never enables the production extended contract. */
    private inner class ShortKwantFixture {
        val old = snapshot("7035", "kwant-pl", "921871")
        val newlyVerified = snapshot("7027", "kwant-pl", "921861")
        val adapterReads = mutableListOf<String>()
        private val adapter = object : ProductLocationsAdapter {
            override val providerId = KWANT_PROVIDER_ID
            override suspend fun read(
                ref: ProductRef,
                requested: List<BranchId>,
                trustedProduct: ProviderProduct?,
            ): ProductLocationsResult {
                adapterReads += ref.productId
                return ProductLocationsResult.Unavailable(
                    LocationFailure.UNVERIFIED_REQUEST_CONTRACT,
                )
            }
        }
        private val tool = AdvisorLocationsTool(
            locationsService = ProductLocationsService(listOf(adapter)),
        )
        suspend fun check(
            message: String,
            trusted: List<VerifiedProductSnapshot>,
            modelId: String,
        ): AdvisorLocationEvidence = tool.execute(
            arguments = AdvisorLocationArguments("kwant-pl", modelId, emptyList()),
            providerId = "kwant-pl", currentBranchId = "205",
            userText = message, currentVerified = emptyList(),
            historicalVerified = trusted,
        )
    }

    @Test fun shortKwantExplicitUnknownProductIdRejectsWithoutAdapter() = runBlocking {
        val f = ShortKwantFixture()
        val request = "Sprawdź stan produktu 7027 w oddziale Zamość"
        val result = f.check(request, listOf(f.old), "7035")
        assertEquals("rejected", result.status)
        assertEquals("untrusted_product", result.reason)
        assertTrue(f.adapterReads.isEmpty())
    }

    @Test fun shortKwantDirectAccentedCheckRejectsUnknownId() = runBlocking {
        val f = ShortKwantFixture()
        val unlabeled = f.check("Sprawdź 7027 w oddziale Zamość", listOf(f.old), "7035")
        assertEquals("rejected", unlabeled.status)
        assertEquals("untrusted_product", unlabeled.reason)
        assertTrue(f.adapterReads.isEmpty())
    }

    @Test fun shortKwantUnknownArticleRejectsWithoutAdapter() = runBlocking {
        val f = ShortKwantFixture()
        val article = f.check(
            "Sprawdź stan produktu o kodzie 921861 w oddziale Zamość",
            listOf(f.old), "7035",
        )
        assertEquals("rejected", article.status)
        assertEquals("untrusted_product", article.reason)
        assertTrue(f.adapterReads.isEmpty())
    }

    @Test fun shortKwantAfterNewVerificationReevaluatesProductTrust() = runBlocking {
        val f = ShortKwantFixture()
        val request = "Sprawdź stan produktu 7027 w oddziale Zamość"
        val after = f.check(request, listOf(f.old, f.newlyVerified), "7027")
        assertEquals("unavailable", after.status)
        assertEquals("unverified_request_contract", after.reason)
        assertEquals(listOf("7027"), f.adapterReads)
        val mismatch = f.check(request, listOf(f.old, f.newlyVerified), "7035")
        assertEquals("rejected", mismatch.status)
        assertEquals(1, f.adapterReads.size)
    }

    @Test fun shortKwantMeasurementsAreNotUntrustedProductIds() = runBlocking {
        val f = ShortKwantFixture()
        val dimensions = f.check(
            "Sprawdź stan produktu 7035 w oddziale Zamość, potrzebuję 1000 W, 25 m i 5 szt.",
            listOf(f.old), "7035",
        )
        assertEquals("unavailable", dimensions.status)
        assertEquals(listOf("7035"), f.adapterReads)
        val watts = f.check(
            "Sprawdź 1000 W w oddziale Zamość",
            listOf(f.old), "7035",
        )
        assertEquals("unavailable", watts.status)
        assertEquals(listOf("7035", "7035"), f.adapterReads)
    }


}
