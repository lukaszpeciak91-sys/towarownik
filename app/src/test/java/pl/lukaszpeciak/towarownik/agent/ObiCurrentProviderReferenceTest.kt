package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.diagnostics.DiagnosticStage
import pl.lukaszpeciak.towarownik.diagnostics.SafeDiagnosticEvent
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.OBI_STORES
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchCandidate
import pl.lukaszpeciak.towarownik.product.ProductSearchResult
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.BranchResolution
import pl.lukaszpeciak.towarownik.product.provider.BranchResolver
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult

private const val RE02_INPUT = "Znajdź mi w naszym OBI kompakt WC Cersanit Kier II z odpływem poziomym. Sprawdź aktualną cenę i stan w naszym markecie."
private const val RE02_QUERY = "Cersanit Kier II"
private const val RE02_PRODUCT = "7000001"

/** Scripted model plans and synthetic product facts; never live provider/model calls. */
class ObiCurrentProviderReferenceTest {
    private val branches = OBI_STORES.map {
        ProviderBranch(BranchId(it.storeNumber), it.city, it.address)
    }

    private data class Observation(
        val state: AdvisorUiState,
        val executions: List<AdvisorToolArguments>,
        val lookups: List<Pair<String, String>>,
        val reasons: List<String?>,
        val continuation: AdvisorToolContinuation?,
    )

