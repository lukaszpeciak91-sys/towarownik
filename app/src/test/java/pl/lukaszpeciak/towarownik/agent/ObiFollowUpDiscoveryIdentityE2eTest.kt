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

    @Test fun `R2-F1 wrong model OBIK recovers trusted identity and performs one fresh OBI 003 lookup`() = runBlocking {
        val r = execute()
        assertEquals(listOf("obi:6117543@003"), r.calls)
        val output = r.responses.single() as AdvisorToolContinuation.Verified
        assertEquals("003", output.result.storeNumber)
        assertEquals("6117543", output.result.results.single().products.single().obik)
        assertEquals("6117543", r.state.products.single().effectiveProductId)
        assertEquals("003", r.state.products.single().effectiveBranchId)
        assertTrue(r.state.text.contains("6117543"))
        assertFalse(r.state.text.contains("3496072"))
    }

    @Test fun `R2-F5 model retry after safe recovery reuses trusted lookup without another provider read`() = runBlocking {
        val r = execute(secondAttempt = "6117543")
        assertEquals(listOf("obi:6117543@003"), r.calls)
        assertEquals(2, r.responses.size)
        assertTrue(r.responses.all { it is AdvisorToolContinuation.Verified })
        assertEquals("6117543", r.state.products.single().effectiveProductId)
        assertFalse(r.state.text.contains("3496072"))
    }

    @Test fun `RED correct inherited OBIK is freshly checked in requested market`() = runBlocking {
        val r = execute(proposed = "6117543")
        assertEquals(listOf("obi:6117543@003"), r.calls)
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

    @Test fun `R2-F2 natural single-market followup variants recover the correct historical OBIK`() = runBlocking {
        for (question in listOf(
            "Ile tego jest na Wielickiej w Krakowie?",
            "a na Wielickiej?",
            "a w OBI 003?",
            "Sprawdź stan tego samego produktu w OBI 003",
            "A ile tego samego produktu jest w OBI Kraków przy ulicy Wielickiej?",
        )) {
            val r = execute(user = question)
            assertEquals(question, listOf("obi:6117543@003"), r.calls)
            assertEquals(question, "6117543", r.state.products.single().effectiveProductId)
            assertEquals(question, "003", r.state.products.single().effectiveBranchId)
            assertFalse(question, r.state.text.contains("3496072"))
        }
    }

    @Test fun `R2-F3 intervening bounded price question retains product identity but not old stock`() = runBlocking {
        val withPrice = history() + listOf(
            AdvisorLocationHistoryMessage("USER", "Cena tego samego produktu?"),
            AdvisorLocationHistoryMessage("ASSISTANT", "Cena wymaga osobnej aktualnej weryfikacji."),
        )
        val r = execute(history = withPrice)
        assertEquals(listOf("obi:6117543@003"), r.calls)
        assertEquals("6117543", r.state.products.single().effectiveProductId)
        assertEquals(18, r.state.products.single().stock)
    }

    @Test fun `R2-F5 ambiguous history missing provider trust or broken referent never reads model OBIK`() = runBlocking {
        val cases = listOf(
            Pair(listOf(
                AdvisorLocationHistoryMessage("USER", "sprawdź oba OBIK 3496072 i 6117543"),
                AdvisorLocationHistoryMessage("ASSISTANT", "Oba zweryfikowane", listOf(card("3496072"), card("6117543"))),
            ), listOf(card("3496072"), card("6117543"))),
            Pair(listOf(
                AdvisorLocationHistoryMessage("USER", "mamy 6117543 na stanie?"),
                AdvisorLocationHistoryMessage("ASSISTANT", "Nie udało się sprawdzić"),
            ), emptyList()),
            Pair(listOf(
                AdvisorLocationHistoryMessage("USER", "mamy 6117543 na stanie?"),
                AdvisorLocationHistoryMessage("ASSISTANT", "inny provider",
                    listOf(card("6117543").copy(providerId = "kwant-pl"))),
            ), listOf(card("6117543").copy(providerId = "kwant-pl"))),
            Pair(history() + listOf(
                AdvisorLocationHistoryMessage("USER", "jaki mamy dziś plan urlopów?"),
                AdvisorLocationHistoryMessage("ASSISTANT", "Nie sprawdzono żadnego produktu"),
            ), listOf(card("3496072"), card("6117543"))),
        )
        for ((previous, products) in cases) {
            val r = execute(history = previous, cards = products)
            assertTrue(previous.toString(), r.calls.isEmpty())
            assertTrue(r.state.products.isEmpty())
            assertFalse(r.state.text.contains("18 sztuk"))
        }
    }

    @Test fun `R2-F2 deictic market needs explicit bounded USER market selection`() = runBlocking {
        val withoutSelectedMarket = execute(user = "ile tego mają w tym markecie?")
        assertTrue(withoutSelectedMarket.calls.isEmpty())
        assertTrue(withoutSelectedMarket.state.products.isEmpty())

        val selectedMarketHistory = history() + listOf(
            AdvisorLocationHistoryMessage("USER", "a w OBI 003?"),
            AdvisorLocationHistoryMessage("ASSISTANT", "6117543: świeżo zweryfikowane w OBI 003",
                listOf(card("6117543", "003"))),
        )
        val withSelection = execute(
            user = "ile tego mają w tym markecie?",
            history = selectedMarketHistory,
            cards = listOf(card("3496072"), card("6117543"), card("6117543", "003")),
        )
        assertEquals(listOf("obi:6117543@003"), withSelection.calls)
        assertEquals("6117543", withSelection.state.products.single().effectiveProductId)
    }

    @Test fun `R2-F6 one-off market switching 003 to 019 to 003 stays USER-authorized`() = runBlocking {
        for ((q, branch) in listOf(
            "a w OBI 003?" to "003",
            "a w OBI 019?" to "019",
            "a na Wielickiej?" to "003",
        )) {
            val r = execute(user = q, proposed = "3496072")
            assertEquals(q, listOf("obi:6117543@$branch"), r.calls)
            assertEquals(q, branch, r.state.products.single().effectiveBranchId)
        }
    }

    @Test fun `R2-F5 conflicting or unknown market never falls back to active profile`() = runBlocking {
        for (q in listOf("a ile mają w OBI 075 na Wielickiej?", "a ile mają w OBI 999?")) {
            val r = execute(user = q)
            assertTrue(q, r.calls.isEmpty())
            assertTrue(q, r.state.products.isEmpty())
        }
    }

    @Test fun `new explicit user OBIK overrides old selection and wrong provider is still denied`() = runBlocking {
        val fresh = execute(user = "Mamy 3496072 na stanie?", proposed = "3496072")
        assertEquals(listOf("obi:3496072@075"), fresh.calls)
        val crossProvider = execute(proposed = "6117543", provider = "kwant-pl")
        assertTrue(crossProvider.calls.isEmpty())
        assertTrue(crossProvider.state.products.isEmpty())
    }
}
