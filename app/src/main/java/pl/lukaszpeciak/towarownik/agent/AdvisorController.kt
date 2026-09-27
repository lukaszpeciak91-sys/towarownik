package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import pl.lukaszpeciak.towarownik.BuildConfig

internal const val ADVISOR_NOT_CONFIGURED_MESSAGE =
    "Doradca nie jest skonfigurowany w tej wersji aplikacji."
internal const val ADVISOR_NETWORK_ERROR_MESSAGE =
    "Nie udało się połączyć z doradcą."
internal const val ADVISOR_SERVICE_ERROR_MESSAGE =
    "Usługa doradcy jest chwilowo niedostępna."
internal const val ADVISOR_PROTOCOL_ERROR_MESSAGE =
    "Otrzymano nieprawidłową odpowiedź doradcy."
internal const val ADVISOR_OBI_ERROR_MESSAGE =
    "Nie udało się odczytać danych z OBI."
internal const val ADVISOR_TOO_MANY_TOOLS_MESSAGE =
    "Doradca poprosił o zbyt wiele sprawdzeń OBI."
internal const val ADVISOR_INPUT_ERROR_MESSAGE =
    "Opisz czego potrzebuje klient."

internal sealed interface AdvisorUiState {
    data object Idle : AdvisorUiState
    data object LoadingProxy : AdvisorUiState
    data object RunningLocalTool : AdvisorUiState
    data object WaitingForFinalAnswer : AdvisorUiState
    data class Success(
        val text: String,
        val responseId: String,
    ) : AdvisorUiState
    data class Error(val message: String) : AdvisorUiState
}

internal class AdvisorController(
    private val isConfigured: () -> Boolean,
    private val startAgent: suspend (String) -> AdvisorProxyCallResult,
    private val messageAgent: suspend (
        previousResponseId: String,
        message: String,
    ) -> AdvisorProxyCallResult,
    private val continueAgent: suspend (
        responseId: String,
        callId: String,
        result: AdvisorVerifiedToolResult,
    ) -> AdvisorProxyCallResult,
    private val executeTool: suspend (AdvisorToolArguments) -> AdvisorToolExecutionResult,
) {
    suspend fun runTurn(
        input: String,
        previousResponseId: String?,
        onState: (AdvisorUiState) -> Unit,
    ): AdvisorUiState {
        val normalizedInput = input.normalizeWhitespace()
        if (normalizedInput.isBlank()) {
            val error = AdvisorUiState.Error(ADVISOR_INPUT_ERROR_MESSAGE)
            onState(error)
            return error
        }

        if (!isConfigured()) {
            val error = AdvisorUiState.Error(ADVISOR_NOT_CONFIGURED_MESSAGE)
            onState(error)
            return error
        }

        onState(AdvisorUiState.LoadingProxy)

        val initialCall = safeProxyCall {
            if (previousResponseId == null) {
                startAgent(normalizedInput)
            } else {
                messageAgent(
                    previousResponseId,
                    normalizedInput,
                )
            }
        }

        var proxyResult = when (initialCall) {
            is AdvisorProxyCallResult.Success -> initialCall.result
            is AdvisorProxyCallResult.Failure -> {
                val error = initialCall.toUiError()
                onState(error)
                return error
            }
        }

        var toolCalls = 0

        while (true) {
            when (proxyResult) {
                is AdvisorProxyResult.Answer -> {
                    val success = AdvisorUiState.Success(
                        text = proxyResult.text,
                        responseId = proxyResult.responseId,
                    )
                    onState(success)
                    return success
                }

                is AdvisorProxyResult.ToolRequest -> {
                    val toolRequest = proxyResult
                    if (toolCalls >= MAX_LOCAL_TOOL_CALLS_PER_TURN) {
                        val error = AdvisorUiState.Error(
                            ADVISOR_TOO_MANY_TOOLS_MESSAGE,
                        )
                        onState(error)
                        return error
                    }

                    toolCalls += 1
                    onState(AdvisorUiState.RunningLocalTool)

                    val localResult = try {
                        executeTool(toolRequest.arguments)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        AdvisorToolExecutionResult.Failure
                    }

                    val verifiedResult = when (localResult) {
                        is AdvisorToolExecutionResult.Success -> localResult.result
                        AdvisorToolExecutionResult.Failure -> {
                            val error = AdvisorUiState.Error(
                                ADVISOR_OBI_ERROR_MESSAGE,
                            )
                            onState(error)
                            return error
                        }
                    }

                    onState(AdvisorUiState.WaitingForFinalAnswer)

                    proxyResult = when (val continued = safeProxyCall {
                        continueAgent(
                            toolRequest.responseId,
                            toolRequest.callId,
                            verifiedResult,
                        )
                    }) {
                        is AdvisorProxyCallResult.Success -> continued.result
                        is AdvisorProxyCallResult.Failure -> {
                            val error = continued.toUiError()
                            onState(error)
                            return error
                        }
                    }
                }
            }
        }
    }

    private suspend fun safeProxyCall(
        block: suspend () -> AdvisorProxyCallResult,
    ): AdvisorProxyCallResult {
        return try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.NETWORK,
            )
        }
    }

    companion object {
        fun production(): AdvisorController {
            val proxyClient = AdvisorProxyClient()
            val localTool = FindAvailableObi075Tool()
            return AdvisorController(
                isConfigured = {
                    BuildConfig.TOWAROWNIK_APP_TOKEN.isNotBlank() &&
                        proxyClient.isConfigured()
                },
                startAgent = proxyClient::start,
                messageAgent = proxyClient::message,
                continueAgent = proxyClient::continueTurn,
                executeTool = localTool::execute,
            )
        }
    }
}

private fun AdvisorProxyCallResult.Failure.toUiError(): AdvisorUiState.Error =
    AdvisorUiState.Error(
        when (kind) {
            AdvisorProxyFailureKind.NOT_CONFIGURED ->
                ADVISOR_NOT_CONFIGURED_MESSAGE
            AdvisorProxyFailureKind.AUTHENTICATION,
            AdvisorProxyFailureKind.SERVICE ->
                ADVISOR_SERVICE_ERROR_MESSAGE
            AdvisorProxyFailureKind.NETWORK ->
                ADVISOR_NETWORK_ERROR_MESSAGE
            AdvisorProxyFailureKind.PROTOCOL ->
                ADVISOR_PROTOCOL_ERROR_MESSAGE
        },
    )

private val ADVISOR_CONTROL_OR_WHITESPACE = Regex("""[\s\p{Cc}]+""")

private fun String.normalizeWhitespace(): String =
    replace(ADVISOR_CONTROL_OR_WHITESPACE, " ").trim()
