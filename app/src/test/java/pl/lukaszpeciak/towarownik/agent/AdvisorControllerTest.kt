package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchCandidate
import pl.lukaszpeciak.towarownik.product.ProductSearchResult
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope

class AdvisorControllerTest {
    @Test
    fun `first user turn uses start and returns final response id`() = runBlocking {
        var starts = 0
        var messages = 0
        val controller = controller(
            start = {
                starts += 1
                successAnswer("resp_first", "Synthetic answer")
            },
            message = { _, _ ->
                messages += 1
                error("message must not run")
            },
        )

        val final = controller.runTurn(
            input = "potrzebuję kleju",
            previousResponseId = null,
        ) { }

        assertEquals(1, starts)
        assertEquals(0, messages)
        assertEquals(
            AdvisorUiState.Success(
                text = "Synthetic answer",
                responseId = "resp_first",
            ),
            final,
        )
    }

    @Test
    fun `follow up turn uses stored previous response id instead of start`() = runBlocking {
        var starts = 0
        val receivedIds = mutableListOf<String>()
        val receivedMessages = mutableListOf<String>()
        val controller = controller(
            start = {
                starts += 1
                error("start must not run")
            },
            message = { previousResponseId, message ->
                receivedIds += previousResponseId
                receivedMessages += message
                successAnswer("resp_second", "Cheaper option")
            },
        )

        val final = controller.runTurn(
            input = "A coś tańszego?",
            previousResponseId = "resp_first",
        ) { }

        assertEquals(0, starts)
        assertEquals(listOf("resp_first"), receivedIds)
        assertEquals(listOf("A coś tańszego?"), receivedMessages)
        assertEquals(
            AdvisorUiState.Success(
                text = "Cheaper option",
                responseId = "resp_second",
            ),
            final,
        )
    }

    @Test
    fun `one tool flow executes once and persists only final answer id upstream`() = runBlocking {
        var toolCalls = 0
        var continueCalls = 0
        val controller = controller(
            start = {
                successTool(
                    responseId = "resp_tool_request",
                    callId = "call_1",
                    query = "klej",
                )
            },
            continueCall = { responseId, callId, result ->
                continueCalls += 1
                assertEquals("resp_tool_request", responseId)
                assertEquals("call_1", callId)
                assertEquals("klej", result.query)
                successAnswer("resp_final", "Final answer")
            },
            tool = {
                toolCalls += 1
                verifiedResult(it.query)
            },
        )
        val states = mutableListOf<AdvisorUiState>()

        val final = controller.runTurn(
            input = "potrzebuję kleju",
            previousResponseId = null,
        ) { states += it }

        assertEquals(1, toolCalls)
        assertEquals(1, continueCalls)
        assertTrue(states.contains(AdvisorUiState.RunningLocalTool))
        assertTrue(states.contains(AdvisorUiState.WaitingForFinalAnswer))
        assertEquals(
            AdvisorUiState.Success(
                text = "Final answer",
                responseId = "resp_final",
            ),
            final,
        )
    }

    @Test
    fun `three local tool calls are allowed in one user turn`() = runBlocking {
        var toolCalls = 0
        var continueCalls = 0
        val controller = controller(
            start = {
                successTool("resp_1", "call_1", "first")
            },
            continueCall = { _, _, _ ->
                continueCalls += 1
                when (continueCalls) {
                    1 -> successTool("resp_2", "call_2", "second")
                    2 -> successTool("resp_3", "call_3", "third")
                    3 -> successAnswer("resp_4", "Done")
                    else -> error("no extra continue expected")
                }
            },
            tool = {
                toolCalls += 1
                verifiedResult(it.query)
            },
        )

        val final = controller.runTurn("test", null) { }

        assertEquals(3, toolCalls)
        assertEquals(3, continueCalls)
        assertEquals(
            AdvisorUiState.Success(
                text = "Done",
                responseId = "resp_4",
            ),
            final,
        )
    }

    @Test
    fun `model selected obik resolves only against locally verified snapshot`() = runBlocking {
        val trusted = snapshot(
            obik = "1234567",
            name = "Trusted local name",
            stock = 4,
            price = BigDecimal("19.99"),
            url = "https://www.obi.pl/p/1234567/trusted",
        )
        val controller = controller(
            start = { successTool("resp_tool", "call_1", "klej") },
            continueCall = { _, _, _ ->
                successAnswer(
                    "resp_final",
                    "Use this.",
                    productObiks = listOf("1234567", "7654321"),
                )
            },
            tool = { verifiedResult(it.query, trusted) },
        )

        val final = controller.runTurn("test", null) { } as AdvisorUiState.Success

        assertEquals(listOf(trusted), final.products)
    }

    @Test
    fun `unknown model obik produces no card`() = runBlocking {
        val controller = controller(
            start = { successTool("resp_tool", "call_1", "klej") },
            continueCall = { _, _, _ ->
                successAnswer(
                    "resp_final",
                    "No matching verified card.",
                    productObiks = listOf("7654321"),
                )
            },
            tool = { verifiedResult(it.query) },
        )

        val final = controller.runTurn("test", null) { } as AdvisorUiState.Success

        assertTrue(final.products.isEmpty())
    }

    @Test
    fun `duplicate selected obiks do not duplicate cards`() = runBlocking {
        val controller = controller(
            start = { successTool("resp_tool", "call_1", "klej") },
            continueCall = { _, _, _ ->
                successAnswer(
                    "resp_final",
                    "One card.",
                    productObiks = listOf("1234567", "1234567"),
                )
            },
            tool = { verifiedResult(it.query) },
        )

        val final = controller.runTurn("test", null) { } as AdvisorUiState.Success

        assertEquals(1, final.products.size)
    }

