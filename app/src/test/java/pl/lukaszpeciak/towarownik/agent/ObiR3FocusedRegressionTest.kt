package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.OBI_STORES
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult

/** Scripted model, real controller authorization/continuations and offline OBI exact-lookup fixture. */
class ObiR3FocusedRegressionTest {
    private val branches = OBI_STORES.map {
        ProviderBranch(BranchId(it.storeNumber), it.city, it.address)
    }
    private fun snapshot(id: String, branch: String = "075") = VerifiedProductSnapshot(
        obik = id, productId = id, providerId = "obi-pl", branchId = branch, storeNumber = branch,
        name = "Synthetic $id", stock = 18, grossPrice = BigDecimal.ONE,
        productUrl = "https://offline.invalid/product/$id", verifiedAt = 1000L,
    )

    @Test fun `R3-P1 grouped explicit OBIK and independent name search both execute and render`() = runBlocking {
        val requested = listOf(
            AdvisorToolQuery("6117543", 1),
            AdvisorToolQuery("czarne opaski zaciskowe", 2),
        )
        val executed = mutableListOf<List<String>>()
        var continuation: AdvisorToolContinuation? = null
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { _, _, _ -> error("must be MESSAGE") },
            messageAgent = { _, _, _, _ ->
                AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
                    "resp_tools", "call_group", AdvisorToolArguments("075", requested, "obi-pl"),
                ))
            },
            continueAgent = { _, _, _, _, _, result ->
                continuation = result
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                    "resp_final", "Gniazdo 6117543 jest dostępne; znalazłam też czarne opaski zaciskowe.",
                    listOf(AdvisorProductRef("075", "6117543", "obi-pl"),
                        AdvisorProductRef("075", "7001001", "obi-pl")),
                ))
            },
            executeObiTool = { arguments ->
                executed += arguments.queries.map { it.query }
                val data = arguments.queries.map { query ->
                    val id = if (query.query == "6117543") "6117543" else "7001001"
                    AdvisorVerifiedQueryResult(query.query, AdvisorQueryResultStatus.VERIFIED,
                        listOf(AdvisorVerifiedProduct(id, "Synthetic $id", 18, price = BigDecimal.ONE)))
                }
                AdvisorToolExecutionResult.Success(
                    AdvisorVerifiedToolResult(arguments.branchId, data),
                    data.flatMap { it.products }.map { snapshot(it.obik, arguments.branchId) },
                )
            },
            executeProviderTool = { error("OBI must not switch provider transport") },
            branchDirectory = { ProviderBranchResult.Available(branches) },
        )
        val outcome = controller.runTurn(
            "Sprawdź stan OBIK 6117543 i znajdź czarne opaski zaciskowe.",
            "previous", "075", "obi-pl",
        ) {} as AdvisorUiState.Success

        assertEquals(listOf(listOf("6117543", "czarne opaski zaciskowe")), executed)
        val grouped = continuation as AdvisorToolContinuation.Verified
        assertEquals(2, grouped.result.results.size)
        assertEquals(requested.map { it.query }, grouped.result.results.map { it.query })
        assertEquals(setOf("6117543", "7001001"), outcome.products.map { it.effectiveProductId }.toSet())
        assertTrue(outcome.text.contains("opaski"))
        assertTrue(outcome.text.contains("6117543"))
    }

    @Test fun `R3-P2 invalid later location tool cannot overwrite good 6117543 at OBI 003`() = runBlocking {
        val calls = mutableListOf<String>()
        val continuations = mutableListOf<AdvisorToolContinuation>()
        var stage = 0
        val history = listOf(
            AdvisorLocationHistoryMessage("USER", "mamy 6117543 na stanie?"),
            AdvisorLocationHistoryMessage("ASSISTANT", "6117543 zweryfikowany", listOf(snapshot("6117543"))),
        )
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { _, _, _ -> error("must be MESSAGE") },
            messageAgent = { _, _, _, _ ->
                AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
                    "resp_good", "call_good",
                    AdvisorToolArguments("075", listOf(AdvisorToolQuery("3496072", 1)), "obi-pl"),
                ))
            },
            continueAgent = { _, _, _, _, _, result ->
                continuations += result
                if (stage++ == 0) {
                    AdvisorProxyCallResult.Success(AdvisorProxyResult.LocationToolRequest(
                        "resp_bad", "call_bad",
                        AdvisorLocationArguments("obi-pl", "3496072", listOf("019")),
                    ))
                } else AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                    "resp_final", "Nie mogę sprawdzić produktu.", emptyList(),
                ))
            },
            executeObiTool = { arguments ->
                calls += arguments.queries.map { "${it.query}@${arguments.branchId}" }
                val query = arguments.queries.single()
                AdvisorToolExecutionResult.Success(
                    AdvisorVerifiedToolResult(arguments.branchId, listOf(
                        AdvisorVerifiedQueryResult(query.query, AdvisorQueryResultStatus.VERIFIED,
                            listOf(AdvisorVerifiedProduct(query.query, "Synthetic", 18, price = BigDecimal.ONE))),
                    )),
                    listOf(snapshot(query.query, arguments.branchId)),
                )
            },
            executeProviderTool = { error("wrong provider") },
            executeLocationsTool = { error("rejected location attempt must never read provider") },
            branchDirectory = { ProviderBranchResult.Available(branches) },
        )

        val outcome = controller.runTurn(
            "a ile mają na Wielickiej w Krakowie?",
            "previous", "075", "obi-pl",
            historicalVerifiedProducts = listOf(snapshot("6117543")),
            locationHistory = history,
        ) {} as AdvisorUiState.Success

        assertEquals(listOf("6117543@003"), calls)
        assertTrue(continuations.first() is AdvisorToolContinuation.Verified)
        assertTrue(continuations[1] is AdvisorToolContinuation.Locations)
        val rejection = (continuations[1] as AdvisorToolContinuation.Locations).evidence
        assertEquals("rejected", rejection.status)
        assertTrue(rejection.checkedIds.isEmpty())
        assertEquals("6117543", outcome.products.single().effectiveProductId)
        assertEquals("003", outcome.products.single().effectiveBranchId)
        assertTrue(outcome.text.contains("003"))
        assertTrue(outcome.text.contains("18 szt."))
        assertFalse(outcome.text.contains("019 —"))
        assertFalse(outcome.text.contains("Nie mogę sprawdzić produktu."))
    }

    private suspend fun r4TwoQueryScenario(grouped: Boolean): Triple<AdvisorUiState.Success, List<List<String>>, List<AdvisorToolContinuation>> {
        val name = "czarne opaski zaciskowe"
        val modelCalls = if (grouped) listOf(
            listOf(AdvisorToolQuery("3496072", 1), AdvisorToolQuery(name, 2)),
        ) else listOf(
            listOf(AdvisorToolQuery("6117543", 1)),
            listOf(AdvisorToolQuery(name, 2)),
        )
        val reads = mutableListOf<List<String>>()
        val continuations = mutableListOf<AdvisorToolContinuation>()
        var next = 0
        fun tool(queries: List<AdvisorToolQuery>) = AdvisorProxyCallResult.Success(
            AdvisorProxyResult.ToolRequest(
                "resp_${next}", "call_${next}",
                AdvisorToolArguments("075", queries, "obi-pl"),
            ),
        )
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { _, _, _ -> error("expected MESSAGE") },
            messageAgent = { _, _, _, _ -> tool(modelCalls[next++]) },
            continueAgent = { _, _, _, _, _, result ->
                continuations += result
                if (next < modelCalls.size) tool(modelCalls[next++])
                else AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                    "final",
                    "Zweryfikowałam stan 6117543 i znalazłam czarne opaski zaciskowe.",
                    listOf(
                        AdvisorProductRef("075", "6117543", "obi-pl"),
                        AdvisorProductRef("075", "7001001", "obi-pl"),
                    ),
                ))
            },
            executeObiTool = { args ->
                reads += args.queries.map { it.query }
                val results = args.queries.map { query ->
                    val id = if (query.query == name) "7001001" else query.query
                    AdvisorVerifiedQueryResult(
                        query.query, AdvisorQueryResultStatus.VERIFIED,
                        listOf(AdvisorVerifiedProduct(id, "Synthetic ${id}", 18, price = BigDecimal.ONE)),
                    )
                }
                AdvisorToolExecutionResult.Success(
                    AdvisorVerifiedToolResult(args.branchId, results),
                    results.flatMap { it.products }.map { snapshot(it.obik, args.branchId) },
                )
            },
            executeProviderTool = { error("OBI provider isolation") },
            branchDirectory = { ProviderBranchResult.Available(branches) },
        )
        val result = controller.runTurn(
            "Sprawdź stan OBIK 6117543 i znajdź czarne opaski zaciskowe.",
            "previous", "075", "obi-pl",
        ) {} as AdvisorUiState.Success
        return Triple(result, reads, continuations)
    }

    @Test fun `R4-P1 two successive independent OBI tool calls retain both identities and UI cards`() = runBlocking {
        val (outcome, reads, continuations) = r4TwoQueryScenario(grouped = false)
        assertEquals(listOf(listOf("6117543"), listOf("czarne opaski zaciskowe")), reads)
        assertEquals(2, continuations.size)
        assertTrue(continuations.all { it is AdvisorToolContinuation.Verified })
        assertEquals(
            listOf("6117543", "czarne opaski zaciskowe"),
            continuations.map { (it as AdvisorToolContinuation.Verified).result.results.single().query },
        )
        assertEquals(setOf("6117543", "7001001"), outcome.products.map { it.effectiveProductId }.toSet())
        assertTrue(outcome.text.contains("opaski"))
        assertTrue(outcome.text.contains("6117543"))
    }

    @Test fun `R4-P2 grouped wrong OBIK is corrected in unique numeric slot without erasing independent name search`() = runBlocking {
        val (outcome, reads, continuations) = r4TwoQueryScenario(grouped = true)
        assertEquals(listOf(listOf("6117543", "czarne opaski zaciskowe")), reads)
        val verified = continuations.single() as AdvisorToolContinuation.Verified
        assertEquals(listOf("6117543", "czarne opaski zaciskowe"),
            verified.result.results.map { it.query })
        assertEquals(setOf("6117543", "7001001"), outcome.products.map { it.effectiveProductId }.toSet())
        assertTrue(outcome.text.contains("opaski"))
        assertTrue(outcome.text.contains("6117543"))
        assertFalse(outcome.text.contains("3496072"))
    }
}
