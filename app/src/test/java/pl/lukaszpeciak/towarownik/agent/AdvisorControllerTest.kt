package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorControllerTest {
    @Test
    fun `direct answer uses one proxy start and no local tool`() = runBlocking {
        var starts = 0
        var continues = 0
        var tools = 0
        val controller = controller(
            start = {
                starts += 1
                successAnswer("resp_1", "Synthetic answer")
            },
            continueCall = { _, _, _ ->
                continues += 1
                error("continue must not run")
            },
            tool = {
                tools += 1
                error("tool must not run")
            },
        )
        val states = mutableListOf<AdvisorUiState>()

        controller.runCase("potrzebuję kleju") { states += it }

        assertEquals(1, starts)
        assertEquals(0, continues)
        assertEquals(0, tools)
        assertEquals(
            AdvisorUiState.Success("Synthetic answer"),
            states.last(),
        )
    }

    @Test
    fun `one tool flow executes once then continues to answer`() = runBlocking {
        var toolCalls = 0
        var continueCalls = 0
        val controller = controller(
            start = {
                successTool(
                    responseId = "resp_1",
                    callId = "call_1",
                    query = "klej",
                )
            },
            continueCall = { responseId, callId, result ->
                continueCalls += 1
                assertEquals("resp_1", responseId)
                assertEquals("call_1", callId)
                assertEquals("klej", result.query)
                successAnswer("resp_2", "Final answer")
            },
            tool = {
                toolCalls += 1
                verifiedResult(it.query)
            },
        )
        val states = mutableListOf<AdvisorUiState>()

        controller.runCase("potrzebuję kleju") { states += it }

        assertEquals(1, toolCalls)
        assertEquals(1, continueCalls)
        assertTrue(states.contains(AdvisorUiState.RunningLocalTool))
        assertTrue(states.contains(AdvisorUiState.WaitingForFinalAnswer))
        assertEquals(
            AdvisorUiState.Success("Final answer"),
            states.last(),
        )
    }

    @Test
    fun `two tool flow executes exactly two local tools`() = runBlocking {
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

        var finalState: AdvisorUiState = AdvisorUiState.Idle
        controller.runCase("test") { finalState = it }

        assertEquals(2, toolCalls)
        assertEquals(2, continueCalls)
        assertEquals(
            AdvisorUiState.Success("Done"),
            finalState,
        )
    }

    @Test
    fun `third tool request is not executed and does not trigger fourth proxy call`() = runBlocking {
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

        var finalState: AdvisorUiState = AdvisorUiState.Idle
        controller.runCase("test") { finalState = it }

        assertEquals(2, toolCalls)
        assertEquals(2, continueCalls)
        assertEquals(
            AdvisorUiState.Error(ADVISOR_TOO_MANY_TOOLS_MESSAGE),
            finalState,
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

        var finalState: AdvisorUiState = AdvisorUiState.Idle
        controller.runCase("test") { finalState = it }

        assertEquals(0, continueCalls)
        assertEquals(
            AdvisorUiState.Error(ADVISOR_OBI_ERROR_MESSAGE),
            finalState,
        )
    }

    @Test
    fun `proxy failure stops with no retry loop`() = runBlocking {
        var starts = 0
        var continueCalls = 0
        val controller = controller(
            start = {
                starts += 1
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.NETWORK,
                )
            },
            continueCall = { _, _, _ ->
                continueCalls += 1
                error("continue must not run")
            },
            tool = {
                error("tool must not run")
            },
        )

        var finalState: AdvisorUiState = AdvisorUiState.Idle
        controller.runCase("test") { finalState = it }

        assertEquals(1, starts)
        assertEquals(0, continueCalls)
        assertEquals(
            AdvisorUiState.Error(ADVISOR_NETWORK_ERROR_MESSAGE),
            finalState,
        )
    }

    @Test
    fun `new case starts from scratch and does not reuse old response ids`() = runBlocking {
        var caseNumber = 0
        val continuedResponseIds = mutableListOf<String>()
        val controller = controller(
            start = {
                caseNumber += 1
                successTool(
                    responseId = "resp_case_$caseNumber",
                    callId = "call_case_$caseNumber",
                    query = "query_$caseNumber",
                )
            },
            continueCall = { responseId, _, _ ->
                continuedResponseIds += responseId
                successAnswer(
                    responseId = "answer_$responseId",
                    text = "done",
                )
            },
            tool = {
                verifiedResult(it.query)
            },
        )

        controller.runCase("first") { }
        controller.runCase("second") { }

        assertEquals(
            listOf("resp_case_1", "resp_case_2"),
            continuedResponseIds,
        )
    }

    @Test
    fun `missing build token fails locally before any proxy call`() = runBlocking {
        var starts = 0
        val controller = controller(
            configured = false,
            start = {
                starts += 1
                successAnswer("resp", "must not happen")
            },
            continueCall = { _, _, _ ->
                error("continue must not run")
            },
            tool = {
                error("tool must not run")
            },
        )

        var finalState: AdvisorUiState = AdvisorUiState.Idle
        controller.runCase("test") { finalState = it }

        assertEquals(0, starts)
        assertEquals(
            AdvisorUiState.Error(ADVISOR_NOT_CONFIGURED_MESSAGE),
            finalState,
        )
    }

    private fun controller(
        configured: Boolean = true,
        start: suspend (String) -> AdvisorProxyCallResult,
        continueCall: suspend (
            String,
            String,
            AdvisorVerifiedToolResult,
        ) -> AdvisorProxyCallResult,
        tool: suspend (AdvisorToolArguments) -> AdvisorToolExecutionResult,
    ) = AdvisorController(
        isConfigured = { configured },
        startAgent = start,
        continueAgent = continueCall,
        executeTool = tool,
    )

    private fun successAnswer(
        responseId: String,
        text: String,
    ) = AdvisorProxyCallResult.Success(
        AdvisorProxyResult.Answer(
            responseId = responseId,
            text = text,
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
    ) = AdvisorToolExecutionResult.Success(
        AdvisorVerifiedToolResult(
            query = query,
            products = listOf(
                AdvisorVerifiedProduct(
                    obik = "1234567",
                    name = "Synthetic product",
                    stock = 1,
                    price = BigDecimal("9.99"),
                ),
            ),
        ),
    )
}
