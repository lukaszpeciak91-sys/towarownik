package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.OBI_STORES
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.*

/** Native Controller -> actual follow-up/identity/scope tool -> offline product-bound read. */
class AdvisorNaturalFollowUpRegressionTest {
    private val kwant = listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz"),
        ProviderBranch(BranchId("203"), "Bochnia", "Krakowska 10"),
        ProviderBranch(BranchId("204"), "Tarnów"),
        ProviderBranch(BranchId("141"), "Włocławek"),
        ProviderBranch(BranchId("128"), "Zamość"),
    )
    private val obi = OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }
    private fun card(provider: String, id: String = if (provider == "obi-pl") "7000001" else "90001") =
        VerifiedProductSnapshot(
            obik = id, productId = id, providerId = provider,
            branchId = if (provider == "obi-pl") "075" else "203",
            storeNumber = if (provider == "obi-pl") "075" else "203",
            name = "Synthetic", stock = 99, grossPrice = BigDecimal.ONE,
            productUrl = "https://offline.invalid/product", verifiedAt = 10,
            articleNumber = if (provider == "kwant-pl") "A9Z01440/SCH" else null,
        )
    private fun user(text: String) = AdvisorLocationHistoryMessage("USER", text)
    private fun history(provider: String, anchor: String? = null) = listOf(
        user(anchor ?: if (provider == "kwant-pl") "sprawdź stan A9Z01440/SCH w Bochni"
            else "sprawdź stan produktu 7000001 w OBI 075"),
        AdvisorLocationHistoryMessage("ASSISTANT", "Synthetic verified result", listOf(card(provider))),
    )
    private data class Observation(
        val evidence: AdvisorLocationEvidence,
        val reads: List<Pair<String, List<String>>>,
        val state: AdvisorUiState.Success,
    )
    private suspend fun run(
        input: String, provider: String = "kwant-pl",
        hints: List<String> = emptyList(),
        prior: List<AdvisorLocationHistoryMessage> = history(provider),
        cards: List<VerifiedProductSnapshot> = listOf(card(provider)),
        modelId: String = if (provider == "obi-pl") "7000001" else "90001",
        directoryUnavailable: Boolean = false,
    ): Observation {
        val selected = if (provider == "obi-pl") "075" else "205"
        val directory = if (provider == "obi-pl") obi else kwant
        val reads = mutableListOf<Pair<String, List<String>>>()
        val adapter = object : ProductLocationsAdapter {
            override val providerId = ProviderId(provider)
            override suspend fun read(ref: ProductRef, requested: List<BranchId>, trustedProduct: ProviderProduct?): ProductLocationsResult {
                assertEquals(modelId, ref.productId)
                reads += ref.productId to requested.map { it.value }
                return ProductLocationsResult.Available(
                    ref, requested.map { id -> LocationStock(directory.single { it.branchId == id }, 7) },
                    LocationCoverage(LocationCoverageKind.REQUESTED_SUBSET, requested, requested, 1), 2000,
                )
            }
        }
        val branches = { if (directoryUnavailable) ProviderBranchResult.Unavailable(ProductProviderFailure.NETWORK, "offline")
            else ProviderBranchResult.Available(directory) }
        val tool = AdvisorLocationsTool(ProductLocationsService(listOf(adapter)), branches, branches)
        var evidence: AdvisorLocationEvidence? = null
        val initial = AdvisorProxyCallResult.Success(AdvisorProxyResult.LocationToolRequest(
            "resp_locations", "call_locations", AdvisorLocationArguments(provider, modelId, hints),
        ))
        val controller = AdvisorController(
            isConfigured = { true }, startAgent = { _, _, _ -> initial }, messageAgent = { _, _, _, _ -> initial },
            continueAgent = { _, _, p, b, _, output ->
                assertEquals(provider, p); assertEquals(selected, b)
                evidence = (output as AdvisorToolContinuation.Locations).evidence
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                    "resp_final", "Synthetic model: location not authorized",
                    listOf(AdvisorProductRef(card(provider).effectiveBranchId, modelId, provider)),
                ))
            },
            executeObiTool = { error("No discovery fixture") }, executeProviderTool = { error("No discovery fixture") },
            executeLocationsTool = tool::execute, branchDirectory = { branches() },
        )
        val state = controller.runTurn(input, "resp_prior", selected, provider,
            historicalVerifiedProducts = cards, locationHistory = prior) { } as AdvisorUiState.Success
        val observed = requireNotNull(evidence)
        println("R2_SYNTHETIC provider=$provider input=$input status=${observed.status} reason=${observed.reason} checked=${observed.checkedIds} reads=$reads cards=${state.products.size} text=${state.text}")
        return Observation(observed, reads, state)
    }
    private fun assertRead(r: Observation, id: String) {
        assertEquals("verified", r.evidence.status)
        assertEquals(listOf(id), r.evidence.checkedIds)
        assertEquals(listOf(id), r.reads.flatMap { it.second })
        assertEquals(7, r.evidence.locations.single().stock)
        assertEquals(2000L, r.evidence.verifiedAtMillis)
        assertTrue(r.state.products.isEmpty()) // Old cards never become new current-turn cards.
        assertFalse(r.state.text.contains("99")) // Historical quantity never substitutes for a read.
    }
    @Test fun `plain KWANT w and we follow-ups use USER anchor and exact product`() = runBlocking {
        assertRead(run("w Bochni"), "203")
        assertRead(run("we Włocławku"), "141")
    }
    @Test fun `prefixed KWANT city follow-up remains valid`() = runBlocking {
        assertRead(run("a w Tarnowie?"), "204")
    }
    @Test fun `OBI plain and prefixed city plus street select unique market`() = runBlocking {
        for (text in listOf("w Krakowie na Wielickiej", "a w Krakowie na Wielickiej?")) {
            assertRead(run(text, "obi-pl"), "003")
        }
    }
    @Test fun `u nas uses conversation profile rather than card or model branch`() = runBlocking {
        assertRead(run("u nas?", hints = listOf("203")), "205")
        assertRead(run("u nas?", "obi-pl", listOf("003")), "075")
    }
    @Test fun `tam refers only to preceding unique USER location`() = runBlocking {
        assertRead(run("a tam?", hints = listOf("205")), "203")
        assertRead(run("a tam?", "obi-pl", listOf("075"), history("obi-pl", "sprawdź stan produktu 7000001 w OBI 003")), "003")
    }
    @Test fun `correct USER scope cannot be vetoed by model hints`() = runBlocking {
        assertRead(run("sprawdź stan A9Z01440/SCH w Bochni", hints = listOf("205")), "203")
        assertRead(run("sprawdź stan produktu 7000001 w OBI 003", "obi-pl", listOf("075")), "003")
    }
    @Test fun `price request breaks follow-up permission`() = runBlocking {
        val r=run("w Bochni", prior=history("kwant-pl") + user("pokaż ceny"))
        assertTrue(r.reads.isEmpty()); assertEquals("rejected", r.evidence.status)
    }
    @Test fun `advice and installation cannot inherit stock permission`() = runBlocking {
        for(text in listOf("jak zamontować w Bochni", "a w Bochni pokaż ceny")) {
            assertTrue(run(text).reads.isEmpty())
        }
    }
    @Test fun `ASSISTANT suggestion alone cannot authorize inventory or tam`() = runBlocking {
        val r=run("a tam?", prior=listOf(AdvisorLocationHistoryMessage("ASSISTANT", "Sprawdź w Bochni", listOf(card("kwant-pl")))))
        assertTrue(r.reads.isEmpty()); assertEquals("rejected", r.evidence.status)
    }
    @Test fun `failed fresh turn cannot fabricate identity and reason asks for product`() = runBlocking {
        val r=run("w Bochni", cards=emptyList(), prior=listOf(user("sprawdź stan A9Z01440/SCH w Bochni"), AdvisorLocationHistoryMessage("ASSISTANT", "Failed turn")))
        assertTrue(r.reads.isEmpty()); assertEquals("untrusted_product",r.evidence.reason)
        assertFalse(r.state.text.contains("not authorized")); assertTrue(r.state.text.contains("produkt"))
    }
    @Test fun `wrong model product stays rejected before inventory`() = runBlocking {
        val r=run("sprawdź stan A9Z01440/SCH w Bochni",modelId="90002")
        assertTrue(r.reads.isEmpty()); assertEquals("untrusted_product",r.evidence.reason)
        assertFalse(r.state.text.contains("not authorized"))
    }
    @Test fun `unverified current numeric product never falls back to historical product`() = runBlocking {
        val r=run("sprawdź stan produktu 9999 w Bochni")
        assertTrue(r.reads.isEmpty()); assertEquals("untrusted_product",r.evidence.reason)
    }
    @Test fun `unverified current article never falls back to historical product`() = runBlocking {
        assertTrue(run("sprawdź stan A9Z01441/SCH w Bochni").reads.isEmpty())
    }
    @Test fun `unknown city has truthful local reason without inventory`() = runBlocking {
        val r=run("w Zakopanem")
        assertTrue(r.reads.isEmpty()); assertEquals("unknown_location",r.evidence.reason)
        assertFalse(r.state.text.contains("not authorized"))
    }
    @Test fun `ambiguous OBI city asks one precise question instead of choosing`() = runBlocking {
        val r=run("w Krakowie", "obi-pl", listOf("003"))
        assertTrue(r.reads.isEmpty()); assertEquals("ambiguous_location",r.evidence.reason)
        assertTrue(r.state.text.contains("ulic")); assertFalse(r.state.text.contains("not authorized"))
    }
    @Test fun `tam cannot use ASSISTANT location suggestion after unscoped USER anchor`() = runBlocking {
        val r=run("a tam?", prior=listOf(user("sprawdź stan A9Z01440/SCH"),AdvisorLocationHistoryMessage("ASSISTANT","W Bochni",listOf(card("kwant-pl")))))
        assertTrue(r.reads.isEmpty()); assertEquals("ambiguous_location",r.evidence.reason)
    }
    @Test fun `directory outage has truthful local reason`() = runBlocking {
        val r=run("w Bochni", directoryUnavailable=true)
        assertTrue(r.reads.isEmpty()); assertEquals("directory_unavailable",r.evidence.reason)
        assertFalse(r.state.text.contains("not authorized"))
    }
    @Test fun `quantity and dimensions cannot authorize branches through short follow-ups`() = runBlocking {
        for(text in listOf("203 m", "203 x 205 mm", "produkt 203", "1000 W")) assertTrue(run(text).reads.isEmpty())
    }
}