    private suspend fun observe(
        input: String,
        hint: String = "075",
        toolProvider: String = "obi-pl",
        directory: List<ProviderBranch> = branches,
        stock: Int? = 9,
    ): Observation {
        val executions = mutableListOf<AdvisorToolArguments>()
        val lookups = mutableListOf<Pair<String, String>>()
        val events = mutableListOf<SafeDiagnosticEvent>()
        var continuation: AdvisorToolContinuation? = null
        val local = FindObiProductsTool(
            searchProducts = { query ->
                assertEquals(RE02_QUERY, query)
                ProductSearchResult.Candidates(listOf(ProductSearchCandidate(RE02_PRODUCT, "Synthetic")))
            },
            lookupObik = { id, branch ->
                lookups += id to branch
                ProductLookupResult.Found(LocalProduct(
                    obik = id, name = "Synthetic product", stock = stock,
                    grossPrice = BigDecimal.ONE, productUrl = "https://offline.invalid/product",
                    ean = null, storeNumber = branch,
                ))
            },
            now = { 1234L },
        )
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { message, provider, branch ->
                assertEquals(input, message)
                assertEquals("obi-pl", provider)
                assertEquals("075", branch)
                AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
                    "resp_re02_tool", "call_re02",
                    AdvisorToolArguments(hint, listOf(AdvisorToolQuery(RE02_QUERY, 1)), toolProvider),
                ))
            },
            messageAgent = { _, _, _, _ -> error("RE02 is a new conversation") },
            continueAgent = { response, call, provider, branch, contract, result ->
                assertEquals("resp_re02_tool", response)
                assertEquals("call_re02", call)
                assertEquals("obi-pl", provider)
                assertEquals("075", branch) // One-off execution never mutates WorkingProfile.
                assertEquals(AdvisorTransportContract.OBI_V2, contract)
                continuation = result
                val refs = if (result is AdvisorToolContinuation.Verified) {
                    listOf(AdvisorProductRef(result.result.storeNumber, RE02_PRODUCT))
                } else emptyList()
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_re02_final", "Synthetic final", refs))
            },
            executeObiTool = { args -> executions += args; local.execute(args) },
            executeProviderTool = { error("OBI text must use its existing OBI executor") },
            branchDirectory = { ProviderBranchResult.Available(directory) },
        )
        val state = controller.runTurn(input, null, "075", "obi-pl", onDiagnosticEvent = { events += it }) { }
        return Observation(state, executions, lookups,
            events.filter { it.stage == DiagnosticStage.TOOL_RESULT }.map { it.reason }, continuation)
    }

    private fun assertVerified(observation: Observation, branch: String = "075", stock: Int? = 9) {
        assertEquals("One authorized OBI product execution", 1, observation.executions.size)
        assertEquals(listOf(RE02_PRODUCT to branch), observation.lookups)
        assertEquals(listOf(AdvisorToolQuery(RE02_QUERY, 1)), observation.executions.single().queries)
        assertEquals(branch, observation.executions.single().storeNumber)
        assertEquals(listOf<String?>(null), observation.reasons)
        assertTrue(observation.continuation is AdvisorToolContinuation.Verified)
        val state = observation.state as AdvisorUiState.Success
        assertEquals("Synthetic final", state.text)
        assertEquals(1, state.products.size)
        assertEquals(branch, state.products.single().effectiveBranchId)
        assertEquals(stock, state.products.single().stock)
    }

    private fun assertDenied(input: String, observation: Observation) {
        assertEquals(input, emptyList<AdvisorToolArguments>(), observation.executions)
        assertTrue(input, observation.lookups.isEmpty())
        assertTrue(input, observation.continuation is AdvisorToolContinuation.RejectedStore)
        assertTrue(input, (observation.state as AdvisorUiState.Success).products.isEmpty())
    }

    @Test fun `RE02 exact USER admits one current market lookup`() = runBlocking {
        assertVerified(observe(RE02_INPUT))
    }

    @Test fun `RE02 redundant current market tail is optional`() = runBlocking {
        assertVerified(observe(RE02_INPUT.replace(" w naszym markecie", "")))
    }

    @Test fun `RE02 removal controls keep current market`() = runBlocking {
        assertVerified(observe(RE02_INPUT.replace(" w naszym OBI", "")))
        assertVerified(observe(RE02_INPUT.replace(" w naszym OBI", "").replace(" w naszym markecie", "")))
    }

    @Test fun `RE02 complete current provider phrase remains independent of model branch hints`() = runBlocking {
        for (hint in listOf("075", "003", "999")) assertVerified(observe(RE02_INPUT, hint = hint))
    }

    @Test fun `RE02 current provider reference is case insensitive and repeatable`() = runBlocking {
        for (input in listOf(
            RE02_INPUT.replace("w naszym OBI", "W NASZYM OBI"),
            "Znajdź mi w naszym OBI Cersanit Kier II. Sprawdź cenę w naszym OBI.",
        )) assertVerified(observe(input))
    }

    @Test fun `RE02 explicit locations outside the phrase cannot be hidden`() = runBlocking {
        for (place in listOf(
            "w Zakopanem", "w zakopanem", "w OBI 999", "w OBI 003",
            "w OBI Kraków na Wielickiej", "w OBI 075 na Wielickiej w Krakowie",
            "w OBI Nowy Sącz 075 i w Zakopanem", "w OBI 003 i 999",
        )) {
            val input = "Znajdź mi w naszym OBI Cersanit Kier II i sprawdź stan $place."
            assertDenied(input, observe(input))
        }
    }

    @Test fun `RE02 adjoining numeric and canonical place qualifiers retain validation`() = runBlocking {
        for (tail in listOf("003", "999", "Kraków", "Kraków Wielicka", "na Wielickiej")) {
            val input = "Znajdź mi w naszym OBI $tail"
            assertDenied(input, observe(input))
        }
    }

    @Test fun `RE02 explicit standalone other market remains authorized without profile mutation`() = runBlocking {
        assertVerified(observe("Znajdź mi Cersanit Kier II. Sprawdź cenę i stan w OBI 003."), "003")
        assertVerified(observe("Znajdź mi Cersanit Kier II. Sprawdź cenę i stan w OBI Nowy Sącz 075."))
    }

    @Test fun `RE02 wrong provider and invalid directories never execute products`() = runBlocking {
        val wrong = observe(RE02_INPUT, toolProvider = "kwant-pl")
        assertDenied(RE02_INPUT, wrong)
        assertEquals(listOf("wrong_provider"), wrong.reasons)
        val missingCurrent = observe(RE02_INPUT, directory = branches.filter { it.branchId.value != "075" })
        assertDenied(RE02_INPUT, missingCurrent)
        assertEquals(listOf("directory_unavailable"), missingCurrent.reasons)
        assertTrue(observe(RE02_INPUT, directory = emptyList()).executions.isEmpty())
    }

    @Test fun `RE02 positive zero and unknown stock stay distinct`() = runBlocking {
        for (stock in listOf(9, 0, null)) assertVerified(observe(RE02_INPUT, stock = stock), stock = stock)
    }

    @Test fun `RE02 change is discovery only and does not repair unrelated web phrasing`() {
        assertEquals(BranchResolution.UnknownMention, BranchResolver.resolve(RE02_INPUT, branches, BranchId("075")))
        assertEquals(BranchResolution.UnknownMention,
            BranchResolver.resolveDiscovery("Znajdź w sieci kody producenta", branches, BranchId("075"), true))
    }
}
