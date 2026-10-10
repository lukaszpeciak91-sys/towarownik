package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ObiProductProvider
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult

/** Offline orchestration: synthetic IDs and quantities, no upstream or provider HTTP. */
class AdvisorLocationsControllerTest {
    private val ref = AdvisorLocationArguments("obi-pl", "3496072", listOf("075"))
    private val snapshot = VerifiedProductSnapshot(
        obik = "3496072", productId = "3496072", name = "Synthetic",
        stock = 1, grossPrice = null,
        productUrl = "https://www.obi.pl/p/3496072",
        verifiedAt = 123L, storeNumber = "075",
    )
    private val evidence = AdvisorLocationEvidence(
        providerId = "obi-pl", productId = "3496072",
        status = "verified", reason = null,
        coverage = "requested_subset",
        checkedIds = listOf("075"), returnedIds = listOf("075"),
        missingIds = emptyList(),
        locations = listOf(AdvisorLocationEntry("075", "Nowy Sącz", 0)),
        verifiedAtMillis = 345L, centralStock = null,
    )
    private fun discovery(): AdvisorProxyCallResult.Success =
        AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
            responseId = "resp_discover",
            callId = "call_discover",
            arguments = AdvisorToolArguments(
                storeNumber = "075",
                queries = listOf(AdvisorToolQuery("3496072", 1)),
            ),
        ))
    private fun location(n: Int = 1): AdvisorProxyCallResult.Success =
        AdvisorProxyCallResult.Success(AdvisorProxyResult.LocationToolRequest(
            responseId = "resp_locations_$n",
            callId = "call_locations_$n",
            arguments = ref,
        ))
    private fun answer(): AdvisorProxyCallResult.Success =
        AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
            responseId = "resp_final",
            text = "Sprawdziłam stan w OBI 075: 0 szt.",
            productRefs = emptyList(),
        ))
    private fun verified() = AdvisorToolExecutionResult.Success(
        result = AdvisorVerifiedToolResult(
            storeNumber = "075",
            results = listOf(AdvisorVerifiedQueryResult(
                "3496072", AdvisorQueryResultStatus.VERIFIED,
                listOf(AdvisorVerifiedProduct(
                    obik = "3496072", name = "Synthetic",
                    stock = 1, price = null,
                )),
            )),
        ),
        snapshots = listOf(snapshot),
    )
    private fun controller(
        initial: AdvisorProxyCallResult,
        continueCall: suspend (AdvisorToolContinuation) -> AdvisorProxyCallResult,
        locationCall: suspend (
            AdvisorLocationArguments, String, String, String,
            Collection<VerifiedProductSnapshot>, List<VerifiedProductSnapshot>,
        ) -> AdvisorLocationEvidence,
        onDiscovery: () -> AdvisorToolExecutionResult = { verified() },
    ) = AdvisorController(
        isConfigured = { true },
        startAgent = { _, _, _ -> initial },
        messageAgent = { _, _, _, _ -> initial },
        continueAgent = { _, _, _, _, _, continuation -> continueCall(continuation) },
        executeObiTool = { onDiscovery() },
        executeProviderTool = { onDiscovery() },
        executeLocationsTool = locationCall,
        branchDirectory = { ObiProductProvider().branches() },
    )

    @Test fun `discovery then locations uses two local calls and historical card not promoted`() = runBlocking {
        var count = 0
        var trustedCurrent = emptyList<VerifiedProductSnapshot>()
        val c = controller(
            initial = discovery(),
            continueCall = { output ->
                count++
                when (count) {
                    1 -> {
                        assertTrue(output is AdvisorToolContinuation.Verified)
                        location()
                    }
                    2 -> {
                        assertEquals(evidence, (output as AdvisorToolContinuation.Locations).evidence)
                        answer()
                    }
                    else -> error("Unexpected tool")
                }
            },
            locationCall = { _, _, _, _, current, _ ->
                trustedCurrent = current.toList()
                evidence
            },
        )
        val state = c.runTurn(
            input = "Sprawdź 3496072 w OBI 075",
            previousResponseId = null,
        ) {}
        assertTrue(state is AdvisorUiState.Success)
        assertEquals(2, count)
        assertEquals(listOf(snapshot), trustedCurrent)
        assertTrue((state as AdvisorUiState.Success).products.isEmpty())
    }

    @Test fun `next-turn single historical assistant product works without new discovery`() = runBlocking {
        var called = 0
        val c = controller(
            initial = location(),
            continueCall = { output ->
                assertTrue(output is AdvisorToolContinuation.Locations)
                answer()
            },
            locationCall = { args, provider, selected, input, current, past ->
                assertEquals("075", selected)
                called++
                assertEquals(ref, args)
                assertEquals("obi-pl", provider)
                assertEquals("Gdzie jest ten produkt w OBI 075?", input)
                assertTrue(current.isEmpty())
                assertEquals(listOf(snapshot), past)
                evidence
            },
            onDiscovery = { error("No automatic discovery") },
        )
        val state = c.runTurn(
            input = "Gdzie jest ten produkt w OBI 075?",
            previousResponseId = "resp_prior",
            historicalVerifiedProducts = listOf(snapshot),
        ) {}
        assertTrue(state is AdvisorUiState.Success)
        assertEquals(1, called)
    }

    @Test fun `fourth local request reaches limit and cannot fetch more inventory`() = runBlocking {
        var continued = 0
        var discoveries = 0
        var locations = 0
        val c = controller(
            initial = discovery(),
            continueCall = { output ->
                continued++
                when (continued) {
                    1, 2 -> discovery()
                    3 -> location()
                    4 -> {
                        val limit = (output as AdvisorToolContinuation.Locations).evidence
                        assertEquals("rejected", limit.status)
                        assertEquals("local_tool_limit_reached", limit.reason)
                        answer()
                    }
                    else -> error("Extra tool")
                }
            },
            locationCall = { _, _, _, _, _, _ ->
                locations++
                evidence
            },
            onDiscovery = {
                discoveries++
                verified()
            },
        )
        assertTrue(c.runTurn(input = "Wiele lokalnych wywołań", previousResponseId = null) {} is AdvisorUiState.Success)
        assertEquals(3, discoveries)
        assertEquals(0, locations)
        assertEquals(4, continued)
    }

    @Test fun `ordinary advisor answer never triggers locations service`() = runBlocking {
        var locations = 0
        val c = controller(
            initial = answer(),
            continueCall = { error("No tool") },
            locationCall = { _, _, _, _, _, _ -> locations++; evidence },
            onDiscovery = { error("No discovery") },
        )
        assertTrue(c.runTurn(input = "Jak dobrać kabel?", previousResponseId = null) {} is AdvisorUiState.Success)
        assertEquals(0, locations)
    }

    @Test fun reportB_bothClarificationReturnsKwantUnavailableWithoutAnyInventoryCall() = runBlocking {
        val kwantOne = snapshot.copy(
            obik = "7035", productId = "7035", providerId = "kwant-pl",
            articleNumber = "921871", storeNumber = "205", branchId = "205",
        )
        val kwantTwo = kwantOne.copy(
            obik = "7027", productId = "7027", articleNumber = "921861",
        )
        val prior = listOf(
            AdvisorLocationHistoryMessage("ASSISTANT", "Two cards", listOf(kwantOne, kwantTwo)),
            AdvisorLocationHistoryMessage(
                "USER", "a jaki jest stan tego gniazda 16 na 4 w oddziale w Zamościu?",
            ),
            AdvisorLocationHistoryMessage("ASSISTANT", "Którego produktu?"),
            AdvisorLocationHistoryMessage("USER", "obu"),
            AdvisorLocationHistoryMessage("ASSISTANT", "Który oddział?"),
        )
        var inventoryCalls = 0
        val response = AdvisorProxyCallResult.Success(
            AdvisorProxyResult.LocationToolRequest(
                responseId = "resp_kwant",
                callId = "call_kwant",
                arguments = AdvisorLocationArguments(
                    "kwant-pl", "7035", listOf("Zamość"),
                ),
            ),
        )
        val c = controller(
            initial = response,
            continueCall = { output ->
                val e = (output as AdvisorToolContinuation.Locations).evidence
                assertEquals("unavailable", e.status)
                assertEquals("unverified_request_contract", e.reason)
                assertTrue(e.productId == null)
                answer()
            },
            locationCall = { _, _, _, _, _, _ ->
                inventoryCalls++
                error("KWANT extended must remain disabled")
            },
        )
        val result = c.runTurn(
            input = "Zamość",
            previousResponseId = "resp_prior",
            conversationProviderId = "kwant-pl",
            conversationStoreNumber = "205",
            historicalVerifiedProducts = listOf(kwantOne, kwantTwo),
            locationHistory = prior,
        ) {}
        assertTrue(result is AdvisorUiState.Success)
        assertEquals(0, inventoryCalls)
    }

    @Test fun repeatedDeterministicLocationRejectionDoesNotConsumeSecondToolCall() = runBlocking {
        var requested = 0
        var continuations = 0
        val c = controller(
            initial = location(),
            continueCall = { output ->
                continuations++
                val rejected = (output as AdvisorToolContinuation.Locations).evidence
                assertEquals("unknown_location", rejected.reason)
                if (continuations == 1) location(2) else answer()
            },
            locationCall = { _, _, _, _, _, _ ->
                requested++
                AdvisorLocationEvidence(
                    providerId = "obi-pl",
                    productId = null,
                    status = "rejected",
                    reason = "unknown_location",
                    coverage = "unknown",
                    checkedIds = emptyList(),
                    returnedIds = emptyList(),
                    missingIds = emptyList(),
                    locations = emptyList(),
                    verifiedAtMillis = null,
                    centralStock = null,
                )
            },
        )
        val result = c.runTurn(
            input = "Sprawdź w Tarnowie",
            previousResponseId = "previous",
            historicalVerifiedProducts = listOf(snapshot),
        ) {}
        assertTrue(result is AdvisorUiState.Success)
        assertEquals(1, requested)
        assertEquals(2, continuations)
    }



    @Test fun workerCompatibleEnvelopeUsesBoundedHistoryAndPreservesTrace() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setHeader(
                        "X-Taksula-Trace-Id",
                        "33333333-3333-4333-8333-333333333333",
                    )
                    .setBody(
                        """
                        {
                          "type":"tool_request",
                          "responseId":"resp_stock",
                          "tool":{
                            "name":"find_product_locations",
                            "callId":"call_stock",
                            "arguments":{
                              "providerId":"obi-pl",
                              "productId":"6117543",
                              "locations":["Miejscu Piastowym"]
                            }
                          },
                          "webSearchCalls":0
                        }
                        """.trimIndent(),
                    ),
            )
            val proxy = AdvisorProxyClient(
                appToken = "test-token",
                baseUrl = server.url("/"),
            )
            val parsed = proxy.start(
                message = "a w Miejscu Piastowym?",
                providerId = "obi-pl",
                branchId = "075",
            )
            assertTrue(parsed is AdvisorProxyCallResult.Success)
            val product = snapshot.copy(
                obik = "6117543", productId = "6117543",
            )
            val old = snapshot.copy(
                obik = "5524079", productId = "5524079",
            )
            var locationCalls = 0
            val c = controller(
                initial = parsed,
                continueCall = { continuation ->
                    val evidence = (continuation as AdvisorToolContinuation.Locations).evidence
                    assertEquals("6117543", evidence.productId)
                    assertEquals("verified", evidence.status)
                    assertEquals(listOf("052"), evidence.checkedIds)
                    answer()
                },
                locationCall = { args, provider, selected, input, current, past ->
                    locationCalls++
                    assertEquals("6117543", args.productId)
                    assertEquals("obi-pl", provider)
                    assertEquals("075", selected)
                    assertTrue(input.startsWith("Sprawdź stan produktu:"))
                    assertTrue(current.isEmpty())
                    assertEquals(listOf(product), past)
                    AdvisorLocationEvidence(
                        providerId = "obi-pl",
                        productId = "6117543",
                        status = "verified",
                        reason = null,
                        coverage = "requested_subset",
                        checkedIds = listOf("052"),
                        returnedIds = listOf("052"),
                        missingIds = emptyList(),
                        locations = listOf(
                            AdvisorLocationEntry("052", "Miejsce Piastowe", 4),
                        ),
                        verifiedAtMillis = 123L,
                        centralStock = null,
                    )
                },
            )
            val final = c.runTurn(
                input = "a w Miejscu Piastowym?",
                previousResponseId = "resp_prior",
                historicalVerifiedProducts = listOf(product, old),
                locationHistory = listOf(
                    AdvisorLocationHistoryMessage(
                        "ASSISTANT", "Two verified products", listOf(product, old),
                    ),
                    AdvisorLocationHistoryMessage(
                        "USER", "Sprawdź OBIK 6117543 w market OBI 003",
                    ),
                    AdvisorLocationHistoryMessage(
                        "ASSISTANT", "Verified selected", listOf(product),
                    ),
                ),
            ) {}
            assertTrue(final is AdvisorUiState.Success)
            assertEquals(1, locationCalls)
            assertEquals(
                "33333333-3333-4333-8333-333333333333",
                (final as AdvisorUiState.Success).traceId,
            )
        }
    }


}
