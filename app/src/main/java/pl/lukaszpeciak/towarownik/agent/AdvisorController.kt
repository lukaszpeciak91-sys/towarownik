package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import pl.lukaszpeciak.towarownik.BuildConfig
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.VerifiedProductKey
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot

internal enum class AdvisorError {
    NOT_CONFIGURED,
    AUTHENTICATION,
    NETWORK,
    SERVICE,
    PROTOCOL,
    OBI,
    INPUT,
    UNSUPPORTED_PROVIDER,
}

internal sealed interface AdvisorUiState {
    data object Idle : AdvisorUiState
    data object LoadingProxy : AdvisorUiState
    data object RunningLocalTool : AdvisorUiState
    data object WaitingForFinalAnswer : AdvisorUiState
    data class Success(
        val text: String,
        val responseId: String,
        val products: List<VerifiedProductSnapshot> = emptyList(),
        val sources: List<AdvisorWebSource> = emptyList(),
        val searchActions: List<AdvisorSearchAction> = emptyList(),
    ) : AdvisorUiState
    data class Error(
        val error: AdvisorError,
        val diagnostic: AdvisorFailureDiagnostic? = null,
    ) : AdvisorUiState
}

internal class AdvisorController(
    private val isConfigured: () -> Boolean,
    private val startAgent: suspend (
        message: String,
        storeNumber: String,
    ) -> AdvisorProxyCallResult,
    private val messageAgent: suspend (
        previousResponseId: String,
        message: String,
        storeNumber: String,
    ) -> AdvisorProxyCallResult,
    private val continueAgent: suspend (
        responseId: String,
        callId: String,
        storeNumber: String,
        continuation: AdvisorToolContinuation,
    ) -> AdvisorProxyCallResult,
    private val executeTool: suspend (
        AdvisorToolArguments,
    ) -> AdvisorToolExecutionResult,
) {
    suspend fun runTurn(
        input: String,
        previousResponseId: String?,
        conversationStoreNumber: String = DEFAULT_OBI_STORE_NUMBER,
        onOpenAiResponse: (AdvisorUsage?, Long) -> Unit = { _, _ -> },
        onToolRequestObserved: () -> Unit = {},
        onState: (AdvisorUiState) -> Unit,
    ): AdvisorUiState {
        val normalizedInput = input.normalizeWhitespace()
        if (normalizedInput.isBlank()) {
            return AdvisorUiState.Error(AdvisorError.INPUT).also(onState)
        }

        val authorization = runCatching {
            AdvisorTurnStoreAuthorization.capture(
                conversationStoreNumber = conversationStoreNumber,
                currentUserMessage = normalizedInput,
            )
        }.getOrElse {
            return AdvisorUiState.Error(AdvisorError.INPUT).also(onState)
        }

        if (!isConfigured()) {
            return AdvisorUiState.Error(
                AdvisorError.NOT_CONFIGURED,
            ).also(onState)
        }

        onState(AdvisorUiState.LoadingProxy)

        val initialCall = safeProxyCall {
            if (previousResponseId == null) {
                startAgent(
                    normalizedInput,
                    conversationStoreNumber,
                )
            } else {
                messageAgent(
                    previousResponseId,
                    normalizedInput,
                    conversationStoreNumber,
                )
            }
        }

        var proxyResult = when (initialCall) {
            is AdvisorProxyCallResult.Success -> {
                observeUsageSafely(
                    usage = initialCall.result.usageOrNull(),
                    webSearchCalls =
                        initialCall.result.webSearchCalls(),
                    callback = onOpenAiResponse,
                )
                initialCall.result
            }
            is AdvisorProxyCallResult.Failure ->
                return initialCall.toUiError().also(onState)
        }

        var toolCalls = 0
        var localToolLimitContinuationSent = false
        var toolAssistedObserved = false
        val verifiedByKey =
            linkedMapOf<VerifiedProductKey, VerifiedProductSnapshot>()
        val searchActionsByKey =
            linkedMapOf<String, AdvisorSearchAction>()

        while (true) {
            when (proxyResult) {
                is AdvisorProxyResult.Answer -> {
                    val selectedProducts = proxyResult.productRefs
                        .distinctBy { it.key }
                        .mapNotNull { reference ->
                            verifiedByKey[reference.key]
                        }
                        .take(MAX_TOOL_PRODUCTS)
                    return AdvisorUiState.Success(
                        text = proxyResult.text,
                        responseId = proxyResult.responseId,
                        products = selectedProducts,
                        sources = proxyResult.sources,
                        searchActions = searchActionsByKey.values.toList(),
                    ).also(onState)
                }

                is AdvisorProxyResult.ToolRequest -> {
                    val toolRequest = proxyResult
                    if (!toolAssistedObserved) {
                        toolAssistedObserved = true
                        observeToolSafely(onToolRequestObserved)
                    }
                    if (toolCalls >= MAX_LOCAL_TOOL_CALLS_PER_TURN) {
                        if (localToolLimitContinuationSent) {
                            return AdvisorUiState.Error(
                                AdvisorError.PROTOCOL,
                            ).also(onState)
                        }
                        localToolLimitContinuationSent = true
                        onState(AdvisorUiState.WaitingForFinalAnswer)
                        proxyResult = when (
                            val continued = safeProxyCall {
                                continueAgent(
                                    toolRequest.responseId,
                                    toolRequest.callId,
                                    conversationStoreNumber,
                                    AdvisorToolContinuation.LocalToolLimitReached(
                                        queries = toolRequest.arguments.queries,
                                        storeNumber =
                                            toolRequest.arguments.storeNumber,
                                    ),
                                )
                            }
                        ) {
                            is AdvisorProxyCallResult.Success -> {
                                observeUsageSafely(
                                    usage =
                                        continued.result.usageOrNull(),
                                    webSearchCalls =
                                        continued.result.webSearchCalls(),
                                    callback = onOpenAiResponse,
                                )
                                continued.result
                            }

                            is AdvisorProxyCallResult.Failure ->
                                return continued.toUiError().also(onState)
                        }
                        continue
                    }
                    toolCalls += 1

                    val arguments = toolRequest.arguments
                    val continuation =
                        if (!authorization.isAuthorized(
                                arguments.storeNumber,
                            )
                        ) {
                            AdvisorToolContinuation.RejectedStore(
                                queries = arguments.queries,
                                storeNumber = arguments.storeNumber,
                            )
                        } else {
                            onState(AdvisorUiState.RunningLocalTool)
                            when (
                                val localResult = safeExecuteTool(arguments)
                            ) {
                                is AdvisorToolExecutionResult.Success -> {
                                    localResult.snapshots.forEach {
                                        verifiedByKey[it.key] = it
                                    }
                                    localResult.searchActions.forEach { action ->
                                        val key = buildString {
                                            append(action.storeNumber)
                                            append('|')
                                            append(
                                                action.query
                                                    .normalizeWhitespace()
                                                    .lowercase(),
                                            )
                                        }
                                        searchActionsByKey.putIfAbsent(
                                            key,
                                            action,
                                        )
                                    }
                                    AdvisorToolContinuation.Verified(
                                        localResult.result,
                                    )
                                }

                                AdvisorToolExecutionResult.UnsupportedStore ->
                                    AdvisorToolContinuation.RejectedStore(
                                        queries = arguments.queries,
                                        storeNumber = arguments.storeNumber,
                                    )

                                AdvisorToolExecutionResult.Failure ->
                                    return AdvisorUiState.Error(
                                        AdvisorError.OBI,
                                    ).also(onState)
                            }
                        }

                    onState(AdvisorUiState.WaitingForFinalAnswer)
                    proxyResult = when (
                        val continued = safeProxyCall {
                            continueAgent(
                                toolRequest.responseId,
                                toolRequest.callId,
                                conversationStoreNumber,
                                continuation,
                            )
                        }
                    ) {
                        is AdvisorProxyCallResult.Success -> {
                            observeUsageSafely(
                                usage = continued.result.usageOrNull(),
                                webSearchCalls =
                                    continued.result.webSearchCalls(),
                                callback = onOpenAiResponse,
                            )
                            continued.result
                        }
                        is AdvisorProxyCallResult.Failure ->
                            return continued.toUiError().also(onState)
                    }
                }
            }
        }
    }

    private fun observeUsageSafely(
        usage: AdvisorUsage?,
        webSearchCalls: Long,
        callback: (AdvisorUsage?, Long) -> Unit,
    ) {
        runCatching {
            callback(usage, webSearchCalls)
        }
    }

    private fun observeToolSafely(
        callback: () -> Unit,
    ) {
        runCatching {
            callback()
        }
    }

    private suspend fun safeExecuteTool(
        arguments: AdvisorToolArguments,
    ): AdvisorToolExecutionResult =
        try {
            executeTool(arguments)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            AdvisorToolExecutionResult.Failure
        }

    private suspend fun safeProxyCall(
        block: suspend () -> AdvisorProxyCallResult,
    ): AdvisorProxyCallResult =
        try {
            block()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.NETWORK,
            )
        }

    companion object {
        fun production(): AdvisorController {
            val proxyClient = AdvisorProxyClient()
            val localTool = FindObiProductsTool()
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

private fun AdvisorProxyResult.usageOrNull(): AdvisorUsage? =
    when (this) {
        is AdvisorProxyResult.Answer -> usage
        is AdvisorProxyResult.ToolRequest -> usage
    }

private fun AdvisorProxyResult.webSearchCalls(): Long =
    when (this) {
        is AdvisorProxyResult.Answer -> webSearchCalls
        is AdvisorProxyResult.ToolRequest -> webSearchCalls
    }

private fun AdvisorProxyCallResult.Failure.toUiError():
    AdvisorUiState.Error =
    AdvisorUiState.Error(
        error = when (kind) {
            AdvisorProxyFailureKind.NOT_CONFIGURED ->
                AdvisorError.NOT_CONFIGURED
            AdvisorProxyFailureKind.AUTHENTICATION ->
                AdvisorError.AUTHENTICATION
            AdvisorProxyFailureKind.SERVICE ->
                AdvisorError.SERVICE
            AdvisorProxyFailureKind.NETWORK ->
                AdvisorError.NETWORK
            AdvisorProxyFailureKind.PROTOCOL ->
                AdvisorError.PROTOCOL
        },
        diagnostic = diagnosticOrNull(),
    )

private val ADVISOR_CONTROL_OR_WHITESPACE = Regex("""[\s\p{Cc}]+""")

private fun String.normalizeWhitespace(): String =
    replace(ADVISOR_CONTROL_OR_WHITESPACE, " ").trim()
