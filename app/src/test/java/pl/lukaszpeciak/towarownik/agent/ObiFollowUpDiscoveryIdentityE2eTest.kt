package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.OBI_STORES
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult

/** E2E fake model -> native Controller authorization -> exact provider read -> final rendering. */
class ObiFollowUpDiscoveryIdentityE2eTest {
    private val directory = OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }
    private fun card(id: String, branch: String = "075") = VerifiedProductSnapshot(
        obik = id, productId = id, providerId = "obi-pl", branchId = branch, storeNumber = branch,
        name = "OBI fixture $id", stock = 18, grossPrice = BigDecimal.ONE,
        productUrl = "https://offline.invalid/product/$id", verifiedAt = 1000L,
    )
    private fun history() = listOf(
        AdvisorLocationHistoryMessage("USER", "Sprawdź stan OBIK 3496072 u nas"),
        AdvisorLocationHistoryMessage("ASSISTANT", "3496072 dostępny", listOf(card("3496072"))),
        AdvisorLocationHistoryMessage("USER", "mamy 6117543 na stanie?"),
        AdvisorLocationHistoryMessage("ASSISTANT", "6117543 dostępny", listOf(card("6117543"))),
    )
    private data class Outcome(val state: AdvisorUiState.Success, val calls: List<String>,
        val responses: List<AdvisorToolContinuation>)

    private suspend fun execute(
        user: String = "a ile mają na Wielickiej w Krakowie?",
        proposed: String = "3496072",
        provider: String = "obi-pl",
        history: List<AdvisorLocationHistoryMessage> = history(),
        cards: List<VerifiedProductSnapshot> = listOf(card("3496072"), card("6117543")),
        secondAttempt: String? = null,
    ): Outcome {
        val calls = mutableListOf<String>()
        val responses = mutableListOf<AdvisorToolContinuation>()
        var index = 0
        fun tool(id: String, p: String = provider) =
            AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
                "resp_$id", "call_$id",
                AdvisorToolArguments("075", listOf(AdvisorToolQuery(id, 1)), p),
            ))
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { _, _, _ -> tool(proposed) },
            messageAgent = { _, _, _, _ -> tool(proposed) },
            continueAgent = { _, _, _, _, _, result ->
                responses += result
                if (index++ == 0 && secondAttempt != null) tool(secondAttempt)
                else AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                    "resp_final", "Na Wielickiej jest 18 sztuk produktu $proposed",
                    listOf(AdvisorProductRef("003", proposed, "obi-pl")),
                ))
            },
            executeObiTool = { args ->
                calls += "obi:${args.query}@${args.branchId}"
                val id = args.query
                val branch = args.branchId
                AdvisorToolExecutionResult.Success(
                    AdvisorVerifiedToolResult(branch, listOf(AdvisorVerifiedQueryResult(
                        id, AdvisorQueryResultStatus.VERIFIED,
                        listOf(AdvisorVerifiedProduct(id, "OBI fixture $id", stock = 18, price = BigDecimal.ONE)),
                    ))),
                    listOf(card(id, branch)),
                )
            },
            executeProviderTool = { args ->
                calls += "provider:${args.query}@${args.branchId}"
                val id = args.query
                val branch = args.branchId
                AdvisorToolExecutionResult.Success(
                    AdvisorVerifiedToolResult(branch, listOf(AdvisorVerifiedQueryResult(
                        id, AdvisorQueryResultStatus.VERIFIED,
                        listOf(AdvisorVerifiedProduct(id, "OBI fixture $id", stock = 18, price = BigDecimal.ONE)),
                    ))),
                    listOf(card(id, branch)),
                )
            },
            branchDirectory = { ProviderBranchResult.Available(directory) },
        )
        return Outcome(
            controller.runTurn(user, "resp_previous", "075", "obi-pl",
                historicalVerifiedProducts = cards, locationHistory = history) { } as AdvisorUiState.Success,
            calls, responses,
        )
    }

    @Test fun `RED wrong OBIK proposed for inherited Wielicka never reaches provider or final state`() = runBlocking {
        val r = execute()
        assertTrue(r.calls.isEmpty())
        assertTrue(r.responses.first() is AdvisorToolContinuation.RejectedStore)
        assertTrue(r.state.products.isEmpty())
        assertFalse(r.state.text.contains("18 sztuk"))
        assertTrue(r.state.text.contains("produktu") || r.state.text.contains("produkt"))
    }

    @Test fun `RED later correct model retry in same turn cannot hide earlier product swap`() = runBlocking {
        val r = execute(secondAttempt = "6117543")
        assertTrue(r.calls.isEmpty())
        assertTrue(r.state.products.isEmpty())
        assertFalse(r.state.text.contains("18 sztuk"))
    }

    @Test fun `RED correct inherited OBIK is freshly checked in requested market`() = runBlocking {
        val r = execute(proposed = "6117543")
        assertEquals(listOf("provider:6117543@003"), r.calls)
        assertEquals("6117543", r.state.products.single().effectiveProductId)
        assertEquals("003", r.state.products.single().effectiveBranchId)
    }

    @Test fun `RED without a trusted assistant card a location followup cannot invent identity`() = runBlocking {
        val r = execute(history = listOf(
            AdvisorLocationHistoryMessage("USER", "mamy 6117543 na stanie?"),
            AdvisorLocationHistoryMessage("ASSISTANT", "Nie udało się sprawdzić"),
        ), cards = emptyList())
        assertTrue(r.calls.isEmpty())
        assertTrue(r.state.products.isEmpty())
        assertFalse(r.state.text.contains("18 sztuk"))
    }

    @Test fun `bounded historical same-product and bare market followups reject wrong model OBIK`() = runBlocking {
        for (question in listOf(
            "a na Wielickiej?",
            "Sprawdź stan tego samego produktu w OBI 003",
            "A ile tego samego produktu jest w OBI Kraków przy ulicy Wielickiej?",
        )) {
            val r = execute(user = question)
            assertTrue(question, r.calls.isEmpty())
            assertTrue(question, r.state.products.isEmpty())
            assertFalse(question, r.state.text.contains("18 sztuk"))
        }
    }

    @Test fun `new explicit user OBIK overrides old selection and wrong provider is still denied`() = runBlocking {
        val fresh = execute(user = "Mamy 3496072 na stanie?", proposed = "3496072")
        assertEquals(listOf("provider:3496072@075"), fresh.calls)
        val crossProvider = execute(proposed = "6117543", provider = "kwant-pl")
        assertTrue(crossProvider.calls.isEmpty())
        assertTrue(crossProvider.state.products.isEmpty())
    }
}
