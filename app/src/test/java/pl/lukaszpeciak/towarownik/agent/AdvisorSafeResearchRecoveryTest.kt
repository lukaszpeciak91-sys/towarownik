package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.diagnostics.*
import pl.lukaszpeciak.towarownik.product.*
import pl.lukaszpeciak.towarownik.product.provider.*

/** Scripted model, actual Controller/tools and synthetic facts. No live traffic. */
class AdvisorSafeResearchRecoveryTest {
    private val safe = "Dokumentacja producenta opisuje serię. Nie sprawdzałam zapasów ani cen."
    private val unsafe = "Produkt jest dostępny: 999 sztuk po 1 zł."
    private val source = AdvisorWebSource("Synthetic manufacturer documentation", "https://manufacturer.example.invalid/manual", 0, 10)
    private data class Seen(val state: AdvisorUiState.Success, val outputs: List<AdvisorToolContinuation>, val executions: List<String>, val reasons: List<String?>)
    private fun run(
        prose: String = safe, provider: String = "kwant-pl", text: String = "Znajdź w sieci instrukcję Hagera",
        sources: List<AdvisorWebSource> = listOf(source), webCalls: Long = 1, refs: Boolean = false,
        laterAuthorized: Boolean = false, stock: Int? = 7, status: String = "found", requestedBranch: String? = null,
        rejectProvider: Boolean = true,
    ): Seen = runBlocking {
        val branch = if (provider == "obi-pl") "075" else "205"
        val productId = if (provider == "obi-pl") "7000001" else "90001"
        val branches = if (provider == "obi-pl") OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) } else listOf(
            ProviderBranch(BranchId("205"), "Nowy Sącz"), ProviderBranch(BranchId("204"), "Tarnów"),
        )
        val executions = mutableListOf<String>()
        val outputs = mutableListOf<AdvisorToolContinuation>()
        val events = mutableListOf<SafeDiagnosticEvent>()
        val p = object : ProductProvider {
            override val providerId = ProviderId(provider)
            override fun branches() = ProviderBranchResult.Available(branches)
            override fun search(query: String, maxResults: Int): ProviderSearchResult = when (status) {
                "not_found" -> ProviderSearchResult.NotFound
                "unavailable" -> ProviderSearchResult.Unavailable(ProductProviderFailure.NETWORK, "Synthetic")
                else -> ProviderSearchResult.Candidates(listOf(ProviderProductCandidate(ProductRef(providerId, productId), "Synthetic", "SYNTHETIC")))
            }
            override fun lookup(ref: ProductRef, branchId: BranchId) = ProviderLookupResult.Found(ProviderProduct(
                ref, branchId, "Synthetic product", stock, grossPrice = BigDecimal.ONE,
                priceScope = ProviderPriceScope.ONLINE, productUrl = "https://offline.invalid/product", ean = null, articleNumber = "SYNTHETIC",
            ))
        }
        val local = FindProviderProductsTool(ProductProviderRegistry(listOf(p)), now = { 9000L })
        val obi = FindObiProductsTool(
            searchProducts = { ProductSearchResult.Candidates(listOf(ProductSearchCandidate(productId, "Synthetic"))) },
            lookupObik = { id, b -> ProductLookupResult.Found(LocalProduct(id, "Synthetic", stock, BigDecimal.ONE, "https://offline.invalid/product", null, b)) },
            now = { 9000L },
        )
        fun tool(id: String, wrong: Boolean) = AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
            "resp_$id", "call_$id", AdvisorToolArguments(branch, listOf(AdvisorToolQuery("Synthetic category", 1)),
                if (wrong) if (provider == "obi-pl") "kwant-pl" else "obi-pl" else provider, requestedBranch),
        ))
        val script = buildList {
            add(tool("rejected", rejectProvider))
            if (laterAuthorized) add(tool("authorized", false))
            add(AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_final", prose,
                if (refs) listOf(AdvisorProductRef(branch, productId, provider)) else emptyList(), sources, webCalls)))
        }
        var calls = 0
        fun next(): AdvisorProxyCallResult = script[calls++]
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { message, p, b -> assertEquals(text, message); assertEquals(provider, p); assertEquals(branch, b); next() },
            messageAgent = { _, _, _, _ -> error("New conversation only") },
            continueAgent = { _, _, p, b, _, result -> assertEquals(provider, p); assertEquals(branch, b); outputs += result; next() },
            executeObiTool = { args -> assertEquals("obi-pl", provider); executions += args.branchId; obi.execute(args) },
            executeProviderTool = { args -> assertEquals("kwant-pl", provider); executions += args.branchId; local.execute(args) },
            executeLocationsTool = { error("Inventory traffic forbidden") },
            branchDirectory = { ProviderBranchResult.Available(branches) },
        )
        val state = controller.runTurn(text, null, branch, provider, onDiagnosticEvent = { events += it }) {} as AdvisorUiState.Success
        println("R2 provider=$provider executions=$executions sources=${state.sources.size} reasons=${events.map { it.reason }}")
        Seen(state, outputs, executions, events.filter { it.stage == DiagnosticStage.TOOL_RESULT }.map { it.reason })
    }

    @Test fun originalR7aResearchWitnessRetainsItsIndependentReferencesAfterWrongProviderDenial() {
        val r = run()
        assertTrue(r.executions.isEmpty())
        assertEquals(listOf(source.url), r.state.sources.map { it.url })
        assertTrue(r.state.text.contains("Nie sprawdziłam stanów"))
        assertTrue(r.state.products.isEmpty())
    }
    @Test fun obiAndKwantKeepSeparateAuthorizationButCanRetainResearchReferences() {
        for (provider in listOf("obi-pl", "kwant-pl")) {
            val r = run(provider = provider)
            assertEquals(listOf("wrong_provider"), r.reasons)
            assertTrue(r.executions.isEmpty())
            assertEquals(listOf(source.url), r.state.sources.map { it.url })
        }
    }
    @Test fun citationIndicesCannotBeAttachedToTheReplacementLocalFailureText() {
        val r = run()
        assertEquals(1, r.state.sources.size)
        assertNull(r.state.sources.single().startIndex)
        assertNull(r.state.sources.single().endIndex)
    }
    @Test fun sourceTitleCannotSmuggleInventoryOrPriceClaims() {
        val r = run(sources = listOf(source.copy(title = unsafe)))
        assertEquals(listOf(source.url), r.state.sources.map { it.url })
        assertTrue(r.state.sources.none { it.title.contains("999") || it.title.contains("1 zł") })
    }
    @Test fun pureInventoryClaimsStayBlockedEvenWithEmptyRefsAndWebMetadata() {
        val r = run(prose = unsafe)
        assertTrue(r.executions.isEmpty())
        assertFalse(r.state.text.contains("999"))
        assertTrue(r.state.products.isEmpty())
    }
    @Test fun mixedResearchAndFabricatedInventoryCannotPassAsASafeSegment() {
        for (prose in listOf("$safe $unsafe", "$safe W naszym oddziale czeka cała paleta.", "$safe Lokalna cena wynosi pięć złotych.")) {
            val r = run(prose = prose)
            assertFalse(r.state.text.contains(prose))
            assertFalse(r.state.text.contains("paleta"))
            assertFalse(r.state.text.contains("pięć"))
            assertTrue(r.state.products.isEmpty())
        }
    }
    @Test fun forgedProductRefsDoNotCreateCardsAfterRejectedExecution() {
        val r = run(prose = unsafe, refs = true)
        assertTrue(r.executions.isEmpty())
        assertTrue(r.state.products.isEmpty())
        assertFalse(r.state.text.contains("999"))
    }
    @Test fun proseUrlsAreNeverPromotedToSourceMetadata() {
        val r = run(prose = "$safe https://fabricated.example.invalid/stock", sources = emptyList())
        assertTrue(r.state.sources.isEmpty())
        assertFalse(r.state.text.contains("fabricated.example"))
    }
    @Test fun uncorroboratedReferenceMetadataWithoutCompletedWebWorkIsNotRecovered() {
        assertTrue(run(webCalls = 0).state.sources.isEmpty())
    }
    @Test fun noSourceOrModelDeclarationCanAuthenticateTheFreeformAdvice() {
        val advice = "Wyłącznik różnicowoprądowy wykrywa prąd upływu."
        val safeAnswer = run(prose = advice, text = "Wyjaśnij działanie RCD", sources = emptyList(), webCalls = 0)
        val unsafeAnswer = run(prose = "$advice $unsafe", text = "Wyjaśnij działanie RCD", sources = emptyList(), webCalls = 0)
        // The unchanged wire has no independently verified technical-content field.
        // Until such evidence exists, neither arbitrary string may bypass denial.
        assertEquals(safeAnswer.state.text, unsafeAnswer.state.text)
        assertFalse(safeAnswer.state.text.contains(advice))
    }
    @Test fun aLaterAuthorizedReadRestoresTheExistingNormalAnswerPath() {
        for (provider in listOf("obi-pl", "kwant-pl")) {
            val r = run(provider = provider, laterAuthorized = true, refs = true)
            assertEquals(1, r.executions.size)
            assertTrue(r.outputs.first() is AdvisorToolContinuation.RejectedStore)
            assertTrue(r.outputs.last() is AdvisorToolContinuation.Verified)
            assertEquals(safe, r.state.text)
            assertEquals(listOf(source), r.state.sources)
            assertEquals(1, r.state.products.size)
            assertEquals(9000L, r.state.products.single().verifiedAt)
        }
    }
    @Test fun verifiedZeroAndUnknownStockRemainDistinctAfterSuccessfulRecovery() {
        for (stock in listOf(0, null)) {
            val r = run(laterAuthorized = true, refs = true, stock = stock)
            assertEquals(stock, r.state.products.single().stock)
            assertEquals(safe, r.state.text)
        }
    }
    @Test fun notFoundAndUnavailableRemainNonInventoryFacts() {
        for (status in listOf("not_found", "unavailable")) {
            val r = run(laterAuthorized = true, refs = true, status = status)
            val group = (r.outputs.last() as AdvisorToolContinuation.Verified).result.results.single()
            assertEquals(if (status == "not_found") AdvisorQueryResultStatus.NOT_FOUND else AdvisorQueryResultStatus.UNAVAILABLE, group.status)
            assertTrue(r.state.products.isEmpty())
        }
    }
    @Test fun unknownAndContradictoryUserLocationsStillNeverExecute() {
        for (text in listOf("Znajdź w sieci instrukcję i sprawdź stan w Zakopanem", "Sprawdź w KWANT Tarnów 205")) {
            val r = run(text = text, rejectProvider = false)
            assertTrue(r.executions.isEmpty())
            assertEquals(listOf("unknown_location"), r.reasons)
            assertTrue(r.state.products.isEmpty())
        }
    }
}
