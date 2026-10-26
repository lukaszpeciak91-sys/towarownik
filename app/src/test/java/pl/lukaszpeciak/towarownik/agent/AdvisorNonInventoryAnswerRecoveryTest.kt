package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult

/**
 * R2 investigation checkpoint on production Controller orchestration.
 * Synthetic model tool choice and answer, synthetic provider data. NO live model,
 * attachment bytes, HTTP, or manufacturer verification.
 *
 * These tests intentionally include a hostile final answer on a rejected
 * provider operation: clearing productRefs alone CANNOT make free prose safe.
 */
class AdvisorNonInventoryAnswerRecoveryTest {
    private val kwantBranches = listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz"),
        ProviderBranch(BranchId("204"), "Tarnów"),
        ProviderBranch(BranchId("203"), "Bochnia"),
    )
    private val obiBranches = listOf(
        ProviderBranch(BranchId("075"), "Nowy Sącz", "ul. Tarnowska 125"),
        ProviderBranch(BranchId("003"), "Kraków", "ul. Wielicka 259"),
    )

    private data class Observation(
        val state: AdvisorUiState.Success,
        val localLookups: Int,
        val rejections: Int,
        val continuations: Int,
    )

    private suspend fun rejectedDiscovery(
        user: String,
        provider: String = "kwant-pl",
        branch: String = if (provider == "kwant-pl") "205" else "075",
        modelToolProvider: String = provider,
        modelToolBranch: String = branch,
        modelText: String,
    ): Observation {
        var localLookups = 0
        var rejections = 0
        var continuations = 0
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { _, receivedProvider, receivedBranch ->
                assertEquals(provider, receivedProvider)
                assertEquals(branch, receivedBranch)
                AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
                    responseId = "response_tool", callId = "call_tool",
                    arguments = AdvisorToolArguments(
                        providerId = modelToolProvider,
                        storeNumber = modelToolBranch,
                        queries = listOf(AdvisorToolQuery("Karlik MINI taupe", 1)),
                    ),
                ))
            },
            messageAgent = { _, _, _, _ -> error("Unexpected second user message") },
            continueAgent = { responseId, callId, receivedProvider, receivedBranch, _, result ->
                assertEquals("response_tool", responseId)
                assertEquals("call_tool", callId)
                assertEquals(provider, receivedProvider)
                assertEquals(branch, receivedBranch)
                continuations++
                assertTrue(result is AdvisorToolContinuation.RejectedStore)
                rejections++
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                    responseId = "response_final", text = modelText,
                    productRefs = listOf(AdvisorProductRef(branch, "9999999", provider)),
                ))
            },
            executeObiTool = {
                localLookups++
                error("Rejected provider tool must not execute HTTP")
            },
            executeProviderTool = {
                localLookups++
                error("Rejected provider tool must not execute HTTP")
            },
            branchDirectory = {
                ProviderBranchResult.Available(if (provider == "kwant-pl") kwantBranches else obiBranches)
            },
        )
        val state = controller.runTurn(user, null, branch, provider) { }
        assertTrue(state is AdvisorUiState.Success)
        return Observation(state as AdvisorUiState.Success, localLookups, rejections, continuations)
    }

    @Test fun `R2 A explicit internet research survives erroneous local discovery rejection`() = runBlocking {
        val user = "Znajdź mi w internecie kody katalogowe Karlik MINI taupe na podstawie załączonych zdjęć. Nie pytam o stan magazynowy."
        val safe = "Nie widzę zawartości zdjęć w tej próbie. Do ustalenia kodów potrzebne są oznaczenia lub katalog producenta; nie sprawdzałam stanów."
        val result = rejectedDiscovery(user = user, modelText = safe)
        assertEquals(0, result.localLookups)
        assertEquals(1, result.rejections)
        assertEquals(1, result.continuations)
        assertTrue(result.state.products.isEmpty())
        assertEquals("response_final", result.state.responseId)
        assertEquals(safe, result.state.text)
    }

    @Test fun `R2 B quotation comparison not overwritten after unnecessary wrong provider tool`() = runBlocking {
        val user = "Porównaj kody i ilości z mojej oferty. Nie sprawdzaj stanów magazynowych."
        val safe = "Porównam pozycje według kodu, wariantu i ilości. Do wykazania różnic potrzebuję treści oferty; nie odczytywałam magazynu."
        val result = rejectedDiscovery(user = user, modelToolProvider = "obi-pl", modelText = safe)
        assertEquals(0, result.localLookups)
        assertEquals(1, result.rejections)
        assertTrue(result.state.products.isEmpty())
        assertEquals(safe, result.state.text)
    }

    @Test fun `R2 C unknown inventory location retains deterministic refusal against fake stock`() = runBlocking {
        val user = "Sprawdź stan produktu w nieznanym oddziale Zakopane."
        val unsafe = "W oddziale Zakopane dostępne są 17 sztuk produktu."
        val result = rejectedDiscovery(user = user, modelText = unsafe)
        assertEquals(0, result.localLookups)
        assertEquals(1, result.rejections)
        assertTrue(result.state.products.isEmpty())
        assertEquals(renderAdvisorLocalFailure("unknown_location"), result.state.text)
    }

    @Test fun `R2 D wrong provider and contradictory OBI market stay fail closed`() = runBlocking {
        val wrong = rejectedDiscovery(
            user = "Sprawdź aktualną cenę i stan produktu w naszym KWANT.",
            modelToolProvider = "obi-pl", modelText = "Jest 18 sztuk w KWANT 205.",
        )
        assertEquals(0, wrong.localLookups)
        assertTrue(wrong.state.products.isEmpty())
        assertEquals(renderAdvisorLocalFailure("wrong_provider"), wrong.state.text)
        val contradict = rejectedDiscovery(
            user = "Sprawdź produkt w OBI 075 na Wielickiej",
            provider = "obi-pl", branch = "075", modelText = "OBI 003 ma 23 sztuki.",
        )
        assertEquals(0, contradict.localLookups)
        assertTrue(contradict.state.products.isEmpty())
        assertEquals(renderAdvisorLocalFailure("unknown_location"), contradict.state.text)
    }

    @Test fun `R2 E rejected lookup does not contaminate next independent user turn`() = runBlocking {
        var localLookups = 0
        var rejectedCalls = 0
        val trusted = snapshot(stock = 12)
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { _, _, _ ->
                AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
                    "response_tool1", "call_tool1",
                    AdvisorToolArguments("205", listOf(AdvisorToolQuery("Karlik", 1)), "obi-pl"),
                ))
            },
            messageAgent = { previous, text, provider, branch ->
                assertEquals("response_answer1", previous)
                assertEquals("kwant-pl", provider)
                assertEquals("205", branch)
                assertEquals("Sprawdź MBN116E u nas", text)
                AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
                    "response_tool2", "call_tool2",
                    AdvisorToolArguments("205", listOf(AdvisorToolQuery("MBN116E", 1)), "kwant-pl"),
                ))
            },
            continueAgent = { _, _, _, _, _, output ->
                when (output) {
                    is AdvisorToolContinuation.RejectedStore -> {
                        rejectedCalls++
                        AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                            "response_answer1", "Odpowiedź po odmowie", emptyList(),
                        ))
                    }
                    is AdvisorToolContinuation.Verified ->
                        AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                            "response_answer2", "Potwierdzono produkt i stan 12 szt.",
                            listOf(AdvisorProductRef("205", "98765", "kwant-pl")),
                        ))
                    else -> error("Unexpected continuation")
                }
            },
            executeObiTool = { error("Wrong provider cannot execute") },
            executeProviderTool = {
                localLookups++
                AdvisorToolExecutionResult.Success(
                    AdvisorVerifiedToolResult(
                        storeNumber = "205", providerId = "kwant-pl",
                        results = listOf(AdvisorVerifiedQueryResult(
                            "MBN116E", AdvisorQueryResultStatus.VERIFIED,
                            listOf(AdvisorVerifiedProduct(
                                obik = "98765", productId = "98765",
                                articleNumber = "MBN116E/HAG", name = "Wyłącznik",
                                stock = 12, price = BigDecimal("15.50"),
                            )),
                        )),
                    ), listOf(trusted),
                )
            },
            branchDirectory = { ProviderBranchResult.Available(kwantBranches) },
        )
        val first = controller.runTurn("Porównaj ofertę", null, "205", "kwant-pl") { } as AdvisorUiState.Success
        val second = controller.runTurn("Sprawdź MBN116E u nas", first.responseId, "205", "kwant-pl") { } as AdvisorUiState.Success
        assertEquals(1, rejectedCalls)
        assertEquals(1, localLookups)
        assertTrue(first.products.isEmpty())
        assertEquals(listOf(trusted), second.products)
        assertEquals("response_answer2", second.responseId)
    }

    @Test fun `R2 F normal verified success returns current-turn cards including zero and null`() = runBlocking {
        for (stock in listOf(0, null, 12)) {
            val trusted = snapshot(stock)
            var localLookups = 0
            val controller = AdvisorController(
                isConfigured = { true },
                startAgent = { _, _, _ ->
                    AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
                        "response_tool", "call_tool",
                        AdvisorToolArguments("205", listOf(AdvisorToolQuery("MBN116E", 1)), "kwant-pl"),
                    ))
                },
                messageAgent = { _, _, _, _ -> error("Unexpected MESSAGE") },
                continueAgent = { _, _, _, _, _, result ->
                    assertTrue(result is AdvisorToolContinuation.Verified)
                    AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                        "response_answer", "Zweryfikowano produkt.",
                        listOf(AdvisorProductRef("205", "98765", "kwant-pl")),
                    ))
                },
                executeObiTool = { error("Wrong provider") },
                executeProviderTool = {
                    localLookups++
                    AdvisorToolExecutionResult.Success(
                        AdvisorVerifiedToolResult(
                            "205", listOf(AdvisorVerifiedQueryResult(
                                "MBN116E", AdvisorQueryResultStatus.VERIFIED,
                                listOf(AdvisorVerifiedProduct(
                                    "98765", "Wyłącznik", stock, price = BigDecimal("15.50"),
                                    productId = "98765", articleNumber = "MBN116E/HAG",
                                )),
                            )), "kwant-pl",
                        ), listOf(trusted),
                    )
                },
                branchDirectory = { ProviderBranchResult.Available(kwantBranches) },
            )
            val output = controller.runTurn("Sprawdź MBN116E u nas", null, "205", "kwant-pl") { } as AdvisorUiState.Success
            assertEquals(1, localLookups)
            assertEquals(listOf(trusted), output.products)
        }
    }

    @Test fun `R2 G explicit noninventory request must not release fabricated stock in model prose`() = runBlocking {
        val user = "Znajdź mi w internecie kody katalogowe Karlik MINI taupe na podstawie załączonych zdjęć. Nie pytam o stan magazynowy."
        val unsafe = "W KWANT 205 jest dokładnie 300 sztuk; potwierdziłam dostępność."
        val result = rejectedDiscovery(user = user, modelText = unsafe)
        assertEquals(0, result.localLookups)
        assertTrue(result.state.products.isEmpty())
        assertEquals(renderAdvisorLocalFailure("unknown_location"), result.state.text)
    }

    private fun snapshot(stock: Int?) = VerifiedProductSnapshot(
        obik = "98765", productId = "98765", providerId = "kwant-pl",
        storeNumber = "205", branchId = "205", name = "Wyłącznik",
        stock = stock, grossPrice = BigDecimal("15.50"),
        productUrl = "https://offline.invalid/product", verifiedAt = 1000L,
        articleNumber = "MBN116E/HAG",
    )
}
