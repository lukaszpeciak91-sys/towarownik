package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.KwantProductLocationsAdapter
import pl.lukaszpeciak.towarownik.product.provider.LocationsHttpFetcher
import pl.lukaszpeciak.towarownik.product.provider.LocationFailure
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ObiProductProvider
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult
import pl.lukaszpeciak.towarownik.product.provider.LocationCoverage
import pl.lukaszpeciak.towarownik.product.provider.LocationCoverageKind
import pl.lukaszpeciak.towarownik.product.provider.LocationStock
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsAdapter
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsResult
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsService
import pl.lukaszpeciak.towarownik.product.provider.ProductRef
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct

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
        onProviderDiscovery: (AdvisorToolArguments) -> AdvisorToolExecutionResult = {
            onDiscovery()
        },
        kwantBranches: List<ProviderBranch> = listOf(
            ProviderBranch(BranchId("205"), "Nowy Sącz"),
            ProviderBranch(BranchId("128"), "Zamość"),
            ProviderBranch(BranchId("204"), "Tarnów"),
        ),
    ) = AdvisorController(
        isConfigured = { true },
        startAgent = { _, _, _ -> initial },
        messageAgent = { _, _, _, _ -> initial },
        continueAgent = { _, _, _, _, _, continuation -> continueCall(continuation) },
        executeObiTool = { onDiscovery() },
        executeProviderTool = { onProviderDiscovery(it) },
        executeLocationsTool = locationCall,
        branchDirectory = { provider ->
            if (provider.value == "kwant-pl") {
                ProviderBranchResult.Available(kwantBranches)
            } else {
                ObiProductProvider().branches()
            }
        },
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
                assertEquals("multi_product_tool_contract", e.reason)
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
                assertEquals(
                    if (continuations == 4) "local_tool_limit_reached" else "unknown_location",
                    rejected.reason,
                )
                when (continuations) {
                    1 -> location(2)
                    2 -> location(3)
                    3 -> location(4)
                    else -> answer()
                }
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
        assertEquals(1, requested) // No repeated inventory/adapter work.
        assertEquals(4, continuations) // Fourth logical model tool hits the hard cap.
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
                storeNumber = "003", branchId = "003",
            )
            val old = snapshot.copy(
                obik = "5524079", productId = "5524079",
                storeNumber = "003", branchId = "003",
            )
            var locationCalls = 0
            var inventoryReads = 0
            val stockAdapter = object : ProductLocationsAdapter {
                override val providerId = OBI_PROVIDER_ID
                override suspend fun read(
                    ref: ProductRef,
                    requested: List<BranchId>,
                    trustedProduct: ProviderProduct?,
                ): ProductLocationsResult {
                    inventoryReads++
                    assertEquals(listOf(BranchId("052")), requested)
                    assertEquals("6117543", ref.productId)
                    return ProductLocationsResult.Available(
                        ref,
                        requested.map { LocationStock(
                            ProviderBranch(it, "Miejsce Piastowe"), 4,
                        ) },
                        LocationCoverage(
                            LocationCoverageKind.REQUESTED_SUBSET,
                            requested, requested, 1,
                        ),
                        123L,
                    )
                }
            }
            val realTool = AdvisorLocationsTool(
                locationsService = ProductLocationsService(listOf(stockAdapter)),
            )
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
                    realTool.execute(args, provider, selected, input, current, past)
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
            assertEquals(1, inventoryReads)
            assertEquals(
                "33333333-3333-4333-8333-333333333333",
                (final as AdvisorUiState.Success).traceId,
            )
        }
    }



    @Test fun untrustedLocationRequestMustBeReevaluatedAfterDiscoveryInSameTurn() = runBlocking {
        var locationCalls = 0
        var inventoryReads = 0
        val adapter = object : ProductLocationsAdapter {
            override val providerId = OBI_PROVIDER_ID
            override suspend fun read(
                ref: ProductRef,
                requested: List<BranchId>,
                trustedProduct: ProviderProduct?,
            ): ProductLocationsResult {
                inventoryReads++
                assertEquals("3496072", ref.productId)
                assertEquals(listOf(BranchId("075")), requested)
                return ProductLocationsResult.Available(
                    ref, requested.map {
                        LocationStock(ProviderBranch(it, "Nowy Sącz"), 3)
                    },
                    LocationCoverage(
                        LocationCoverageKind.REQUESTED_SUBSET,
                        requested, requested, 1,
                    ),
                    123L,
                )
            }
        }
        val realTool = AdvisorLocationsTool(
            locationsService = ProductLocationsService(listOf(adapter)),
        )
        var continuations = 0
        val c = controller(
            initial = location(),
            continueCall = { output ->
                continuations++
                when (continuations) {
                    1 -> {
                        val first = (output as AdvisorToolContinuation.Locations).evidence
                        assertEquals("rejected", first.status)
                        assertEquals("untrusted_product", first.reason)
                        assertEquals(0, inventoryReads)
                        discovery()
                    }
                    2 -> {
                        assertTrue(output is AdvisorToolContinuation.Verified)
                        location(2)
                    }
                    3 -> {
                        val second = (output as AdvisorToolContinuation.Locations).evidence
                        assertEquals("verified", second.status)
                        assertEquals(listOf("075"), second.checkedIds)
                        assertEquals(1, inventoryReads)
                        answer()
                    }
                    else -> error("Unexpected fourth tool")
                }
            },
            locationCall = { args, provider, selected, input, current, past ->
                locationCalls++
                realTool.execute(args, provider, selected, input, current, past)
            },
            onDiscovery = { verified() },
        )
        val result = c.runTurn(
            input = "Sprawdź stan produktu w OBI 075",
            previousResponseId = "resp_prior",
        ) {}
        assertTrue(result is AdvisorUiState.Success)
        assertEquals(2, locationCalls) // First denied, second re-evaluated after verification.
        assertEquals(1, inventoryReads)
        assertEquals(3, continuations) // All three logical local calls accounted for.
    }



    @Test fun kwantFindProductsSelectedAndOtherBranchNeverRequiresLocations() = runBlocking {
        for ((userRequest, expectedBranch) in listOf(
            "Sprawdź gniazda w oddziale Nowy Sącz" to "205",
            "Sprawdź gniazda w oddziale Zamość" to "128",
        )) {
            var discoveries = 0
            var locationCalls = 0
            var continuations = 0
            val request = AdvisorToolArguments(
                storeNumber = expectedBranch,
                providerId = "kwant-pl",
                requestedBranch = expectedBranch,
                queries = listOf(AdvisorToolQuery("gniazdo 16A 4P", 2)),
            )
            val initial = AdvisorProxyCallResult.Success(
                AdvisorProxyResult.ToolRequest(
                    responseId = "resp_kwant_discovery",
                    callId = "call_kwant_discovery",
                    arguments = request,
                ),
            )
            val kwantSnapshot = snapshot.copy(
                obik = "7027", productId = "7027",
                providerId = "kwant-pl", articleNumber = "921861",
                storeNumber = expectedBranch, branchId = expectedBranch,
            )
            val c = controller(
                initial = initial,
                continueCall = { output ->
                    continuations++
                    assertTrue(output is AdvisorToolContinuation.Verified)
                    val verifiedResult = (output as AdvisorToolContinuation.Verified).result
                    assertEquals("kwant-pl", verifiedResult.providerId)
                    assertEquals(expectedBranch, verifiedResult.branchId)
                    assertEquals("7027", verifiedResult.results.single().products.single().productId)
                    answer()
                },
                locationCall = { _, _, _, _, _, _ ->
                    locationCalls++
                    error("find_product_locations must remain independent")
                },
                onProviderDiscovery = { arguments ->
                    discoveries++
                    assertEquals("kwant-pl", arguments.providerId)
                    assertEquals(expectedBranch, arguments.storeNumber)
                    assertEquals(expectedBranch, arguments.branchId)
                    assertTrue(arguments.requestedBranch == null)
                    AdvisorToolExecutionResult.Success(
                        result = AdvisorVerifiedToolResult(
                            storeNumber = expectedBranch,
                            results = listOf(AdvisorVerifiedQueryResult(
                                query = "gniazdo 16A 4P",
                                status = AdvisorQueryResultStatus.VERIFIED,
                                products = listOf(AdvisorVerifiedProduct(
                                    obik = "7027",
                                    productId = "7027",
                                    name = "Fixture KWANT gniazdo",
                                    stock = 4,
                                    price = null,
                                )),
                            )),
                            providerId = "kwant-pl",
                        ),
                        snapshots = listOf(kwantSnapshot),
                    )
                },
            )
            val state = c.runTurn(
                input = userRequest,
                previousResponseId = "resp_before",
                conversationProviderId = "kwant-pl",
                conversationStoreNumber = "205",
            ) {}
            assertTrue(userRequest, state is AdvisorUiState.Success)
            assertEquals(userRequest, 1, discoveries)
            assertEquals(userRequest, 1, continuations)
            assertEquals(userRequest, 0, locationCalls)
        }
    }


    @Test fun kwantGroupedFindProductsIndependentFromDisabledLocations() = runBlocking {
        for ((requestText, expectedBranch) in listOf(
            "Sprawdź gniazda 7027 i 7035 w oddziale Nowy Sącz" to "205",
            "Sprawdź gniazda 7027 i 7035 w oddziale Zamość" to "128",
        )) {
            var providerCalls = 0
            var locationCalls = 0
            var continuations = 0
            val identifiers = listOf("7027", "7035")
            val groupedQueries = identifiers.map { AdvisorToolQuery(it, 1) }
            val initial = AdvisorProxyCallResult.Success(
                AdvisorProxyResult.ToolRequest(
                    responseId = "resp_kwant_group",
                    callId = "call_kwant_group",
                    arguments = AdvisorToolArguments(
                        storeNumber = expectedBranch,
                        providerId = "kwant-pl",
                        requestedBranch = expectedBranch,
                        queries = groupedQueries,
                    ),
                ),
            )
            val c = controller(
                initial = initial,
                continueCall = { output ->
                    continuations++
                    assertTrue(requestText, output is AdvisorToolContinuation.Verified)
                    val result = (output as AdvisorToolContinuation.Verified).result
                    assertEquals("kwant-pl", result.providerId)
                    assertEquals(expectedBranch, result.branchId)
                    assertEquals(identifiers, result.results.map {
                        it.products.single().productId
                    })
                    assertTrue(result.results.all {
                        it.status == AdvisorQueryResultStatus.VERIFIED
                    })
                    answer()
                },
                locationCall = { _, _, _, _, _, _ ->
                    locationCalls++
                    error("Grouped find_products must not invoke find_product_locations")
                },
                onProviderDiscovery = { args ->
                    providerCalls++
                    assertEquals("kwant-pl", args.providerId)
                    assertEquals(expectedBranch, args.branchId)
                    assertEquals(groupedQueries, args.queries)
                    assertEquals(null, args.requestedBranch)
                    AdvisorToolExecutionResult.Success(
                        result = AdvisorVerifiedToolResult(
                            storeNumber = expectedBranch,
                            providerId = "kwant-pl",
                            results = identifiers.map { id ->
                                AdvisorVerifiedQueryResult(
                                    query = id,
                                    status = AdvisorQueryResultStatus.VERIFIED,
                                    products = listOf(AdvisorVerifiedProduct(
                                        obik = id,
                                        productId = id,
                                        name = "Verified KWANT fixture $id",
                                        stock = 3,
                                        price = null,
                                    )),
                                )
                            },
                        ),
                        snapshots = identifiers.map { id ->
                            snapshot.copy(
                                obik = id, productId = id, providerId = "kwant-pl",
                                branchId = expectedBranch, storeNumber = expectedBranch,
                            )
                        },
                    )
                },
            )
            val state = c.runTurn(
                input = requestText,
                previousResponseId = "resp_before",
                conversationProviderId = "kwant-pl",
                conversationStoreNumber = "205",
            ) {}
            assertTrue(requestText, state is AdvisorUiState.Success)
            assertEquals(requestText, 1, providerCalls)
            assertEquals(requestText, 1, continuations)
            assertEquals(requestText, 0, locationCalls)
        }
    }


    @Test fun inc003ContextualSameProductUsesBoundedHistoryAndLocationIntent() {
        val branches = listOf(
            ProviderBranch(BranchId("205"), "Nowy Sącz"),
            ProviderBranch(BranchId("128"), "Zamość"),
        )
        val product = snapshot.copy(
            obik = "580", productId = "580", providerId = "kwant-pl",
            articleNumber = "MBN116E/HAG", storeNumber = "205", branchId = "205",
        )
        val history = listOf(
            AdvisorLocationHistoryMessage("USER",
                "Sprawdź dostępność produktu MBN116E w oddziale w Zamościu"),
            AdvisorLocationHistoryMessage("ASSISTANT", "Verified", listOf(product)),
        )
        val follow = resolveAdvisorLocationFollowUp(
            input = "A ile tego samego produktu jest w Nowym Sączu?",
            history = history, historicalProducts = listOf(product), branches = branches,
            providerId = "kwant-pl",
        )
        assertEquals(listOf("580"), follow.historicalProducts.map { it.effectiveProductId })
        assertEquals(true, follow.hasPendingInventoryRequest)
        assertEquals(true, follow.authorizedText.contains("Sprawdź stan"))
        assertEquals(
            listOf(BranchId("205")),
            (authorizeKwantLocations(follow.authorizedText,
                listOf("Nowy Sącz"), branches, BranchId("205"))
                as KwantAuthorizedLocations.Allowed).requested,
        )
        val next = resolveAdvisorLocationFollowUp(
            input = "A w Sączu ile jest tego samego?",
            history = history, historicalProducts = listOf(product), branches = branches,
            providerId = "kwant-pl",
        )
        assertEquals(true, next.authorizedText.contains("Sprawdź stan"))
        assertEquals(listOf(BranchId("205")),
            (authorizeKwantLocations(next.authorizedText,
                listOf("Nowy Sącz"), branches, BranchId("205"))
                as KwantAuthorizedLocations.Allowed).requested)
        val unrelated = resolveAdvisorLocationFollowUp(
            input = "A w Sączu ile jest tego samego?",
            history = history + AdvisorLocationHistoryMessage("USER", "pokaż ceny"),
            historicalProducts = listOf(product), branches = branches,
        )
        assertEquals(false, unrelated.hasPendingInventoryRequest)
    }

    @Test fun inc003FiveTurnHistoryPreservesLatestExplicitIdentityAndVerifiedBranchReads() = runBlocking {
        val branches = listOf(
            ProviderBranch(BranchId("205"), "Nowy Sącz"),
            ProviderBranch(BranchId("128"), "Zamość"),
        )
        val first = snapshot.copy(
            obik = "580", productId = "580", providerId = "kwant-pl",
            articleNumber = "MBN116E/HAG", storeNumber = "205", branchId = "205",
        )
        val other = snapshot.copy(
            obik = "581", productId = "581", providerId = "kwant-pl",
            articleNumber = "MBN316E/HAG", storeNumber = "205", branchId = "205",
        )
        val reads = mutableListOf<Pair<String, List<String>>>()
        val adapter = KwantProductLocationsAdapter(
            directory = { ProviderBranchResult.Available(branches) },
            fetcher = LocationsHttpFetcher { url ->
                assertEquals("/api/front/products/580/departments", url.encodedPath)
                assertEquals("1", url.queryParameter("extended"))
                pl.lukaszpeciak.towarownik.product.provider.LocationsHttpResult.Success(
                    """{"list":[{"department_id":205,"stock":7},""" +
                        """{"department_id":128,"stock":11}],"unit":"szt."}""",
                )
            },
        )
        val tool = AdvisorLocationsTool(
            locationsService = ProductLocationsService(listOf(adapter)),
            kwantBranches = { ProviderBranchResult.Available(branches) },
        )
        val turn1 = "Sprawdź dostępność produktu MBN116E w oddziale w Zamościu"
        val turn2 = "A ile tego samego produktu jest w Nowym Sączu?"
        val turn3 = "sprawdź dostępność mbn316e u nas"
        val turn4 = "Sprawdź stan produktu MBN116E/HAG w oddziale KWANT Nowy Sącz 205."
        val turn5 = "A w Sączu ile jest tego samego?"
        val history = mutableListOf<AdvisorLocationHistoryMessage>()

        suspend fun exercise(text: String, expected: String, cards: List<VerifiedProductSnapshot>) {
            var continuationCount = 0
            val controller = controller(
                initial = AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.LocationToolRequest(
                        responseId = "resp_inc003", callId = "call_inc003",
                        arguments = AdvisorLocationArguments(
                            "kwant-pl", "580", listOf(
                                if (expected == "128") "Zamość" else "Nowy Sącz"
                            ),
                        ),
                    ),
                ),
                continueCall = { result ->
                    continuationCount++
                    val evidence = (result as AdvisorToolContinuation.Locations).evidence
                    assertEquals(text, "verified", evidence.status)
                    assertEquals(text, "580", evidence.productId)
                    assertEquals(text, listOf(expected), evidence.checkedIds)
                    assertEquals(text, listOf(expected), evidence.returnedIds)
                    assertEquals(text, if (expected == "128") 11 else 7,
                        evidence.locations.single().stock)
                    reads += evidence.productId!! to evidence.checkedIds
                    answer()
                },
                locationCall = { args, provider, branch, authorizedText, current, past ->
                    tool.execute(args, provider, branch, authorizedText, current, past)
                },
                onProviderDiscovery = { error("History location test must not invoke discovery") },
                kwantBranches = branches,
            )
            val state = controller.runTurn(
                input = text, previousResponseId = "resp_previous",
                conversationProviderId = "kwant-pl",
                conversationStoreNumber = "205",
                historicalVerifiedProducts = cards,
                locationHistory = history,
            ) {}
            assertTrue(text, state is AdvisorUiState.Success)
            assertEquals(text, 1, continuationCount)
        }
        exercise(turn1, "128", listOf(first))
        history += AdvisorLocationHistoryMessage("USER", turn1)
        history += AdvisorLocationHistoryMessage("ASSISTANT", "verified synthetic", listOf(first))
        exercise(turn2, "205", listOf(first))
        history += AdvisorLocationHistoryMessage("USER", turn2)
        history += AdvisorLocationHistoryMessage("ASSISTANT", "verified synthetic", listOf(first))
        // Turns 3/4 were successful explicit-product requests on the real device;
        // represent their verified messages without assuming which model tool was used.
        history += AdvisorLocationHistoryMessage("USER", turn3)
        history += AdvisorLocationHistoryMessage("ASSISTANT", "verified synthetic", listOf(other))
        history += AdvisorLocationHistoryMessage("USER", turn4)
        history += AdvisorLocationHistoryMessage("ASSISTANT", "verified synthetic", listOf(first))
        val selected = resolveAdvisorLocationFollowUp(
            input = turn5, history = history, historicalProducts = listOf(first, other),
            branches = branches, providerId = "kwant-pl",
        )
        assertEquals(listOf("580"), selected.historicalProducts.map { it.effectiveProductId })
        exercise(turn5, "205", listOf(first, other))
        assertEquals(listOf(
            "580" to listOf("128"), "580" to listOf("205"), "580" to listOf("205"),
        ), reads)
    }

    @Test fun controllerAndRealLocationsToolShareNumericProductIdentityBoundary() = runBlocking {
        val selected = snapshot.copy(
            obik = "7035", productId = "7035", providerId = "kwant-pl",
            articleNumber = "921871", storeNumber = "205", branchId = "205",
        )
        val measuredNumber = selected.copy(
            obik = "1000", productId = "1000", articleNumber = "441111",
        )
        val historical = listOf(selected, measuredNumber)
        val previousTurns = listOf(
            AdvisorLocationHistoryMessage("ASSISTANT", "Two verified KWANT cards", historical),
            AdvisorLocationHistoryMessage(
                "USER", "Sprawdź stan produktu 7035 w oddziale Zamość",
            ),
            AdvisorLocationHistoryMessage("ASSISTANT", "Stan wymaga sprawdzenia."),
        )
        data class Scenario(
            val user: String,
            val modelId: String,
            val expectedHistorical: List<String>,
            val expectedStatus: String,
            val expectedReason: String,
            val expectedReads: List<String>,
        )
        val scenarios = listOf(
            Scenario(
                "Sprawdź stan 1000 W w oddziale Zamość",
                "1000", listOf("7035"),
                "rejected", "untrusted_product", emptyList(),
            ),
            Scenario(
                "Sprawdź stan produktu 1000 w Zamościu",
                "1000", listOf("1000"),
                "unavailable", "unverified_request_contract", listOf("1000"),
            ),
            Scenario(
                "Sprawdź stan produktu 7027 w Zamościu",
                "7035", listOf("7035"),
                "rejected", "untrusted_product", emptyList(),
            ),
            Scenario(
                "Sprawdź stan produktu 1000 w Zamościu",
                "7035", listOf("1000"),
                "rejected", "untrusted_product", emptyList(),
            ),
            Scenario(
                "Sprawdź stan produktu 7035 w oddziale Zamość, 1000 W, 25 m i 5 szt.",
                "7035", listOf("7035"),
                "unavailable", "unverified_request_contract", listOf("7035"),
            ),
        )
        for (scenario in scenarios) {
            val adapterReads = mutableListOf<String>()
            val adapter = object : ProductLocationsAdapter {
                override val providerId = KWANT_PROVIDER_ID
                override suspend fun read(
                    ref: ProductRef,
                    requested: List<BranchId>,
                    trustedProduct: ProviderProduct?,
                ): ProductLocationsResult {
                    adapterReads += ref.productId
                    return ProductLocationsResult.Unavailable(
                        LocationFailure.UNVERIFIED_REQUEST_CONTRACT,
                    )
                }
            }
            val realLocations = AdvisorLocationsTool(
                locationsService = ProductLocationsService(listOf(adapter)),
                kwantBranches = {
                    ProviderBranchResult.Available(listOf(
                        ProviderBranch(BranchId("205"), "Nowy Sącz"),
                        ProviderBranch(BranchId("128"), "Zamość"),
                        ProviderBranch(BranchId("204"), "Tarnów"),
                    ))
                },
            )
            val proxyLocation = AdvisorProxyCallResult.Success(
                AdvisorProxyResult.LocationToolRequest(
                    responseId = "resp_identity",
                    callId = "call_identity",
                    arguments = AdvisorLocationArguments(
                        "kwant-pl", scenario.modelId, listOf("Zamość"),
                    ),
                ),
            )
            var serviceInvocations = 0
            var continuations = 0
            val controller = controller(
                initial = proxyLocation,
                continueCall = { output ->
                    continuations++
                    val evidence = (output as AdvisorToolContinuation.Locations).evidence
                    assertEquals(scenario.user, scenario.expectedStatus, evidence.status)
                    assertEquals(scenario.user, scenario.expectedReason, evidence.reason)
                    answer()
                },
                locationCall = { args, provider, selectedBranch, userText, current, past ->
                    serviceInvocations++
                    assertEquals(scenario.user, scenario.user, userText)
                    assertEquals(scenario.user, scenario.expectedHistorical,
                        past.map { it.effectiveProductId })
                    realLocations.execute(args, provider, selectedBranch, userText, current, past)
                },
                onProviderDiscovery = { error("No find_products needed for history test") },
            )
            val state = controller.runTurn(
                input = scenario.user,
                previousResponseId = "resp_prior",
                conversationProviderId = "kwant-pl",
                conversationStoreNumber = "205",
                historicalVerifiedProducts = historical,
                locationHistory = previousTurns,
            ) {}
            assertTrue(scenario.user, state is AdvisorUiState.Success)
            assertEquals(scenario.user, 1, serviceInvocations)
            assertEquals(scenario.user, 1, continuations)
            assertEquals(scenario.user, scenario.expectedReads, adapterReads)
        }
    }

    @Test fun controllerDoesNotSelectObiMarketIdsAsHistoricalProductIdentities() = runBlocking {
        val product = snapshot.copy(
            obik = "6117543", productId = "6117543", storeNumber = "075",
        )
        for (market in listOf("003", "052", "075")) {
            val marketNumber = product.copy(obik = market, productId = market)
            val oldMessages = listOf(
                AdvisorLocationHistoryMessage(
                    "ASSISTANT", "Two cards", listOf(product, marketNumber),
                ),
                AdvisorLocationHistoryMessage(
                    "USER", "Sprawdź stan produktu OBIK 6117543 w OBI 075",
                ),
                AdvisorLocationHistoryMessage("ASSISTANT", "OK"),
            )
            val locationAdapterReads = mutableListOf<String>()
            val adapter = object : ProductLocationsAdapter {
                override val providerId = OBI_PROVIDER_ID
                override suspend fun read(
                    ref: ProductRef,
                    requested: List<BranchId>,
                    trustedProduct: ProviderProduct?,
                ): ProductLocationsResult {
                    locationAdapterReads += ref.productId
                    return ProductLocationsResult.Available(
                        ref,
                        requested.map { id -> LocationStock(ProviderBranch(id, "Test"), 2) },
                        LocationCoverage(
                            LocationCoverageKind.REQUESTED_SUBSET,
                            requested,
                            requested,
                            1,
                        ),
                        123L,
                    )
                }
            }
            val realTool = AdvisorLocationsTool(
                locationsService = ProductLocationsService(listOf(adapter)),
            )
            val text = "Sprawdź stan produktu OBIK 6117543 w market OBI $market"
            val modelId = "6117543"
            val c = controller(
                initial = AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.LocationToolRequest(
                        responseId = "resp_obi_market",
                        callId = "call_obi_market",
                        arguments = AdvisorLocationArguments("obi-pl", modelId, listOf(market)),
                    ),
                ),
                continueCall = { continuation ->
                    val result = (continuation as AdvisorToolContinuation.Locations).evidence
                    assertEquals(text, "verified", result.status)
                    assertEquals(text, "6117543", result.productId)
                    assertEquals(text, listOf(market), result.checkedIds)
                    answer()
                },
                locationCall = { args, provider, selectedBranch, userText, current, past ->
                    assertEquals(text, listOf("6117543"), past.map { it.effectiveProductId })
                    realTool.execute(args, provider, selectedBranch, userText, current, past)
                },
            )
            assertTrue(c.runTurn(
                input = text,
                previousResponseId = "resp_prior",
                historicalVerifiedProducts = listOf(product, marketNumber),
                locationHistory = oldMessages,
            ) {} is AdvisorUiState.Success)
            assertEquals(text, listOf("6117543"), locationAdapterReads)
        }
    }


    @Test fun controllerCurrentSingleSelectionSupersedesHistoricalBothWithoutNetworkHttp() = runBlocking {
        val first = snapshot.copy(
            obik = "7035", productId = "7035", providerId = "kwant-pl",
            articleNumber = "921871", storeNumber = "205", branchId = "205",
        )
        val second = first.copy(
            obik = "7027", productId = "7027", articleNumber = "921861",
        )
        val verified = listOf(first, second)
        val history = listOf(
            AdvisorLocationHistoryMessage(
                "ASSISTANT", "Two verified KWANT products", verified,
            ),
            AdvisorLocationHistoryMessage(
                "USER", "Sprawdź stan obu gniazd w Zamościu",
            ),
            AdvisorLocationHistoryMessage("ASSISTANT", "Który oddział?"),
        )
        val kwantBranches = listOf(
            ProviderBranch(BranchId("205"), "Nowy Sącz"),
            ProviderBranch(BranchId("128"), "Zamość"),
            ProviderBranch(BranchId("204"), "Tarnów"),
        )
        data class Case(
            val input: String,
            val modelId: String,
            val expectedBoth: Set<String>,
            val expectedHistory: List<String>,
            val expectedStatus: String,
            val expectedReason: String,
            val expectedProductId: String?,
            val expectedToolCalls: Int,
        )
        val cases = listOf(
            Case(
                "Sprawdź stan produktu 7027 w Tarnowie", "7027",
                emptySet(), listOf("7027"),
                "unavailable", "transport", "7027", 1,
            ),
            Case(
                "Sprawdź stan produktu 7027 w Tarnowie", "7035",
                emptySet(), listOf("7027"),
                "rejected", "untrusted_product", null, 1,
            ),
            Case(
                "Sprawdź stan produktu 9999 w Tarnowie", "7035",
                emptySet(), listOf("7035", "7027"),
                "rejected", "ambiguous_product", null, 1,
            ),
            // The current explicit "obu" remains valid; it must not
            // collapse into silently selecting the model's single product.
            Case(
                "obu", "7035", setOf("7035", "7027"),
                listOf("7035", "7027"),
                "unavailable", "multi_product_tool_contract", null, 0,
            ),
            // Location-only follow-up may carry an uninterrupted USER "obu".
            Case(
                "a w Tarnowie?", "7035", setOf("7035", "7027"),
                listOf("7035", "7027"),
                "unavailable", "multi_product_tool_contract", null, 0,
            ),
        )
        for (scenario in cases) {
            // Real endpoint is enabled. A synthetic fetcher reports transport
            // failure for a selected single product; obu invokes no HTTP.
            var httpCalls = 0
            val adapter = KwantProductLocationsAdapter(
                fetcher = LocationsHttpFetcher {
                    httpCalls++
                    pl.lukaszpeciak.towarownik.product.provider.LocationsHttpResult.Failure(
                        LocationFailure.TRANSPORT,
                    )
                },
                directory = { ProviderBranchResult.Available(kwantBranches) },
            )
            val locationsTool = AdvisorLocationsTool(
                locationsService = ProductLocationsService(listOf(adapter)),
                kwantBranches = { ProviderBranchResult.Available(kwantBranches) },
            )
            val followed = resolveAdvisorLocationFollowUp(
                input = scenario.input,
                history = history,
                historicalProducts = verified,
                branches = kwantBranches,
            )
            assertEquals(scenario.input, scenario.expectedBoth, followed.bothProductIds)
            assertEquals(
                scenario.input, scenario.expectedHistory,
                followed.historicalProducts.map { it.effectiveProductId },
            )
            var locationToolCalls = 0
            var continuations = 0
            val c = controller(
                initial = AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.LocationToolRequest(
                        responseId = "resp_kwant_both_selection",
                        callId = "call_kwant_both_selection",
                        arguments = AdvisorLocationArguments(
                            "kwant-pl", scenario.modelId, listOf("Tarnów"),
                        ),
                    ),
                ),
                continueCall = { output ->
                    continuations++
                    val evidence = (output as AdvisorToolContinuation.Locations).evidence
                    assertEquals(scenario.input, scenario.expectedStatus, evidence.status)
                    assertEquals(scenario.input, scenario.expectedReason, evidence.reason)
                    assertEquals(scenario.input, scenario.expectedProductId, evidence.productId)
                    answer()
                },
                locationCall = { args, provider, selectedBranch, userText, current, past ->
                    locationToolCalls++
                    assertEquals(scenario.input, followed.authorizedText, userText)
                    assertEquals(
                        scenario.input, scenario.expectedHistory,
                        past.map { it.effectiveProductId },
                    )
                    locationsTool.execute(
                        args, provider, selectedBranch, userText, current, past,
                    )
                },
                onProviderDiscovery = { error("find_products not involved") },
            )
            val result = c.runTurn(
                input = scenario.input,
                previousResponseId = "resp_prior",
                conversationProviderId = "kwant-pl",
                conversationStoreNumber = "205",
                historicalVerifiedProducts = verified,
                locationHistory = history,
            ) {}
            assertTrue(scenario.input, result is AdvisorUiState.Success)
            assertEquals(scenario.input, 1, continuations)
            assertEquals(scenario.input, scenario.expectedToolCalls, locationToolCalls)
            assertEquals(scenario.input, if (scenario.expectedToolCalls == 0) 0 else
                if (scenario.expectedReason == "transport") 1 else 0, httpCalls)
        }
    }

    @Test fun controllerKwantScopesRealLocationsOutputToUserBranchesNotModelHints() = runBlocking {
        val branches = listOf(
            ProviderBranch(BranchId("205"), "Nowy Sącz"),
            ProviderBranch(BranchId("128"), "Zamość"),
            ProviderBranch(BranchId("204"), "Tarnów"),
        )
        val product = snapshot.copy(
            obik = "580", productId = "580", providerId = "kwant-pl",
            articleNumber = "MBN116E", branchId = "205", storeNumber = "205",
        )
        data class Scenario(
            val user: String,
            val modelHints: List<String>,
            val expectedStatus: String,
            val checked: List<String>,
            val expectedHttp: Int,
        )
        for (case in listOf(
            Scenario("Sprawdź stan produktu 580 w Zamościu",
                listOf("Zamość"), "verified", listOf("128"), 1),
            Scenario("Sprawdź stan produktu 580 w Zamościu",
                listOf("Tarnów"), "rejected", emptyList(), 0),
            Scenario("Sprawdź stan produktu 580 we wszystkich oddziałach",
                emptyList(), "verified", listOf("205", "128", "204"), 1),
        )) {
            var inventoryHttpCalls = 0
            val adapter = KwantProductLocationsAdapter(
                directory = { ProviderBranchResult.Available(branches) },
                fetcher = LocationsHttpFetcher { url ->
                    inventoryHttpCalls++
                    assertEquals("1", url.queryParameter("extended"))
                    pl.lukaszpeciak.towarownik.product.provider.LocationsHttpResult.Success(
                        """{"list":[{"department_id":205,"stock":2},""" +
                            """{"department_id":128,"stock":0},""" +
                            """{"department_id":204,"stock":9}],"unit":"szt."}""",
                    )
                },
            )
            val tool = AdvisorLocationsTool(
                locationsService = ProductLocationsService(listOf(adapter)),
                kwantBranches = { ProviderBranchResult.Available(branches) },
            )
            val proxy = AdvisorProxyCallResult.Success(
                AdvisorProxyResult.LocationToolRequest(
                    responseId = "resp_kwant_scope", callId = "call_kwant_scope",
                    arguments = AdvisorLocationArguments(
                        "kwant-pl", "580", case.modelHints,
                    ),
                ),
            )
            var continuations = 0
            val c = controller(
                initial = proxy,
                continueCall = { result ->
                    continuations++
                    val verified = (result as AdvisorToolContinuation.Locations).evidence
                    assertEquals(case.user, case.expectedStatus, verified.status)
                    assertEquals(case.user, case.checked, verified.checkedIds)
                    assertEquals(case.user, case.checked.toSet(), verified.locations.map { it.branchId }.toSet())
                    if (verified.status == "verified") {
                        assertEquals(case.user, "szt.", verified.stockUnit)
                        assertTrue(case.user, verified.centralStock == null)
                    } else {
                        assertEquals(case.user, "location_not_authorized", verified.reason)
                    }
                    answer()
                },
                locationCall = { args, provider, selected, text, current, past ->
                    tool.execute(args, provider, selected, text, current, past)
                },
                onProviderDiscovery = { error("find_products must remain independent") },
            )
            val result = c.runTurn(
                input = case.user, previousResponseId = "previous",
                conversationProviderId = "kwant-pl", conversationStoreNumber = "205",
                historicalVerifiedProducts = listOf(product),
            ) {}
            assertTrue(case.user, result is AdvisorUiState.Success)
            assertEquals(case.user, 1, continuations)
            assertEquals(case.user, case.expectedHttp, inventoryHttpCalls)
        }
    }

    @Test fun controllerKwantCompleteMultipleLocationsOrWholeRequestRejected() = runBlocking {
        val branches = listOf(
            ProviderBranch(BranchId("205"), "Nowy Sącz"),
            ProviderBranch(BranchId("128"), "Zamość"),
            ProviderBranch(BranchId("204"), "Tarnów"),
        )
        val product = snapshot.copy(
            obik = "7027", productId = "7027", providerId = "kwant-pl",
            articleNumber = "921861", branchId = "205", storeNumber = "205",
        )
        data class Case(
            val text: String,
            val hints: List<String>,
            val ids: List<String>,
            val rejection: String?,
        )
        val scenarios = listOf(
            Case("Sprawdź stan 7027 w Zamościu i Tarnowie",
                listOf("Zamość"), listOf("128", "204"), null),
            Case("Sprawdź stan 7027 w Zamościu oraz Tarnowie",
                emptyList(), listOf("128", "204"), null),
            Case("Sprawdź stan 7027 w Tarnowie, Zamościu",
                emptyList(), listOf("204", "128"), null),
            Case("Sprawdź stan 7027 w oddziale 128 i 204",
                listOf("204"), listOf("128", "204"), null),
            Case("Sprawdź stan 7027 w Zamościu i Nieznanowie",
                emptyList(), emptyList(), "unknown_location"),
            Case("Sprawdź stan 7027 w oddziale 128 i 999",
                emptyList(), emptyList(), "unknown_location"),
            Case("Sprawdź stan 7027 w oddziale 128 Tarnów",
                emptyList(), emptyList(), "location_conflict"),
            Case("Sprawdź stan 7027 w Zamościu i Tarnowie",
                listOf("Nowy Sącz"), emptyList(), "location_not_authorized"),
        )
        for (scenario in scenarios) {
            var httpReads = 0
            val adapter = KwantProductLocationsAdapter(
                directory = { ProviderBranchResult.Available(branches) },
                fetcher = LocationsHttpFetcher {
                    httpReads++
                    pl.lukaszpeciak.towarownik.product.provider.LocationsHttpResult.Success(
                        """{"list":[{"department_id":205,"stock":1},""" +
                            """{"department_id":128,"stock":0},""" +
                            """{"department_id":204,"stock":5}],"unit":"szt."}""",
                    )
                },
            )
            val tool = AdvisorLocationsTool(
                locationsService = ProductLocationsService(listOf(adapter)),
                kwantBranches = { ProviderBranchResult.Available(branches) },
            )
            val c = controller(
                initial = AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.LocationToolRequest(
                        responseId = "resp_multi", callId = "call_multi",
                        arguments = AdvisorLocationArguments(
                            "kwant-pl", "7027", scenario.hints,
                        ),
                    ),
                ),
                continueCall = { continuation ->
                    val result = (continuation as AdvisorToolContinuation.Locations).evidence
                    if (scenario.rejection != null) {
                        assertEquals(scenario.text, "rejected", result.status)
                        assertEquals(scenario.text, scenario.rejection, result.reason)
                        assertTrue(scenario.text, result.checkedIds.isEmpty())
                    } else {
                        assertEquals(scenario.text, "verified", result.status)
                        assertEquals(scenario.text, scenario.ids, result.checkedIds)
                        assertEquals(scenario.text, scenario.ids.toSet(),
                            result.locations.map { it.branchId }.toSet())
                    }
                    answer()
                },
                locationCall = { args, provider, branch, text, current, history ->
                    tool.execute(args, provider, branch, text, current, history)
                },
                onProviderDiscovery = { error("find_products must not execute") },
            )
            assertTrue(scenario.text, c.runTurn(
                input = scenario.text, previousResponseId = "resp_before_multi",
                conversationProviderId = "kwant-pl", conversationStoreNumber = "205",
                historicalVerifiedProducts = listOf(product),
            ) {} is AdvisorUiState.Success)
            assertEquals(scenario.text, if (scenario.rejection == null) 1 else 0, httpReads)
        }
    }

    @Test fun controllerKwantCanonicalInflectionsFollowUpsAndConflictsStayScoped() = runBlocking {
        val branches = listOf(
            ProviderBranch(BranchId("205"), "Nowy Sącz"),
            ProviderBranch(BranchId("128"), "Zamość"),
            ProviderBranch(BranchId("216"), "Białystok"),
            ProviderBranch(BranchId("204"), "Tarnów"),
            ProviderBranch(BranchId("141"), "Włocławek"),
            ProviderBranch(BranchId("161"), "Suwałki"),
        )
        val product = snapshot.copy(
            obik = "7027", productId = "7027", providerId = "kwant-pl",
            articleNumber = "921861", storeNumber = "205", branchId = "205",
        )
        val previous = listOf(
            AdvisorLocationHistoryMessage("ASSISTANT", "Verified product",
                listOf(product)),
            AdvisorLocationHistoryMessage("USER",
                "Sprawdź stan produktu 7027 w Zamościu"),
            AdvisorLocationHistoryMessage("ASSISTANT", "Który oddział teraz?"),
        )
        data class Scenario(
            val text: String,
            val hints: List<String>,
            val expectedIds: Set<String>,
            val expectedReason: String?,
        )
        val cases = listOf(
            Scenario("a w Białymstoku?", listOf("Białystok"),
                setOf("216"), null),
            Scenario("a we Włocławku?", listOf("Włocławek"),
                setOf("141"), null),
            Scenario("Sprawdź stan 7027 w Białymstoku i Tarnowie",
                listOf("Tarnów"), setOf("216", "204"), null),
            Scenario("Sprawdź stan 7027 we Włocławku i Suwałkach",
                emptyList(), setOf("141", "161"), null),
            Scenario("Sprawdź stan 7027 w Białymstoku i Nieznanowie",
                emptyList(), emptySet(), "unknown_location"),
            Scenario("Sprawdź stan 7027 w oddziale 216 Tarnów",
                emptyList(), emptySet(), "location_conflict"),
            Scenario("Sprawdź stan 7027 w Białymstoku",
                listOf("Tarnów"), emptySet(), "location_not_authorized"),
        )
        for (case in cases) {
            var httpCalls = 0
            val adapter = KwantProductLocationsAdapter(
                directory = { ProviderBranchResult.Available(branches) },
                fetcher = LocationsHttpFetcher { url ->
                    httpCalls++
                    assertEquals("/api/front/products/7027/departments", url.encodedPath)
                    assertEquals("1", url.queryParameter("extended"))
                    pl.lukaszpeciak.towarownik.product.provider.LocationsHttpResult.Success(
                        """{"list":[{"department_id":205,"stock":2},""" +
                            """{"department_id":128,"stock":5},""" +
                            """{"department_id":216,"stock":0},""" +
                            """{"department_id":204,"stock":3},""" +
                            """{"department_id":141,"stock":4},""" +
                            """{"department_id":161,"stock":1}],"unit":"szt."}""",
                    )
                },
            )
            val tool = AdvisorLocationsTool(
                locationsService = ProductLocationsService(listOf(adapter)),
                kwantBranches = { ProviderBranchResult.Available(branches) },
            )
            var continuationCount = 0
            val c = controller(
                initial = AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.LocationToolRequest(
                        responseId = "resp_locative", callId = "call_locative",
                        arguments = AdvisorLocationArguments("kwant-pl", "7027",
                            case.hints),
                    ),
                ),
                continueCall = { result ->
                    continuationCount++
                    val actual = (result as AdvisorToolContinuation.Locations).evidence
                    if (case.expectedReason != null) {
                        assertEquals(case.text, "rejected", actual.status)
                        assertEquals(case.text, case.expectedReason, actual.reason)
                        assertTrue(case.text, actual.locations.isEmpty())
                    } else {
                        assertEquals(case.text, "verified", actual.status)
                        assertEquals(case.text, case.expectedIds,
                            actual.checkedIds.toSet())
                        assertEquals(case.text, case.expectedIds,
                            actual.locations.map { it.branchId }.toSet())
                        assertEquals(case.text, "szt.", actual.stockUnit)
                        assertTrue(case.text, actual.locations.all { it.stock != null })
                    }
                    answer()
                },
                locationCall = { args, provider, branch, text, current, history ->
                    tool.execute(args, provider, branch, text, current, history)
                },
                onProviderDiscovery = { error("find_products must stay independent") },
                kwantBranches = branches,
            )
            val state = c.runTurn(
                input = case.text,
                previousResponseId = "resp_before_locative",
                conversationProviderId = "kwant-pl",
                conversationStoreNumber = "205",
                historicalVerifiedProducts = listOf(product),
                locationHistory = previous,
            ) {}
            assertTrue(case.text, state is AdvisorUiState.Success)
            assertEquals(case.text, 1, continuationCount)
            assertEquals(case.text, if (case.expectedReason == null) 1 else 0,
                httpCalls)
        }
    }

}
