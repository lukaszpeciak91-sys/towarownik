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
}
