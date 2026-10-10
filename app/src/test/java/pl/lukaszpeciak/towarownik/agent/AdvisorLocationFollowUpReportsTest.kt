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
        val followUp = resolveAdvisorLocationFollowUp(message, history, cards)
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
        val initial = listOf(
            assistant(first, second),
            user("a jaki jest stan tego gniazda 16 na 4 w oddziale w Zamościu?"),
            assistant(),
        )
        val both = resolveAdvisorLocationFollowUp("obu", initial, listOf(first, second))
        assertEquals(setOf("7035", "7027"), both.bothProductIds)
        val afterBoth = initial + user("obu") + assistant()
        val city = resolveAdvisorLocationFollowUp("Zamość", afterBoth, listOf(first, second))
        assertEquals(setOf("7035", "7027"), city.bothProductIds)
        assertEquals("Sprawdź stan produktu w Zamość", city.authorizedText)
        val afterCity = afterBoth + user("Zamość") + assistant()
        val explicit = resolveAdvisorLocationFollowUp(
            "sprawdź stan obu gniazd w Zamościu", afterCity, listOf(first, second),
        )
        assertEquals(setOf("7035", "7027"), explicit.bothProductIds)
        val tarnow = resolveAdvisorLocationFollowUp(
            "a w oddziale Tarnów?",
            afterCity + user("sprawdź stan obu gniazd w Zamościu") + assistant(),
            listOf(first, second),
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
}
