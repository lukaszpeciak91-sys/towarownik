package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import pl.lukaszpeciak.towarownik.BuildConfig
import pl.lukaszpeciak.towarownik.attachment.AdvisorAttachment
import pl.lukaszpeciak.towarownik.attachment.AttachmentStorage
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.VerifiedProductKey
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.BranchResolution
import pl.lukaszpeciak.towarownik.product.provider.BranchResolver
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProductProviderRegistry
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderId

internal enum class AdvisorError {
    NOT_CONFIGURED,
    AUTHENTICATION,
    NETWORK,
    SERVICE,
    PROTOCOL,
    OBI,
    INPUT,
    UNSUPPORTED_PROVIDER,
    PRODUCT_PROVIDER,
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
        providerId: String,
        branchId: String,
    ) -> AdvisorProxyCallResult,
    private val messageAgent: suspend (
        previousResponseId: String,
        message: String,
        providerId: String,
        branchId: String,
    ) -> AdvisorProxyCallResult,
    private val continueAgent: suspend (
        responseId: String,
        callId: String,
        providerId: String,
        branchId: String,
        continuation: AdvisorToolContinuation,
    ) -> AdvisorProxyCallResult,
    private val executeObiTool: suspend (
        AdvisorToolArguments,
    ) -> AdvisorToolExecutionResult,
    private val executeProviderTool: suspend (
        AdvisorToolArguments,
    ) -> AdvisorToolExecutionResult,
    private val branchDirectory: (
        ProviderId,
    ) -> ProviderBranchResult,
    private val startAgentWithAttachment: (suspend (
        String,
        String,
        String,
        AdvisorAttachment,
    ) -> AdvisorProxyCallResult)? = null,
    private val messageAgentWithAttachment: (suspend (
        String,
        String,
        String,
        String,
        AdvisorAttachment,
    ) -> AdvisorProxyCallResult)? = null,
) {
    suspend fun runTurn(
        input: String,
        previousResponseId: String?,
        conversationStoreNumber: String = DEFAULT_OBI_STORE_NUMBER,
        conversationProviderId: String = OBI_PROVIDER_ID.value,
        attachment: AdvisorAttachment? = null,
        onOpenAiResponse: (AdvisorUsage?, Long) -> Unit = { _, _ -> },
        onToolRequestObserved: () -> Unit = {},
        onState: (AdvisorUiState) -> Unit,
    ): AdvisorUiState {
        val normalizedInput = input.normalizeWhitespace()
        if (normalizedInput.isBlank() && attachment == null) {
            return AdvisorUiState.Error(AdvisorError.INPUT).also(onState)
        }

        val conversationProvider = runCatching {
            ProviderId(conversationProviderId)
        }.getOrElse {
            return AdvisorUiState.Error(
                AdvisorError.INPUT,
            ).also(onState)
        }
        val currentBranchId = runCatching {
            BranchId(conversationStoreNumber)
        }.getOrElse {
            return AdvisorUiState.Error(
                AdvisorError.INPUT,
            ).also(onState)
        }
        var turnBranches: List<ProviderBranch>? = null

        fun loadTurnBranches(): ProviderBranchResult {
            turnBranches?.let {
                return ProviderBranchResult.Available(it)
            }
            return when (
                val result = runCatching {
                    branchDirectory(conversationProvider)
                }.getOrElse {
                    ProviderBranchResult.Unavailable(
                        failure =
                            pl.lukaszpeciak.towarownik.product.provider
                                .ProductProviderFailure.NETWORK,
                        reason = "Branch directory unavailable",
                    )
                }
            ) {
                is ProviderBranchResult.Available -> {
                    turnBranches = result.branches
                    result
                }
                is ProviderBranchResult.Unavailable -> result
            }
        }

        fun authorizeToolArguments(
            arguments: AdvisorToolArguments,
        ): ToolBranchAuthorization {
            if (arguments.providerId != conversationProviderId) {
                return ToolBranchAuthorization.Rejected
            }
            val branches = when (val result = loadTurnBranches()) {
                is ProviderBranchResult.Available -> result.branches
                is ProviderBranchResult.Unavailable ->
                    return ToolBranchAuthorization.Unavailable
            }
            if (branches.none { it.branchId == currentBranchId }) {
                return ToolBranchAuthorization.Rejected
            }

            return when (
                val resolution = BranchResolver.resolve(
                    userText = normalizedInput,
                    branches = branches,
                    currentBranchId = currentBranchId,
                )
            ) {
                is BranchResolution.CurrentBranch ->
                    ToolBranchAuthorization.Authorized(
                        arguments.forResolvedBranch(
                            resolution.branch.branchId,
                        ),
                    )

                is BranchResolution.Resolved ->
                    ToolBranchAuthorization.Authorized(
                        arguments.forResolvedBranch(
                            resolution.branch.branchId,
                        ),
                    )

                is BranchResolution.Ambiguous,
                BranchResolution.UnknownMention ->
                    ToolBranchAuthorization.Rejected

                BranchResolution.NotMentioned -> {
                    val modelRequestedOtherBranch =
                        arguments.storeNumber != currentBranchId.value ||
                            arguments.requestedBranch?.let { hint ->
                                when (
                                    BranchResolver.resolve(
                                        userText = hint,
                                        branches = branches,
                                        currentBranchId = currentBranchId,
                                    )
                                ) {
                                    is BranchResolution.CurrentBranch ->
                                        false
                                    else -> true
                                }
                            } == true
                    if (modelRequestedOtherBranch) {
                        ToolBranchAuthorization.Rejected
                    } else {
                        ToolBranchAuthorization.Authorized(
                            arguments.forResolvedBranch(
                                currentBranchId,
                            ),
                        )
                    }
                }
            }
        }

        if (!isConfigured()) {
            return AdvisorUiState.Error(
                AdvisorError.NOT_CONFIGURED,
            ).also(onState)
        }

        onState(AdvisorUiState.LoadingProxy)

        val initialCall = safeProxyCall {
            if (previousResponseId == null) {
                if (attachment != null) requireNotNull(startAgentWithAttachment)(
                    normalizedInput,
                    conversationProviderId,
                    conversationStoreNumber,
                    attachment,
                ) else startAgent(
                    normalizedInput,
                    conversationProviderId,
                    conversationStoreNumber,
                )
            } else {
                if (attachment != null) requireNotNull(messageAgentWithAttachment)(
                    previousResponseId,
                    normalizedInput,
                    conversationProviderId,
                    conversationStoreNumber,
                    attachment,
                ) else messageAgent(
                    previousResponseId,
                    normalizedInput,
                    conversationProviderId,
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
                                    conversationProviderId,
                                    conversationStoreNumber,
                                    AdvisorToolContinuation.LocalToolLimitReached(
                                        queries = toolRequest.arguments.queries,
                                        storeNumber =
                                            toolRequest.arguments.storeNumber,
                                        providerId =
                                            toolRequest.arguments.providerId,
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
                    val authorization = authorizeToolArguments(arguments)
                    val continuation =
                        when (authorization) {
                            ToolBranchAuthorization.Rejected ->
                                AdvisorToolContinuation.RejectedStore(
                                    queries = arguments.queries,
                                    storeNumber = arguments.storeNumber,
                                    providerId = arguments.providerId,
                                )

                            ToolBranchAuthorization.Unavailable ->
                                return AdvisorUiState.Error(
                                    AdvisorError.PRODUCT_PROVIDER,
                                ).also(onState)

                            is ToolBranchAuthorization.Authorized -> {
                                val resolvedArguments =
                                    authorization.arguments
                                onState(AdvisorUiState.RunningLocalTool)
                                when (
                                    val localResult = safeExecuteTool(
                                        conversationProviderId =
                                            conversationProviderId,
                                        arguments = resolvedArguments,
                                    )
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
                                            queries =
                                                resolvedArguments.queries,
                                            storeNumber =
                                                resolvedArguments.storeNumber,
                                            providerId =
                                                resolvedArguments.providerId,
                                        )

                                    AdvisorToolExecutionResult.Failure ->
                                        return AdvisorUiState.Error(
                                            AdvisorError.PRODUCT_PROVIDER,
                                        ).also(onState)
                                }
                            }
                        }

                    onState(AdvisorUiState.WaitingForFinalAnswer)
                    proxyResult = when (
                        val continued = safeProxyCall {
                            continueAgent(
                                toolRequest.responseId,
                                toolRequest.callId,
                                conversationProviderId,
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
        conversationProviderId: String,
        arguments: AdvisorToolArguments,
    ): AdvisorToolExecutionResult =
        try {
            if (conversationProviderId == OBI_PROVIDER_ID.value) {
                executeObiTool(arguments)
            } else {
                executeProviderTool(arguments)
            }
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
        fun production(context: android.content.Context): AdvisorController {
            val attachmentStorage = AttachmentStorage(context.applicationContext)
            val proxyClient = AdvisorProxyClient(attachmentStorage = attachmentStorage)
            val providers = ProductProviderRegistry.production()
            val obiTool = FindObiProductsTool()
            val providerTool = FindProviderProductsTool(
                providers = providers,
            )
            return AdvisorController(
                isConfigured = {
                    BuildConfig.TOWAROWNIK_APP_TOKEN.isNotBlank() &&
                        proxyClient.isConfigured()
                },
                startAgent = { message, providerId, branchId ->
                    if (providerId == OBI_PROVIDER_ID.value) {
                        proxyClient.start(
                            message = message,
                            storeNumber = branchId,
                        )
                    } else {
                        proxyClient.start(
                            message = message,
                            providerId = providerId,
                            branchId = branchId,
                        )
                    }
                },
                messageAgent = {
                        previousResponseId,
                        message,
                        providerId,
                        branchId,
                    ->
                    if (providerId == OBI_PROVIDER_ID.value) {
                        proxyClient.message(
                            previousResponseId = previousResponseId,
                            message = message,
                            storeNumber = branchId,
                        )
                    } else {
                        proxyClient.message(
                            previousResponseId = previousResponseId,
                            message = message,
                            providerId = providerId,
                            branchId = branchId,
                        )
                    }
                },
                continueAgent = {
                        responseId,
                        callId,
                        providerId,
                        branchId,
                        continuation,
                    ->
                    if (providerId == OBI_PROVIDER_ID.value) {
                        proxyClient.continueTurn(
                            responseId = responseId,
                            callId = callId,
                            storeNumber = branchId,
                            continuation = continuation,
                        )
                    } else {
                        proxyClient.continueTurn(
                            responseId = responseId,
                            callId = callId,
                            providerId = providerId,
                            branchId = branchId,
                            continuation = continuation,
                        )
                    }
                },
                executeObiTool = obiTool::execute,
                executeProviderTool = providerTool::execute,
                branchDirectory = { providerId ->
                    providers.resolve(providerId).branches()
                },
                startAgentWithAttachment = {
                        message, providerId, branchId, attachment,
                    ->
                    proxyClient.start(message, providerId, branchId, attachment)
                },
                messageAgentWithAttachment = {
                        responseId, message, providerId, branchId, attachment,
                    ->
                    proxyClient.message(responseId, message, providerId, branchId, attachment)
                },
            )
        }
    }
}

private sealed interface ToolBranchAuthorization {
    data class Authorized(
        val arguments: AdvisorToolArguments,
    ) : ToolBranchAuthorization

    data object Rejected : ToolBranchAuthorization
    data object Unavailable : ToolBranchAuthorization
}

private fun AdvisorToolArguments.forResolvedBranch(
    branchId: BranchId,
): AdvisorToolArguments =
    copy(
        storeNumber = branchId.value,
        requestedBranch = null,
    )

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