    @Test
    fun `latest exact lookup wins when same obik is verified twice in one turn`() = runBlocking {
        var toolCall = 0
        val first = snapshot(
            obik = "1234567",
            name = "First exact",
            stock = 5,
            price = BigDecimal("20.00"),
            verifiedAt = 1_000L,
        )
        val latest = snapshot(
            obik = "1234567",
            name = "Latest exact",
            stock = 2,
            price = BigDecimal("18.00"),
            verifiedAt = 2_000L,
        )
        val controller = controller(
            start = { successTool("resp_1", "call_1", "first") },
            continueCall = { _, _, _ ->
                toolCall += 1
                if (toolCall == 1) {
                    successTool("resp_2", "call_2", "second")
                } else {
                    successAnswer(
                        "resp_final",
                        "Latest",
                        productObiks = listOf("1234567"),
                    )
                }
            },
            tool = { args ->
                if (args.query == "first") {
                    verifiedResult(args.query, first)
                } else {
                    verifiedResult(args.query, latest)
                }
            },
        )

        val final = controller.runTurn("test", null) { } as AdvisorUiState.Success

        assertEquals(listOf(latest), final.products)
    }

    @Test
    fun `search actions are accumulated and deduped across one user turn`() = runBlocking {
        var continuation = 0
        val controller = controller(
            start = {
                successTool(
                    "resp_1",
                    "call_1",
                    "Czarne trytytki",
                )
            },
            continueCall = { _, _, _ ->
                continuation += 1
                if (continuation == 1) {
                    successTool(
                        "resp_2",
                        "call_2",
                        "  czarne   TRYTYTKI ",
                    )
                } else {
                    successAnswer(
                        "resp_final",
                        "Znalazłam kilka wariantów.",
                    )
                }
            },
            tool = { args ->
                verifiedResult(args.query).copy(
                    searchActions = listOf(
                        AdvisorSearchAction(
                            query = args.query,
                            storeNumber = args.storeNumber,
                            reportedTotalCount = 27,
                        ),
                    ),
                )
            },
        )

        val final = controller.runTurn(
            input = "Pokaż czarne trytytki",
            previousResponseId = null,
        ) { } as AdvisorUiState.Success

        assertEquals(
            listOf(
                AdvisorSearchAction(
                    query = "Czarne trytytki",
                    storeNumber = "075",
                    reportedTotalCount = 27,
                ),
            ),
            final.searchActions,
        )
    }

    @Test
    fun `verified snapshots do not carry into the next user turn`() = runBlocking {
        var phase = 0
        val controller = controller(
            start = {
                successTool("resp_tool", "call_1", "first")
            },
            message = { previousResponseId, _ ->
                assertEquals("resp_first", previousResponseId)
                successAnswer(
                    "resp_second",
                    "Historical OBIK must not become a current card.",
                    productObiks = listOf("1234567"),
                )
            },
            continueCall = { _, _, _ ->
                phase += 1
                successAnswer(
                    "resp_first",
                    "First answer",
                    productObiks = listOf("1234567"),
                )
            },
            tool = { verifiedResult(it.query) },
        )

        val first = controller.runTurn("first", null) { } as AdvisorUiState.Success
        val second = controller.runTurn("second", first.responseId) { } as AdvisorUiState.Success

        assertEquals(1, first.products.size)
        assertTrue(second.products.isEmpty())
    }

    @Test
    fun `washbasin customer kit uses one batch and selects cards from different groups`() = runBlocking {
        var localToolCalls = 0
        var exactLookups = 0
        var continuations = 0
        val candidatesByQuery = mapOf(
            "silikon sanitarny" to listOf(
                ProductSearchCandidate("1000001", "Silicone A"),
                ProductSearchCandidate("1000002", "Silicone B"),
            ),
            "pistolet do kartuszy" to listOf(
                ProductSearchCandidate("1000003", "Gun"),
            ),
            "narzędzie do wygładzania" to listOf(
                ProductSearchCandidate("1000004", "Finishing tool"),
            ),
        )
        val localTool = FindObiProductsTool(
            searchProducts = { query ->
                ProductSearchResult.Candidates(
                    checkNotNull(candidatesByQuery[query]),
                )
            },
            lookupObik = { obik, storeNumber ->
                exactLookups += 1
                ProductLookupResult.Found(
                    LocalProduct(
                        obik = obik,
                        name = "Verified $obik",
                        stock = exactLookups,
                        grossPrice = BigDecimal("10.00"),
                        productUrl = "https://www.obi.pl/p/$obik/trusted",
                        ean = null,
                        storeNumber = storeNumber,
                        brand = null,
                        shortDescription = null,
                        technicalFacts = emptyList(),
                    ),
                )
            },
            ioDispatcher = Dispatchers.Unconfined,
            now = { 1_000L },
        )
        val batchArguments = AdvisorToolArguments(
            storeNumber = "075",
            queries = listOf(
                AdvisorToolQuery("silikon sanitarny", 2),
                AdvisorToolQuery("pistolet do kartuszy", 1),
                AdvisorToolQuery("narzędzie do wygładzania", 1),
            ),
        )
        val controller = controller(
            start = {
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.ToolRequest(
                        responseId = "resp_batch",
                        callId = "call_batch",
                        arguments = batchArguments,
                    ),
                )
            },
            continueCall = { responseId, callId, result ->
                continuations += 1
                assertEquals("resp_batch", responseId)
                assertEquals("call_batch", callId)
                assertEquals(3, result.results.size)
                assertTrue(
                    result.results.all {
                        it.status == AdvisorQueryResultStatus.VERIFIED
                    },
                )
                successAnswerRefs(
                    responseId = "resp_final",
                    text = "Zweryfikowany zestaw do umywalki.",
                    productRefs = listOf(
                        AdvisorProductRef("075", "1000001"),
                        AdvisorProductRef("075", "1000003"),
                        AdvisorProductRef("075", "1000004"),
                    ),
                )
            },
            tool = { arguments ->
                localToolCalls += 1
                localTool.execute(arguments)
            },
        )

        val final = controller.runTurn(
            "Klient potrzebuje obsadzić umywalkę, potrzebuje silikonu i narzędzi",
            null,
        ) { }

