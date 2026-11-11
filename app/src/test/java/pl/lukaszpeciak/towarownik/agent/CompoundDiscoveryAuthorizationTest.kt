package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.diagnostics.*
import pl.lukaszpeciak.towarownik.product.provider.*

/** Production Controller/resolver/tools; scripted model and in-memory provider only. */
class CompoundDiscoveryAuthorizationTest {
    private val branches = listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz", "Tarnowska 149"),
        ProviderBranch(BranchId("204"), "Tarnów", "Lwowska 20"),
        ProviderBranch(BranchId("203"), "Bochnia", "Krakowska 10"),
    )
    private val twoBranches = "Sprawdź stan MBN116E w KWANT Nowy Sącz i w KWANT Tarnów"
    private val conditional = "Dla Karlik MINI taupe znajdź w sieci kody producenta: włącznik pojedynczy, gniazdo pojedyncze i ramka okrągła. Sprawdź te artykuły w KWANT Nowy Sącz 205, a brakujące w KWANT Tarnów 204. Podsumuj wyniki."
    private fun request(branch: String, vararg queries: String, provider: String = "kwant-pl") =
        AdvisorToolArguments(branch, queries.map { AdvisorToolQuery(it, 1) }, provider)
    private data class Fact(val stock: Int? = 7, val status: String = "found", val id: String? = null)
    private data class Seen(
        val executions: List<Pair<String, List<String>>>, val outputs: List<AdvisorToolContinuation>,
        val reasons: List<String?>, val state: AdvisorUiState, val profiles: List<String>,
    )
    private fun run(
        text: String, requests: List<AdvisorToolArguments>,
        facts: Map<Pair<String, String>, Fact> = emptyMap(), previous: String? = null,
    ): Seen = runBlocking {
        val executions = mutableListOf<Pair<String, List<String>>>()
        val outputs = mutableListOf<AdvisorToolContinuation>()
        val events = mutableListOf<SafeDiagnosticEvent>()
        val profiles = mutableListOf<String>()
        var index = 0
        var activeBranch = "205"
        val queriesById = mutableMapOf<String, String>()
        val ids = mutableMapOf<String, String>()
        val provider = object : ProductProvider {
            override val providerId = ProviderId("kwant-pl")
            override fun branches() = ProviderBranchResult.Available(branches)
            override fun search(query: String, maxResults: Int): ProviderSearchResult {
                val fact = facts[activeBranch to query] ?: Fact()
                if (fact.status == "not_found") return ProviderSearchResult.NotFound
                if (fact.status == "unavailable") return ProviderSearchResult.Unavailable(ProductProviderFailure.NETWORK, "synthetic")
                val id = fact.id ?: ids.getOrPut(query) { (90001 + ids.size).toString() }
                queriesById[id] = query
                return ProviderSearchResult.Candidates(listOf(ProviderProductCandidate(ProductRef(providerId, id), "Synthetic", query)))
            }
            override fun lookup(ref: ProductRef, branchId: BranchId): ProviderLookupResult {
                val query = requireNotNull(queriesById[ref.productId])
                val fact = facts[branchId.value to query] ?: Fact()
                return ProviderLookupResult.Found(ProviderProduct(ref, branchId, "Synthetic $query", fact.stock,
                    grossPrice = BigDecimal.ONE, priceScope = ProviderPriceScope.ONLINE,
                    productUrl = "https://offline.invalid/product", ean = null, articleNumber = query))
            }
        }
        val tool = FindProviderProductsTool(ProductProviderRegistry(listOf(provider)), now = { 7000L })
        fun next(): AdvisorProxyCallResult {
            if (index < requests.size) {
                val call = index++
                return AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest("resp_$call", "call_$call", requests[call]))
            }
            val refs = outputs.filterIsInstance<AdvisorToolContinuation.Verified>().flatMap { out ->
                out.result.products.map { AdvisorProductRef(out.result.branchId, it.productId, "kwant-pl") }
            }.distinct().take(5)
            return AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_final",
                "Syntetyczne wyniki. Pozostałych części nie zweryfikowano.", refs))
        }
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { message, p, b -> assertNull(previous); assertEquals(text, message); assertEquals("kwant-pl", p); profiles += b; next() },
            messageAgent = { id, message, p, b -> assertEquals(previous, id); assertEquals(text, message); assertEquals("kwant-pl", p); profiles += b; next() },
            continueAgent = { _, _, p, b, _, result -> assertEquals("kwant-pl", p); profiles += b; outputs += result; next() },
            executeObiTool = { error("OBI execution forbidden") },
            executeProviderTool = { args ->
                activeBranch = args.branchId
                executions += args.branchId to args.queries.map { it.query }
                tool.execute(args)
            },
            executeLocationsTool = { error("Inventory execution forbidden") },
            branchDirectory = { ProviderBranchResult.Available(branches) },
        )
        val state = controller.runTurn(text, previous, "205", "kwant-pl", onDiagnosticEvent = { events += it }) {}
        assertTrue("WorkingProfile must stay 205 in every model call", profiles.all { it == "205" })
        println("R7B text=$text executions=$executions reasons=${events.map { it.reason }}")
        Seen(executions, outputs, events.filter { it.stage == DiagnosticStage.TOOL_RESULT }.map { it.reason }, state, profiles)
    }

    @Test fun knownBranchThenResearchAdmitsOneOff204() {
        val r = run("Sprawdź w KWANT Tarnów i znajdź w sieci kody producenta", listOf(request("205", "MBN116E")))
        assertEquals(listOf("204" to listOf("MBN116E")), r.executions)
    }
    @Test fun naturalNamedMultiBranchAdmitsBothBoundedSubtasks() {
        val r = run(twoBranches, listOf(request("205", "MBN116E"), request("204", "MBN116E")))
        assertEquals(listOf("205", "204"), r.executions.map { it.first })
        assertEquals(setOf("205", "204"), (r.state as AdvisorUiState.Success).products.map { it.effectiveBranchId }.toSet())
    }
    @Test fun originalConditionalKarlikAdmitsOnlyPrimaryFirst() {
        val r = run(conditional, listOf(request("205", "Karlik MINI taupe")))
        assertEquals(listOf("205"), r.executions.map { it.first })
    }
    @Test fun conditionalZeroAllowsSameFreshProductFallback() {
        val r = run(conditional, listOf(request("205", "MBN116E"), request("204", "MBN116E")), mapOf(("205" to "MBN116E") to Fact(0)))
        assertEquals(listOf("205", "204"), r.executions.map { it.first })
        assertEquals(setOf(0, 7), (r.state as AdvisorUiState.Success).products.map { it.stock }.toSet())
    }
    @Test fun conditionalNotFoundAllowsSameQueryFallback() {
        val r = run(conditional, listOf(request("205", "MBN116E"), request("204", "MBN116E")), mapOf(("205" to "MBN116E") to Fact(status = "not_found")))
        assertEquals(listOf("205", "204"), r.executions.map { it.first })
    }
    @Test fun prematureFallbackIsDeniedButPrimaryCanStillRecover() {
        val r = run(conditional, listOf(request("204", "MBN116E"), request("205", "MBN116E"), request("204", "MBN116E")), mapOf(("205" to "MBN116E") to Fact(0)))
        assertEquals(listOf("205", "204"), r.executions.map { it.first })
        assertTrue(r.outputs.first() is AdvisorToolContinuation.RejectedStore)
    }
    @Test fun unknownStockDoesNotFulfilFallbackCondition() {
        val r = run(conditional, listOf(request("205", "MBN116E"), request("204", "MBN116E")), mapOf(("205" to "MBN116E") to Fact(null)))
        assertEquals(listOf("205"), r.executions.map { it.first })
        assertTrue(r.outputs.last() is AdvisorToolContinuation.RejectedStore)
    }
    @Test fun unavailableDoesNotFulfilFallbackCondition() {
        val r = run(conditional, listOf(request("205", "MBN116E"), request("204", "MBN116E")), mapOf(("205" to "MBN116E") to Fact(status = "unavailable")))
        assertEquals(listOf("205"), r.executions.map { it.first })
        assertTrue(r.outputs.last() is AdvisorToolContinuation.RejectedStore)
    }
    @Test fun positiveStockDoesNotFulfilFallbackCondition() {
        val r = run(conditional, listOf(request("205", "MBN116E"), request("204", "MBN116E")))
        assertEquals(listOf("205"), r.executions.map { it.first })
    }
    @Test fun unrelatedQueryCannotBorrowMissingOutcome() {
        val r = run(conditional, listOf(request("205", "MBN116E"), request("204", "MBN316E")), mapOf(("205" to "MBN116E") to Fact(0)))
        assertEquals(listOf("205"), r.executions.map { it.first })
    }
    @Test fun mixedFallbackBatchIsRejectedAsAWhole() {
        val r = run(conditional, listOf(request("205", "MBN116E", "MBN316E"), request("204", "MBN116E", "MBN316E")), mapOf(("205" to "MBN116E") to Fact(0)))
        assertEquals(listOf("205"), r.executions.map { it.first })
    }
    @Test fun fallbackIdentityMismatchCannotPublishFreshWrongProduct() {
        val r = run(conditional, listOf(request("205", "MBN116E"), request("204", "MBN116E")), mapOf(
            ("205" to "MBN116E") to Fact(0, id = "90001"), ("204" to "MBN116E") to Fact(7, id = "90002")))
        assertEquals(listOf("205", "204"), r.executions.map { it.first })
        val final = r.outputs.last() as AdvisorToolContinuation.Verified
        assertEquals(AdvisorQueryResultStatus.UNAVAILABLE, final.result.results.single().status)
        assertTrue(final.result.products.isEmpty())
        assertTrue((r.state as AdvisorUiState.Success).products.none { it.effectiveProductId == "90002" })
    }
    @Test fun repeatedCompletedReadUsesTurnLocalEvidenceAndStillCountsLogicalCalls() {
        val r = run(twoBranches, listOf(request("205", "MBN116E"), request("205", "MBN116E"), request("204", "MBN116E")))
        assertEquals(listOf("205", "204"), r.executions.map { it.first })
        assertEquals(3, r.outputs.size)
    }
    @Test fun overlappingBatchDoesNotRepeatCompletedQuery() {
        val r = run(twoBranches, listOf(request("205", "MBN116E"), request("205", "MBN116E", "MBN316E")))
        assertEquals(listOf("205" to listOf("MBN116E"), "205" to listOf("MBN316E")), r.executions)
        assertEquals(2, (r.outputs.last() as AdvisorToolContinuation.Verified).result.results.size)
    }
    @Test fun explicitUserScopeCannotBeExtendedByModelBranch() {
        val r = run(twoBranches, listOf(request("203", "MBN116E")))
        assertTrue(r.executions.isEmpty())
    }
    @Test fun modelProviderCannotExtendUserAuthority() {
        val r = run(twoBranches, listOf(request("205", "MBN116E", provider = "obi-pl")))
        assertTrue(r.executions.isEmpty())
        assertEquals("wrong_provider", r.reasons.single())
    }
    @Test fun completeScopeValidationRetainsUnknownForeignDeicticAndContradictoryPlaces() {
        for (tail in listOf("w Zakopanem", "w nieznanym mieście", "tam", "w innym oddziale", "w KWANT Tarnów 205", "w KWANT Tarnów na Tarnowskiej", "w OBI Tarnów 204")) {
            val r = run("Sprawdź w KWANT Nowy Sącz 205 i znajdź w sieci kody, a stan sprawdź $tail", listOf(request("205", "MBN116E")))
            assertTrue("Must reject entire scope: $tail", r.executions.isEmpty())
        }
    }
    @Test fun unknownJoinedLocalityIsNotAnActionBoundary() {
        val r = run("Sprawdź w KWANT Tarnów i Zakopane i znajdź w sieci kody producenta", listOf(request("204", "MBN116E")))
        assertTrue(r.executions.isEmpty())
    }
    @Test fun messageStagePreservesOriginalMultilineTextAndSavedProfile() {
        val text = "Sprawdź w KWANT Tarnów\ni znajdź w sieci kody producenta\n  Karlik MINI taupe"
        val r = run(text, listOf(request("204", "MBN116E")), previous = "resp_previous")
        assertEquals(listOf("204"), r.executions.map { it.first })
    }
    @Test fun rawResolverAndInventoryEntryPointRetainTheirPriorGrammar() {
        val r = BranchResolver.resolve("Sprawdź w KWANT Tarnów i znajdź w sieci kody producenta", branches, BranchId("205"))
        assertEquals(BranchResolution.UnknownMention, r)
    }
}
