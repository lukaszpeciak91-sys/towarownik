package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot

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
    fun `two local tool calls are allowed in one user turn`() = runBlocking {
        var toolCalls = 0
        var continueCalls = 0
        val controller = controller(
            start = {
                successTool("resp_1", "call_1", "first")
            },
            continueCall = { _, _, _ ->
                continueCalls += 1
                if (continueCalls == 1) {
                    successTool("resp_2", "call_2", "second")
                } else {
                    successAnswer("resp_3", "Done")
                }
            },
            tool = {
                toolCalls += 1
                verifiedResult(it.query)
            },
        )

        val final = controller.runTurn("test", null) { }

        assertEquals(2, toolCalls)
        assertEquals(2, continueCalls)
        assertEquals(
            AdvisorUiState.Success(
                text = "Done",
                responseId = "resp_3",
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
    fun `third local tool request in same turn is rejected`() = runBlocking {
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
                    else -> error("no fourth proxy call allowed")
                }
            },
            tool = {
                toolCalls += 1
                verifiedResult(it.query)
            },
        )

        val final = controller.runTurn("test", null) { }

        assertEquals(2, toolCalls)
        assertEquals(2, continueCalls)
        assertEquals(
            AdvisorUiState.Error(AdvisorError.TOO_MANY_TOOLS),
            final,
        )
    }

    @Test
    fun `tool allowance resets for next user message`() = runBlocking {
        var turn = 0
        var toolCalls = 0
        var continueCalls = 0
        val controller = controller(
            start = {
                turn = 1
                successTool("resp_1a", "call_1a", "first-a")
            },
            message = { previousResponseId, _ ->
                assertEquals("resp_1_final", previousResponseId)
                turn = 2
                successTool("resp_2a", "call_2a", "first-b")
            },
            continueCall = { _, _, _ ->
                continueCalls += 1
                val withinTurn = if (turn == 1) {
                    continueCalls
                } else {
                    continueCalls - 2
                }
                if (withinTurn == 1) {
                    if (turn == 1) {
                        successTool("resp_1b", "call_1b", "second-a")
                    } else {
                        successTool("resp_2b", "call_2b", "second-b")
                    }
                } else {
                    if (turn == 1) {
                        successAnswer("resp_1_final", "First done")
                    } else {
                        successAnswer("resp_2_final", "Second done")
                    }
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

        assertEquals(4, toolCalls)
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
            AdvisorUiState.Error(AdvisorError.OBI),
            final,
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
        tool: suspend (AdvisorToolArguments) -> AdvisorToolExecutionResult = {
            error("tool not expected")
        },
    ) = AdvisorController(
        isConfigured = { configured },
        startAgent = start,
        messageAgent = message,
        continueAgent = continueCall,
        executeTool = tool,
    )

    private fun successAnswer(
        responseId: String,
        text: String,
        productObiks: List<String> = emptyList(),
    ) = AdvisorProxyCallResult.Success(
        AdvisorProxyResult.Answer(
            responseId = responseId,
            text = text,
            productObiks = productObiks,
        ),
    )

    private fun successTool(
        responseId: String,
        callId: String,
        query: String,
    ) = AdvisorProxyCallResult.Success(
        AdvisorProxyResult.ToolRequest(
            responseId = responseId,
            callId = callId,
            arguments = AdvisorToolArguments(
                query = query,
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
    ) = VerifiedProductSnapshot(
        obik = obik,
        name = name,
        stock = stock,
        grossPrice = price,
        productUrl = url,
        verifiedAt = verifiedAt,
    )
}
