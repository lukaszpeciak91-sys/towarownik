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
    data class Success(val text: String) : AdvisorUiState
    data class Error(val message: String) : AdvisorUiState
}

internal class AdvisorController(
    private val isConfigured: () -> Boolean,
    private val startAgent: suspend (String) -> AdvisorProxyCallResult,
    private val continueAgent: suspend (
        responseId: String,
        callId: String,
        result: AdvisorVerifiedToolResult,
    ) -> AdvisorProxyCallResult,
    private val executeTool: suspend (AdvisorToolArguments) -> AdvisorToolExecutionResult,
) {
    suspend fun runCase(
        input: String,
        onState: (AdvisorUiState) -> Unit,
    ) {
        val normalizedInput = input.normalizeWhitespace()
        if (normalizedInput.isBlank()) {
            onState(AdvisorUiState.Error(ADVISOR_INPUT_ERROR_MESSAGE))
            return
        }

        if (!isConfigured()) {
            onState(AdvisorUiState.Error(ADVISOR_NOT_CONFIGURED_MESSAGE))
            return
        }

        onState(AdvisorUiState.LoadingProxy)

        var proxyResult = when (val start = safeProxyCall {
            startAgent(normalizedInput)
        }) {
            is AdvisorProxyCallResult.Success -> start.result
            is AdvisorProxyCallResult.Failure -> {
                onState(start.toUiError())
                return
            }
        }

        var toolCalls = 0

        while (true) {
            when (proxyResult) {
                is AdvisorProxyResult.Answer -> {
                    onState(
                        AdvisorUiState.Success(
                            proxyResult.text,
                        ),
                    )
                    return
                }

                is AdvisorProxyResult.ToolRequest -> {
                    if (toolCalls >= MAX_LOCAL_TOOL_CALLS_PER_CASE) {
                        onState(
                            AdvisorUiState.Error(
                                ADVISOR_TOO_MANY_TOOLS_MESSAGE,
                            ),
                        )
                        return
                    }

                    toolCalls += 1
                    onState(AdvisorUiState.RunningLocalTool)

                    val localResult = try {
                        executeTool(proxyResult.arguments)
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        AdvisorToolExecutionResult.Failure
                    }

                    val verifiedResult = when (localResult) {
                        is AdvisorToolExecutionResult.Success -> localResult.result
                        AdvisorToolExecutionResult.Failure -> {
                            onState(
                                AdvisorUiState.Error(
                                    ADVISOR_OBI_ERROR_MESSAGE,
                                ),
                            )
                            return
                        }
                    }

                    onState(AdvisorUiState.WaitingForFinalAnswer)

                    proxyResult = when (val continued = safeProxyCall {
                        continueAgent(
                            proxyResult.responseId,
                            proxyResult.callId,
                            verifiedResult,
                        )
                    }) {
                        is AdvisorProxyCallResult.Success -> continued.result
                        is AdvisorProxyCallResult.Failure -> {
                            onState(continued.toUiError())
                            return
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