        assertEquals(1, localToolCalls)
        assertEquals(1, continuations)
        assertEquals(4, exactLookups)
        assertTrue(exactLookups <= MAX_TOOL_PRODUCTS)
        assertTrue(final is AdvisorUiState.Success)
        final as AdvisorUiState.Success
        assertEquals(
            listOf("1000001", "1000003", "1000004"),
            final.products.map { it.obik },
        )
    }

    @Test
    fun `fourth local tool request is resolved gracefully without extra OBI work and keeps first three snapshots`() = runBlocking {
        var toolCalls = 0
        var verifiedContinues = 0
        var limitContinues = 0
        val controller = controller(
            start = {
                successTool("resp_1", "call_1", "first")
            },
            continueCall = { _, _, _ ->
                verifiedContinues += 1
                when (verifiedContinues) {
                    1 -> successTool("resp_2", "call_2", "second")
                    2 -> successTool("resp_3", "call_3", "third")
                    3 -> successTool("resp_4", "call_4", "fourth")
                    else -> error("no extra verified continue expected")
                }
            },
            limitContinueCall = { responseId, callId, limit ->
                limitContinues += 1
                assertEquals("resp_4", responseId)
                assertEquals("call_4", callId)
                assertEquals("fourth", limit.query)
                successAnswerRefs(
                    "resp_final",
                    "Use the three products already verified in this turn.",
                    listOf(
                        AdvisorProductRef("075", "1000001"),
                        AdvisorProductRef("075", "1000002"),
                        AdvisorProductRef("075", "1000003"),
                    ),
                )
            },
            tool = { arguments ->
                toolCalls += 1
                verifiedResult(
                    arguments.query,
                    snapshot(
                        obik = (1_000_000 + toolCalls).toString(),
                        name = "Verified $toolCalls",
                        stock = toolCalls,
                        price = BigDecimal("10.00"),
                    ),
                )
            },
        )

        val final = controller.runTurn("test", null) { }

        assertEquals(3, toolCalls)
        assertEquals(3, verifiedContinues)
        assertEquals(1, limitContinues)
        assertTrue(final is AdvisorUiState.Success)
        final as AdvisorUiState.Success
        assertEquals("resp_final", final.responseId)
        assertEquals(
            listOf("1000001", "1000002", "1000003"),
            final.products.map { it.obik },
        )
    }

    @Test
    fun `budget exhaustion cannot enter a fifth local tool loop`() = runBlocking {
        var toolCalls = 0
        var verifiedContinues = 0
        var limitContinues = 0
        val controller = controller(
            start = {
                successTool("resp_1", "call_1", "first")
            },
            continueCall = { _, _, _ ->
                verifiedContinues += 1
                when (verifiedContinues) {
                    1 -> successTool("resp_2", "call_2", "second")
                    2 -> successTool("resp_3", "call_3", "third")
                    3 -> successTool("resp_4", "call_4", "fourth")
                    else -> error("no more verified continues")
                }
            },
            limitContinueCall = { _, _, _ ->
                limitContinues += 1
                successTool("resp_5", "call_5", "fifth")
            },
            tool = {
                toolCalls += 1
                verifiedResult(it.query)
            },
        )

        val final = controller.runTurn("test", null) { }

        assertEquals(3, toolCalls)
        assertEquals(3, verifiedContinues)
        assertEquals(1, limitContinues)
        assertEquals(
            AdvisorUiState.Error(AdvisorError.PROTOCOL),
            final,
        )
    }

    @Test
    fun `tool allowance resets to three for next user message`() = runBlocking {
        var turn = 0
        var withinTurnContinues = 0
        var toolCalls = 0
        val controller = controller(
            start = {
                turn = 1
                withinTurnContinues = 0
                successTool("resp_1a", "call_1a", "first-a")
            },
            message = { previousResponseId, _ ->
                assertEquals("resp_1_final", previousResponseId)
                turn = 2
                withinTurnContinues = 0
                successTool("resp_2a", "call_2a", "first-b")
            },
            continueCall = { _, _, _ ->
                withinTurnContinues += 1
                when (withinTurnContinues) {
                    1 -> if (turn == 1) {
                        successTool("resp_1b", "call_1b", "second-a")
                    } else {
                        successTool("resp_2b", "call_2b", "second-b")
                    }
                    2 -> if (turn == 1) {
                        successTool("resp_1c", "call_1c", "third-a")
                    } else {
                        successTool("resp_2c", "call_2c", "third-b")
                    }
                    3 -> if (turn == 1) {
                        successAnswer("resp_1_final", "First done")
                    } else {
                        successAnswer("resp_2_final", "Second done")
                    }
                    else -> error("no extra continue expected")
                }
            },
            tool = {
                toolCalls += 1
                verifiedResult(it.query)
            },
        )

        val first = controller.runTurn("first", null) { }
        val second = controller.runTurn(
            "second",
            (first as AdvisorUiState.Success).responseId,
        ) { }

        assertEquals(6, toolCalls)
        assertEquals(
            "resp_2_final",
            (second as AdvisorUiState.Success).responseId,
        )
    }

    @Test
    fun `local OBI failure stops without continue payload`() = runBlocking {
        var continueCalls = 0
        val controller = controller(
            start = {
                successTool("resp_1", "call_1", "klej")
            },
            continueCall = { _, _, _ ->
                continueCalls += 1
                error("continue must not run")
            },
            tool = {
                AdvisorToolExecutionResult.Failure
            },
        )

        val final = controller.runTurn("test", null) { }

        assertEquals(0, continueCalls)
        assertEquals(
            AdvisorUiState.Error(AdvisorError.PRODUCT_PROVIDER),
            final,
        )
    }

    @Test
    fun `OBI Wielicka user text authorizes one-off 003 without changing conversation branch`() =
        runBlocking {
            val toolBranches = mutableListOf<String>()
            val continueBranches = mutableListOf<String>()
            val controller = controller(
                start = {
                    successTool(
                        responseId = "resp_tool",
                        callId = "call_tool",
                        query = "klej",
                        storeNumber = "075",
                    )
                },
                continueCall = { _, _, result ->
                    assertEquals("003", result.storeNumber)
                    successAnswer("resp_final", "Done")
                },
                obiTool = { arguments ->
                    toolBranches += arguments.storeNumber
                    assertEquals(null, arguments.requestedBranch)
                    verifiedResult(
                        arguments.query,
                        snapshot(
                            obik = "3496072",
                            name = "Product",
                            stock = 3,
                            price = BigDecimal("12.99"),
                            storeNumber = arguments.storeNumber,
                        ),
                    )
                },
                onContinueStore = { continueBranches += it },
            )

            controller.runTurn(
                input = "czy jest w OBI Kraków Wielicka?",
                previousResponseId = null,
                conversationStoreNumber = "075",
                conversationProviderId = "obi-pl",
            ) { }

            assertEquals(listOf("003"), toolBranches)
            assertEquals(listOf("075"), continueBranches)
        }

    @Test
    fun `incidental OBI metadata cannot authorize branch through model hint`() =
        runBlocking {
            var toolCalls = 0
            var rejected = false
            val controller = controller(
                start = {
                    AdvisorProxyCallResult.Success(
                        AdvisorProxyResult.ToolRequest(
                            responseId = "resp_tool",
                            callId = "call_tool",
                            arguments = AdvisorToolArguments(
                                providerId = "obi-pl",
                                storeNumber = "003",
                                requestedBranch = "Wielicka",
                                queries = listOf(
                                    AdvisorToolQuery(
                                        "szukam długą listwę",
                                        1,
                                    ),
                                ),
                            ),
                        ),
                    )
                },
                rejectedContinueCall = { _, _, _ ->
                    rejected = true
                    successAnswer("resp_final", "Clarify")
                },
                obiTool = {
                    toolCalls += 1
                    error("incidental metadata must not authorize a branch")
                },
            )

            controller.runTurn(
                input = "szukam długą listwę",
                previousResponseId = null,
                conversationStoreNumber = "075",
                conversationProviderId = "obi-pl",
            ) { }

            assertEquals(0, toolCalls)
            assertTrue(rejected)
        }

    @Test
    fun `ambiguous OBI Krakow cannot be bypassed by model store number`() =
        runBlocking {
            var toolCalls = 0
            var rejected = false
            val controller = controller(
                start = {
                    successTool(
                        responseId = "resp_tool",
                        callId = "call_tool",
                        query = "klej",
                        storeNumber = "003",
                    )
                },
                rejectedContinueCall = { _, _, _ ->
                    rejected = true
                    successAnswer("resp_final", "Który market?")
                },
                obiTool = {
                    toolCalls += 1
                    error("ambiguous branch must not execute")
                },
            )

            controller.runTurn(
                input = "sprawdź OBI Kraków",
                previousResponseId = null,
                conversationStoreNumber = "075",
                conversationProviderId = "obi-pl",
            ) { }

            assertEquals(0, toolCalls)
            assertTrue(rejected)
        }

    @Test
    fun `KWANT Zamosc user text resolves one-off branch 128 without changing conversation branch`() =
        runBlocking {
            val toolBranches = mutableListOf<String>()
            val continueBranches = mutableListOf<String>()
            val controller = controller(
                start = {
                    AdvisorProxyCallResult.Success(
                        AdvisorProxyResult.ToolRequest(
                            responseId = "resp_tool",
                            callId = "call_tool",
                            arguments = AdvisorToolArguments(
                                providerId = "kwant-pl",
                                storeNumber = "205",
                                requestedBranch = "Zamość",
                                queries = listOf(
                                    AdvisorToolQuery("MBN116E", 1),
                                ),
                            ),
                        ),
                    )
                },
                continueCall = { _, _, result ->
                    assertEquals("kwant-pl", result.providerId)
                    assertEquals("128", result.storeNumber)
                    successAnswer("resp_final", "Done")
                },
                providerTool = { arguments ->
                    toolBranches += arguments.storeNumber
                    assertEquals(null, arguments.requestedBranch)
                    AdvisorToolExecutionResult.Success(
                        result = AdvisorVerifiedToolResult(
                            providerId = "kwant-pl",
                            storeNumber = arguments.storeNumber,
                            results = emptyList(),
                        ),
                        snapshots = emptyList(),
                    )
                },
                onContinueStore = { continueBranches += it },
            )

            controller.runTurn(
                input = "ile tego jest w Kwant Zamość?",
                previousResponseId = null,
                conversationStoreNumber = "205",
                conversationProviderId = "kwant-pl",
            ) { }

            assertEquals(listOf("128"), toolBranches)
            assertEquals(listOf("205"), continueBranches)
        }

    @Test
    fun `KWANT Sacz current alias stays on branch 205`() = runBlocking {
        val toolBranches = mutableListOf<String>()
        val controller = controller(
            start = {
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.ToolRequest(
                        responseId = "resp_tool",
                        callId = "call_tool",
                        arguments = AdvisorToolArguments(
                            providerId = "kwant-pl",
                            storeNumber = "205",
                            requestedBranch = "Nowy Sącz",
                            queries = listOf(
                                AdvisorToolQuery("MBN116E", 1),
                            ),
                        ),
                    ),
                )
            },
            continueCall = { _, _, _ ->
                successAnswer("resp_final", "Done")
            },
            providerTool = { arguments ->
                toolBranches += arguments.storeNumber
                AdvisorToolExecutionResult.Success(
                    result = AdvisorVerifiedToolResult(
                        providerId = "kwant-pl",
                        storeNumber = arguments.storeNumber,
                        results = emptyList(),
                    ),
                    snapshots = emptyList(),
                )
            },
        )

        controller.runTurn(
            input = "a ile mamy tego w Sączu?",
            previousResponseId = null,
            conversationStoreNumber = "205",
            conversationProviderId = "kwant-pl",
        ) { }

        assertEquals(listOf("205"), toolBranches)
    }

    @Test
    fun `model requestedBranch cannot authorize location absent from user text`() =
        runBlocking {
            var toolCalls = 0
            var rejected = false
            val controller = controller(
                start = {
                    AdvisorProxyCallResult.Success(
                        AdvisorProxyResult.ToolRequest(
                            responseId = "resp_tool",
                            callId = "call_tool",
                            arguments = AdvisorToolArguments(
                                providerId = "kwant-pl",
                                storeNumber = "205",
                                requestedBranch = "Zamość",
                                queries = listOf(
                                    AdvisorToolQuery("MBN116E", 1),
                                ),
                            ),
                        ),
                    )
                },
                rejectedContinueCall = { _, _, _ ->
                    rejected = true
                    successAnswer("resp_final", "Clarify")
                },
                providerTool = {
                    toolCalls += 1
                    error("model hint must not authorize a branch")
                },
            )

            controller.runTurn(
                input = "sprawdź MBN116E",
                previousResponseId = null,
                conversationStoreNumber = "205",
                conversationProviderId = "kwant-pl",
            ) { }

            assertEquals(0, toolCalls)
            assertTrue(rejected)
        }

    @Test
    fun `unknown explicit location does not fall back to current branch`() =
        runBlocking {
            var toolCalls = 0
            var rejected = false
            val controller = controller(
                start = {
                    successTool(
                        responseId = "resp_tool",
                        callId = "call_tool",
                        query = "klej",
                        storeNumber = "075",
                    )
                },
                rejectedContinueCall = { _, _, _ ->
                    rejected = true
                    successAnswer("resp_final", "Clarify")
                },
                obiTool = {
                    toolCalls += 1
                    error("unknown location must not use current branch")
                },
            )

            controller.runTurn(
                input = "sprawdź w OBI Zakopane",
                previousResponseId = null,
                conversationStoreNumber = "075",
                conversationProviderId = "obi-pl",
            ) { }

            assertEquals(0, toolCalls)
            assertTrue(rejected)
        }

    @Test
    fun `KWANT conversation executes provider tool and selects current turn card`() = runBlocking {
        var toolCalls = 0
        val snapshot = snapshot(
            obik = "580",
            name = "Wyłącznik",
            stock = 140,
            price = BigDecimal("14.55"),
            storeNumber = "205",
        ).copy(
            providerId = "kwant-pl",
            productId = "580",
            branchId = "205",
            articleNumber = "MBN116E/HAG",
        )
        val controller = controller(
            start = {
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.ToolRequest(
                        responseId = "resp_kwant_tool",
                        callId = "call_kwant",
                        arguments = AdvisorToolArguments(
                            providerId = "kwant-pl",
                            storeNumber = "205",
                            queries = listOf(
                                AdvisorToolQuery("MBN116E", 1),
                            ),
                        ),
                    ),
                )
            },
            continueCall = { _, _, _ ->
                successAnswerRefs(
                    responseId = "resp_kwant_final",
                    text = "Mam produkt.",
                    productRefs = listOf(
                        AdvisorProductRef(
                            storeNumber = "205",
                            obik = "580",
                            providerId = "kwant-pl",
                        ),
                    ),
                )
            },
            tool = { arguments ->
                toolCalls += 1
                assertEquals("kwant-pl", arguments.providerId)
                assertEquals("205", arguments.branchId)
                AdvisorToolExecutionResult.Success(
                    result = AdvisorVerifiedToolResult(
                        providerId = "kwant-pl",
                        storeNumber = "205",
                        results = listOf(
                            AdvisorVerifiedQueryResult(
                                query = "MBN116E",
                                status =
                                    AdvisorQueryResultStatus.VERIFIED,
                                products = listOf(
                                    AdvisorVerifiedProduct(
                                        obik = "580",
                                        productId = "580",
                                        articleNumber = "MBN116E/HAG",
                                        name = "Wyłącznik",
                                        stock = 140,
                                        price = BigDecimal("14.55"),
                                        priceScope = "online",
                                    ),
                                ),
                            ),
                        ),
                    ),
                    snapshots = listOf(snapshot),
                )
            },
        )

        val result = controller.runTurn(
            input = "Sprawdź MBN116E",
            previousResponseId = null,
            conversationStoreNumber = "205",
            conversationProviderId = "kwant-pl",
        ) { } as AdvisorUiState.Success

        assertEquals(1, toolCalls)
        assertEquals(listOf(snapshot), result.products)
    }

    @Test
    fun `KWANT conversation rejects OBI provider tool request before local execution`() = runBlocking {
        var toolCalls = 0
        var rejectedProvider: String? = null
        val controller = controller(
            start = {
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.ToolRequest(
                        responseId = "resp_wrong_provider",
                        callId = "call_wrong_provider",
                        arguments = AdvisorToolArguments(
                            providerId = "obi-pl",
                            storeNumber = "075",
                            queries = listOf(
                                AdvisorToolQuery("klej", 1),
                            ),
                        ),
                    ),
                )
            },
            rejectedContinueCall = { _, _, rejected ->
                rejectedProvider = rejected.providerId
                successAnswer(
                    responseId = "resp_final",
                    text = "Provider mismatch.",
                )
            },
            tool = {
                toolCalls += 1
                error("OBI tool must not run for KWANT conversation")
            },
        )

        controller.runTurn(
            input = "Sprawdź produkt",
            previousResponseId = null,
            conversationStoreNumber = "205",
            conversationProviderId = "kwant-pl",
        ) { }

        assertEquals(0, toolCalls)
        assertEquals("obi-pl", rejectedProvider)
    }

    @Test
    fun `OBI conversation executes only legacy OBI local tool path`() = runBlocking {
        var obiCalls = 0
        var providerCalls = 0
        val controller = controller(
            start = {
                successTool(
                    responseId = "resp_obi_tool",
                    callId = "call_obi",
                    query = "klej",
                    storeNumber = "075",
                )
            },
            continueCall = { _, _, _ ->
                successAnswer(
                    responseId = "resp_obi_final",
                    text = "OBI done",
                    productObiks = listOf("1234567"),
                )
            },
            obiTool = { arguments ->
                obiCalls += 1
                verifiedResult(
                    arguments.query,
                    snapshot(
                        obik = "1234567",
                        name = "Legacy OBI verified",
                        stock = 7,
                        price = BigDecimal("12.99"),
                        storeNumber = "075",
                    ),
                )
            },
            providerTool = {
                providerCalls += 1
                error("provider tool must not run for OBI")
            },
        )

        val result = controller.runTurn(
            input = "Sprawdź klej",
            previousResponseId = null,
            conversationStoreNumber = "075",
            conversationProviderId = "obi-pl",
        ) { } as AdvisorUiState.Success

        assertEquals(1, obiCalls)
        assertEquals(0, providerCalls)
        assertEquals("1234567", result.products.single().obik)
        assertEquals("075", result.products.single().storeNumber)
    }

    @Test
    fun `KWANT conversation executes only provider local tool path`() = runBlocking {
        var obiCalls = 0
        var providerCalls = 0
        val kwantSnapshot = VerifiedProductSnapshot(
            obik = "580",
            name = "Wyłącznik",
            stock = 140,
            grossPrice = BigDecimal("14.55"),
            productUrl = "https://kwant.net.pl/produkt/test-580",
            verifiedAt = 1234L,
            storeNumber = "205",
            providerId = "kwant-pl",
            productId = "580",
            branchId = "205",
            articleNumber = "MBN116E/HAG",
            priceScope = ProviderPriceScope.ONLINE,
        )
        val controller = controller(
            start = {
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.ToolRequest(
                        responseId = "resp_kwant_tool",
                        callId = "call_kwant",
                        arguments = AdvisorToolArguments(
                            providerId = "kwant-pl",
                            storeNumber = "205",
                            queries = listOf(
                                AdvisorToolQuery("MBN116E", 1),
                            ),
                        ),
                    ),
                )
            },
            continueCall = { _, _, _ ->
                successAnswerRefs(
                    responseId = "resp_kwant_final",
                    text = "KWANT done",
                    productRefs = listOf(
                        AdvisorProductRef(
                            storeNumber = "205",
                            obik = "580",
                            providerId = "kwant-pl",
                        ),
                    ),
                )
            },
            obiTool = {
                obiCalls += 1
                error("OBI tool must not run for KWANT")
            },
            providerTool = { arguments ->
                providerCalls += 1
                assertEquals("kwant-pl", arguments.providerId)
                assertEquals("205", arguments.storeNumber)
                AdvisorToolExecutionResult.Success(
                    result = AdvisorVerifiedToolResult(
                        providerId = "kwant-pl",
                        storeNumber = "205",
                        results = listOf(
                            AdvisorVerifiedQueryResult(
                                query = "MBN116E",
                                status = AdvisorQueryResultStatus.VERIFIED,
                                products = listOf(
                                    AdvisorVerifiedProduct(
                                        obik = "580",
                                        productId = "580",
                                        articleNumber = "MBN116E/HAG",
                                        name = "Wyłącznik",
                                        stock = 140,
                                        price = BigDecimal("14.55"),
                                        priceScope = "online",
                                    ),
                                ),
                            ),
                        ),
                    ),
                    snapshots = listOf(kwantSnapshot),
                )
            },
        )

        val result = controller.runTurn(
            input = "Sprawdź MBN116E",
            previousResponseId = null,
            conversationStoreNumber = "205",
            conversationProviderId = "kwant-pl",
        ) { } as AdvisorUiState.Success

        assertEquals(0, obiCalls)
        assertEquals(1, providerCalls)
        assertEquals("kwant-pl", result.products.single().providerId)
        assertEquals("205", result.products.single().branchId)
        assertEquals("580", result.products.single().productId)
        assertEquals(
            ProviderPriceScope.ONLINE,
            result.products.single().priceScope,
        )
    }

    @Test
    fun `proxy failure stops with no retry loop`() = runBlocking {
        var starts = 0
        var messages = 0
        val controller = controller(
            start = {
                starts += 1
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.NETWORK,
                )
            },
            message = { _, _ ->
                messages += 1
                error("message must not run")
            },
        )

        val final = controller.runTurn("test", null) { }

        assertEquals(1, starts)
        assertEquals(0, messages)
        assertEquals(
            AdvisorUiState.Error(AdvisorError.NETWORK),
            final,
        )
    }

    @Test
    fun `authentication failure stays distinct and carries safe diagnostic`() = runBlocking {
        val controller = controller(
            start = {
                AdvisorProxyCallResult.Failure(
                    kind = AdvisorProxyFailureKind.AUTHENTICATION,
                    httpStatus = 401,
                    proxyErrorCode = "unauthorized",
                    endpoint = "start",
                )
            },
        )

        val final = controller.runTurn("test", null) { }

        assertEquals(
            AdvisorUiState.Error(
                error = AdvisorError.AUTHENTICATION,
                diagnostic = AdvisorFailureDiagnostic(
                    kind = AdvisorProxyFailureKind.AUTHENTICATION,
                    httpStatus = 401,
                    proxyErrorCode = "unauthorized",
                    endpoint = "start",
                ),
            ),
            final,
        )
    }

    @Test
    fun `new conversation with null previous id cannot reuse old response id`() = runBlocking {
        var starts = 0
        var messages = 0
        val controller = controller(
            start = {
                starts += 1
                successAnswer("resp_new_$starts", "done")
            },
            message = { _, _ ->
                messages += 1
                error("message must not run for new conversations")
            },
        )

        controller.runTurn("first case", null) { }
        controller.runTurn("second case", null) { }

        assertEquals(2, starts)
        assertEquals(0, messages)
    }

    @Test
    fun `missing build token fails locally before any proxy call`() = runBlocking {
        var starts = 0
        var messages = 0
        val controller = controller(
            configured = false,
            start = {
                starts += 1
                successAnswer("resp", "must not happen")
            },
            message = { _, _ ->
                messages += 1
                successAnswer("resp", "must not happen")
            },
        )

        val final = controller.runTurn("test", null) { }

        assertEquals(0, starts)
        assertEquals(0, messages)
        assertEquals(
            AdvisorUiState.Error(AdvisorError.NOT_CONFIGURED),
            final,
        )
    }

    @Test
    fun `conversation selected store is authorized without literal mention`() = runBlocking {
        var toolCalls = 0
        val seenStores = mutableListOf<String>()
        val controller = controller(
            start = {
                successTool(
                    responseId = "resp_tool",
                    callId = "call_tool",
                    query = "klej",
                    storeNumber = "074",
                )
            },
            continueCall = { _, _, result ->
                assertEquals("074", result.storeNumber)
                successAnswer("resp_final", "Done")
            },
            tool = {
                toolCalls += 1
                verifiedResult(
                    it.query,
                    snapshot(
                        obik = "3496072",
                        name = "Product",
                        stock = 13,
                        price = BigDecimal("12.99"),
                        storeNumber = it.storeNumber,
                    ),
                )
            },
            onStartStore = { seenStores += it },
            onContinueStore = { seenStores += it },
        )

        controller.runTurn(
            input = "Sprawdź klej",
            previousResponseId = null,
            conversationStoreNumber = "074",
        ) { }

        assertEquals(1, toolCalls)
        assertEquals(listOf("074", "074"), seenStores)
    }

    @Test
    fun `non mentioned alternate store is rejected before OBI tool`() = runBlocking {
        var toolCalls = 0
        var rejectedStore: String? = null
        val controller = controller(
            start = {
                successTool(
                    "resp_tool",
                    "call_tool",
                    "klej",
                    storeNumber = "074",
                )
            },
            rejectedContinueCall = { _, _, rejected ->
                rejectedStore = rejected.storeNumber
                successAnswer("resp_final", "Podaj numer marketu")
            },
            tool = {
                toolCalls += 1
                verifiedResult(it.query)
            },
        )

        controller.runTurn(
            input = "Sprawdź w innym markecie",
            previousResponseId = null,
            conversationStoreNumber = "075",
        ) { }

        assertEquals(0, toolCalls)
        assertEquals("074", rejectedStore)
    }

    @Test
    fun `two explicitly mentioned stores can both be queried`() = runBlocking {
        val requestedStores = mutableListOf<String>()
        var continuation = 0
        val controller = controller(
            start = {
                successTool("resp_1", "call_1", "klej", "074")
            },
            continueCall = { _, _, _ ->
                continuation += 1
                if (continuation == 1) {
                    successTool("resp_2", "call_2", "klej", "075")
                } else {
                    successAnswer("resp_final", "Done")
                }
            },
            tool = {
                requestedStores += it.storeNumber
                verifiedResult(
                    it.query,
                    snapshot(
                        "3496072",
                        "Product",
                        if (it.storeNumber == "074") 13 else 25,
                        BigDecimal("12.99"),
                        storeNumber = it.storeNumber,
                    ),
                )
            },
        )

        controller.runTurn(
            input = "Porównaj 074 i 075",
            previousResponseId = null,
            conversationStoreNumber = "075",
        ) { }

        assertEquals(listOf("074", "075"), requestedStores)
    }

    @Test
    fun `embedded digits and unsupported stores are not authorized`() = runBlocking {
        listOf(
            "Sprawdź 1074" to "074",
            "Sprawdź 999" to "999",
        ).forEach { (message, requestedStore) ->
            var toolCalls = 0
            var rejected = false
            val controller = controller(
                start = {
                    successTool(
                        "resp_tool",
                        "call_tool",
                        "klej",
                        requestedStore,
                    )
                },
                rejectedContinueCall = { _, _, _ ->
                    rejected = true
                    successAnswer("resp_final", "Clarify")
                },
                tool = {
                    toolCalls += 1
                    verifiedResult(it.query)
                },
            )

            controller.runTurn(
                input = message,
                previousResponseId = null,
                conversationStoreNumber = "075",
            ) { }

            assertEquals(0, toolCalls)
            assertTrue(rejected)
        }
    }

    @Test
    fun `store mentioned only in previous user turn does not authorize current turn`() = runBlocking {
        var toolCalls = 0
        var rejected = false
        val controller = controller(
            start = {
                successAnswer("resp_first", "Noted")
            },
            message = { _, _ ->
                successTool(
                    "resp_tool",
                    "call_tool",
                    "klej",
                    storeNumber = "074",
                )
            },
            rejectedContinueCall = { _, _, _ ->
                rejected = true
                successAnswer("resp_final", "Need number")
            },
            tool = {
                toolCalls += 1
                verifiedResult(it.query)
            },
        )

        val first = controller.runTurn(
            input = "Zapamiętaj 074",
            previousResponseId = null,
            conversationStoreNumber = "075",
        ) { } as AdvisorUiState.Success

        controller.runTurn(
            input = "Sprawdź tam klej",
            previousResponseId = first.responseId,
            conversationStoreNumber = "075",
        ) { }

        assertEquals(0, toolCalls)
        assertTrue(rejected)
    }

    @Test
    fun `same OBIK from two stores resolves to two current turn cards`() = runBlocking {
        var continuation = 0
        val controller = controller(
            start = {
                successTool("resp_1", "call_1", "x", "074")
            },
            continueCall = { _, _, _ ->
                continuation += 1
                if (continuation == 1) {
                    successTool("resp_2", "call_2", "x", "075")
                } else {
                    successAnswerRefs(
                        "resp_final",
                        "Compare",
                        listOf(
                            AdvisorProductRef("074", "3496072"),
                            AdvisorProductRef("075", "3496072"),
                            AdvisorProductRef("078", "3496072"),
                        ),
                    )
                }
            },
            tool = {
                verifiedResult(
                    it.query,
                    snapshot(
                        "3496072",
                        "Product",
                        if (it.storeNumber == "074") 13 else 25,
                        BigDecimal("12.99"),
                        storeNumber = it.storeNumber,
                    ),
                )
            },
        )

        val result = controller.runTurn(
            input = "Porównaj 074 i 075",
            previousResponseId = null,
            conversationStoreNumber = "075",
        ) { } as AdvisorUiState.Success

        assertEquals(
            listOf("074", "075"),
            result.products.map { it.storeNumber },
        )
    }

    @Test
    fun `usage is observed before a later continuation failure`() = runBlocking {
        val observed = mutableListOf<Pair<AdvisorUsage?, Long>>()
        var toolAssistedSignals = 0
        val paidUsage = AdvisorUsage(
            model = "gpt-6-luna",
            requestType = AdvisorRequestType.START,
            inputTokens = 100,
            cachedInputTokens = 0,
            outputTokens = 10,
            reasoningTokens = 2,
            totalTokens = 110,
            estimatedCostUsd = BigDecimal("0.000032"),
            pricingVersion =
                "openai-gpt-6-luna-2026-09-27-v1",
        )
        val controller = controller(
            start = {
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.ToolRequest(
                        responseId = "resp_paid",
                        callId = "call_paid",
                        arguments = AdvisorToolArguments(
                            query = "klej",
                            storeNumber = "075",
                            limit = 1,
                        ),
                        usage = paidUsage,
                    ),
                )
            },
            continueCall = { _, _, _ ->
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.SERVICE,
                )
            },
            tool = {
                verifiedResult(it.query)
            },
        )

        val final = controller.runTurn(
            input = "test",
            previousResponseId = null,
            onOpenAiResponse = { usage, webSearchCalls ->
                observed += usage to webSearchCalls
            },
            onToolRequestObserved = {
                toolAssistedSignals += 1
            },
        ) { }

        assertEquals(
            AdvisorUiState.Error(AdvisorError.SERVICE),
            final,
        )
        assertEquals(listOf(paidUsage to 0L), observed)
        assertEquals(1, toolAssistedSignals)
    }

    private fun controller(
        configured: Boolean = true,
        start: suspend (String) -> AdvisorProxyCallResult = {
            error("start not expected")
        },
        message: suspend (String, String) -> AdvisorProxyCallResult = { _, _ ->
            error("message not expected")
        },
        continueCall: suspend (
            String,
            String,
            AdvisorVerifiedToolResult,
        ) -> AdvisorProxyCallResult = { _, _, _ ->
            error("continue not expected")
        },
        rejectedContinueCall: suspend (
            String,
            String,
            AdvisorToolContinuation.RejectedStore,
        ) -> AdvisorProxyCallResult = { _, _, _ ->
            error("rejected continue not expected")
        },
        limitContinueCall: suspend (
            String,
            String,
            AdvisorToolContinuation.LocalToolLimitReached,
        ) -> AdvisorProxyCallResult = { _, _, _ ->
            error("limit continue not expected")
        },
        tool: suspend (AdvisorToolArguments) -> AdvisorToolExecutionResult = {
            error("tool not expected")
        },
        obiTool: suspend (AdvisorToolArguments) -> AdvisorToolExecutionResult =
            tool,
        providerTool: suspend (AdvisorToolArguments) -> AdvisorToolExecutionResult =
            tool,
        branchDirectory: suspend (ProviderId) -> ProviderBranchResult = {
            testBranchDirectory(it)
        },
        onStartStore: (String) -> Unit = {},
        onMessageStore: (String) -> Unit = {},
        onContinueStore: (String) -> Unit = {},
    ) = AdvisorController(
        isConfigured = { configured },
        startAgent = { input, _, branchId ->
            onStartStore(branchId)
            start(input)
        },
        messageAgent = { previousResponseId, input, _, branchId ->
            onMessageStore(branchId)
            message(previousResponseId, input)
        },
        continueAgent = {
                responseId,
                callId,
                _,
                branchId,
                _,
                continuation,
            ->
            onContinueStore(branchId)
            when (continuation) {
                is AdvisorToolContinuation.Verified ->
                    continueCall(
                        responseId,
                        callId,
                        continuation.result,
                    )
                is AdvisorToolContinuation.RejectedStore ->
                    rejectedContinueCall(
                        responseId,
                        callId,
                        continuation,
                    )
                is AdvisorToolContinuation.LocalToolLimitReached ->
                    limitContinueCall(
                        responseId,
                        callId,
                        continuation,
                    )
            }
        },
        executeObiTool = obiTool,
        executeProviderTool = providerTool,
        branchDirectory = branchDirectory,
    )

    private fun testBranchDirectory(
        providerId: ProviderId,
    ): ProviderBranchResult =
        when (providerId) {
            OBI_PROVIDER_ID ->
                pl.lukaszpeciak.towarownik.product.provider
                    .ObiProductProvider()
                    .branches()

            ProviderId("kwant-pl") ->
                ProviderBranchResult.Available(
                    listOf(
                        ProviderBranch(
                            branchId = BranchId("205"),
                            name = "Nowy Sącz",
                            address = "33-300 Tarnowska 149",
                        ),
                        ProviderBranch(
                            branchId = BranchId("128"),
                            name = "Zamość",
                            address = "22-400 Braterstwa Broni 60",
                        ),
                    ),
                )

            else ->
                ProviderBranchResult.Available(emptyList())
        }

    private fun successAnswer(
        responseId: String,
        text: String,
        productObiks: List<String> = emptyList(),
        storeNumber: String = "075",
    ) = AdvisorProxyCallResult.Success(
        AdvisorProxyResult.Answer(
            responseId = responseId,
            text = text,
            productRefs = productObiks.map {
                AdvisorProductRef(
                    storeNumber = storeNumber,
                    obik = it,
                )
            },
        ),
    )

    private fun successAnswerRefs(
        responseId: String,
        text: String,
        productRefs: List<AdvisorProductRef>,
    ) = AdvisorProxyCallResult.Success(
        AdvisorProxyResult.Answer(
            responseId = responseId,
            text = text,
            productRefs = productRefs,
        ),
    )

    private fun successTool(
        responseId: String,
        callId: String,
        query: String,
        storeNumber: String = "075",
    ) = AdvisorProxyCallResult.Success(
        AdvisorProxyResult.ToolRequest(
            responseId = responseId,
            callId = callId,
            arguments = AdvisorToolArguments(
                query = query,
                storeNumber = storeNumber,
                limit = 5,
            ),
        ),
    )

    private fun verifiedResult(
        query: String,
        snapshot: VerifiedProductSnapshot = snapshot(
            obik = "1234567",
            name = "Synthetic product",
            stock = 1,
            price = BigDecimal("9.99"),
        ),
    ) = AdvisorToolExecutionResult.Success(
        result = AdvisorVerifiedToolResult(
            query = query,
            storeNumber = snapshot.storeNumber,
            products = listOf(
                AdvisorVerifiedProduct(
                    obik = snapshot.obik,
                    name = snapshot.name,
                    stock = snapshot.stock,
                    price = snapshot.grossPrice,
                ),
            ),
        ),
        snapshots = listOf(snapshot),
    )

    private fun snapshot(
        obik: String,
        name: String,
        stock: Int?,
        price: BigDecimal?,
        url: String = "https://www.obi.pl/p/$obik/trusted",
        verifiedAt: Long = 1_000L,
        storeNumber: String = "075",
    ) = VerifiedProductSnapshot(
        obik = obik,
        name = name,
        stock = stock,
        grossPrice = price,
        productUrl = url,
        verifiedAt = verifiedAt,
        storeNumber = storeNumber,
    )
}
