package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        val traceId: String? = null,
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
        contract: AdvisorTransportContract,
        continuation: AdvisorToolContinuation,
    ) -> AdvisorProxyCallResult,
    private val continueAgentWithTrace: (suspend (
        responseId: String,
        callId: String,
        providerId: String,
        branchId: String,
        contract: AdvisorTransportContract,
        continuation: AdvisorToolContinuation,
        traceId: String?,
    ) -> AdvisorProxyCallResult)? = null,
    private val executeObiTool: suspend (
        AdvisorToolArguments,
    ) -> AdvisorToolExecutionResult,
    private val executeProviderTool: suspend (
        AdvisorToolArguments,
    ) -> AdvisorToolExecutionResult,
    private val branchDirectory: suspend (
        ProviderId,
    ) -> ProviderBranchResult,
    private val executeLocationsTool: (suspend (
        AdvisorLocationArguments,
        String,
        String,
        String,
        Collection<VerifiedProductSnapshot>,
        List<VerifiedProductSnapshot>,
    ) -> AdvisorLocationEvidence)? = null,
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
    private val startAgentWithAttachments: (suspend (
        String, String, String, List<AdvisorAttachment>,
    ) -> AdvisorProxyCallResult)? = null,
    private val messageAgentWithAttachments: (suspend (
        String, String, String, String, List<AdvisorAttachment>,
    ) -> AdvisorProxyCallResult)? = null,
) {
    suspend fun runTurn(
        input: String,
        previousResponseId: String?,
        conversationStoreNumber: String = DEFAULT_OBI_STORE_NUMBER,
        conversationProviderId: String = OBI_PROVIDER_ID.value,
        attachment: AdvisorAttachment? = null,
        attachments: List<AdvisorAttachment> = emptyList(),
        historicalVerifiedProducts: List<VerifiedProductSnapshot> = emptyList(),
        onOpenAiResponse: (AdvisorUsage?, Long) -> Unit = { _, _ -> },
        onToolRequestObserved: () -> Unit = {},
        onState: (AdvisorUiState) -> Unit,
    ): AdvisorUiState {
        val normalizedInput = input.normalizeWhitespace()
        val requested = if (attachments.isEmpty()) listOfNotNull(attachment) else attachments
        if (normalizedInput.isBlank() && requested.isEmpty() ||
            requested.size > 3 ||
            requested.map { it.localId }.distinct().size != requested.size ||
            requested.sumOf { it.byteSize } > 24L * 1024 * 1024
        ) {
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
        val turnContract = advisorTransportContract(
            providerId = conversationProvider,
            hasAttachment = requested.isNotEmpty(),
        ).let { if (attachments.isNotEmpty()) AdvisorTransportContract.PROVIDER_V5 else it }
        var turnBranches: List<ProviderBranch>? = null

        suspend fun loadTurnBranches(): ProviderBranchResult {
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

        suspend fun authorizeToolArguments(
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

                is BranchResolution.Ambiguous -> {
                    if (!resolution.allowsExplicitBranchHint) {
                        ToolBranchAuthorization.Rejected
                    } else {
                        val hinted = arguments.resolveBranchHint(branches)
                        if (
                            hinted != null &&
                            resolution.candidates.any {
                                it.branchId == hinted.branchId
                            }
                        ) {
                            ToolBranchAuthorization.Authorized(
                                arguments.forResolvedBranch(
                                    hinted.branchId,
                                ),
                            )
                        } else {
                            ToolBranchAuthorization.Rejected
                        }
                    }
                }

                BranchResolution.UnknownMention ->
                    ToolBranchAuthorization.Rejected

                BranchResolution.NotMentioned -> {
                    val hinted = arguments.resolveBranchHint(branches)
                    val modelRequestedOtherBranch =
                        arguments.storeNumber != currentBranchId.value ||
                            (
                                arguments.requestedBranch != null &&
                                    hinted?.branchId != currentBranchId
                            )
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
                if (attachments.isNotEmpty()) requireNotNull(startAgentWithAttachments)(
                    normalizedInput,
                    conversationProviderId,
                    conversationStoreNumber,
                    attachments,
                ) else if (attachment != null) requireNotNull(startAgentWithAttachment)(
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
                if (attachments.isNotEmpty()) requireNotNull(messageAgentWithAttachments)(
                    previousResponseId,
                    normalizedInput,
                    conversationProviderId,
                    conversationStoreNumber,
                    attachments,
                ) else if (attachment != null) requireNotNull(messageAgentWithAttachment)(
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

        var turnTraceId: String? = null
        var proxyResult = when (initialCall) {
            is AdvisorProxyCallResult.Success -> {
                turnTraceId = advisorTraceIdOrNull(initialCall.traceId)
                observeUsageSafely(
                    usage = initialCall.result.usageOrNull(),
                    webSearchCalls =
                        initialCall.result.webSearchCalls(),
                    callback = onOpenAiResponse,
                )
                initialCall.result
            }
            is AdvisorProxyCallResult.Failure ->
                return initialCall.toUiError(
                    fallbackTraceId = null,
                ).also(onState)
        }

        var toolCalls = 0
        var lastLocationEvidence: AdvisorLocationEvidence? = null
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
                    val completeLocationList = lastLocationEvidence
                        ?.let(::renderAdvisorLocationDetails)
                        .orEmpty()
                    return AdvisorUiState.Success(
                        text = if (completeLocationList.isBlank()) proxyResult.text else {
                            proxyResult.text + "\n\n" + completeLocationList
                        },
                        responseId = proxyResult.responseId,
                        products = selectedProducts,
                        sources = proxyResult.sources,
                        searchActions = searchActionsByKey.values.toList(),
                        traceId = turnTraceId,
                    ).also(onState)
                }

                is AdvisorProxyResult.LocationToolRequest -> {
                    val locationRequest = proxyResult
                    if (!toolAssistedObserved) {
                        toolAssistedObserved = true
                        observeToolSafely(onToolRequestObserved)
                    }
                    val evidence = if (toolCalls >= MAX_LOCAL_TOOL_CALLS_PER_TURN) {
                        if (localToolLimitContinuationSent) {
                            return AdvisorUiState.Error(AdvisorError.PROTOCOL).also(onState)
                        }
                        localToolLimitContinuationSent = true
                        AdvisorLocationEvidence(
                            providerId = conversationProviderId,
                            productId = null,
                            status = "rejected",
                            reason = "local_tool_limit_reached",
                            coverage = "unknown",
                            checkedIds = emptyList(),
                            returnedIds = emptyList(),
                            missingIds = emptyList(),
                            locations = emptyList(),
                            verifiedAtMillis = null,
                            centralStock = null,
                        )
                    } else {
                        toolCalls += 1
                        onState(AdvisorUiState.RunningLocalTool)
                        try {
                            executeLocationsTool?.invoke(
                                locationRequest.arguments,
                                conversationProviderId,
                                conversationStoreNumber,
                                normalizedInput,
                                verifiedByKey.values.toList(),
                                historicalVerifiedProducts,
                            ) ?: AdvisorLocationEvidence(
                                providerId = conversationProviderId,
                                productId = null,
                                status = "unavailable",
                                reason = "location_service_unavailable",
                                coverage = "unknown",
                                checkedIds = emptyList(),
                                returnedIds = emptyList(),
                                missingIds = emptyList(),
                                locations = emptyList(),
                                verifiedAtMillis = null,
                                centralStock = null,
                            )
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            AdvisorLocationEvidence(
                                providerId = conversationProviderId,
                                productId = null,
                                status = "unavailable",
                                reason = "transport",
                                coverage = "unknown",
                                checkedIds = emptyList(),
                                returnedIds = emptyList(),
                                missingIds = emptyList(),
                                locations = emptyList(),
                                verifiedAtMillis = null,
                                centralStock = null,
                            )
                        }
                    }
                    lastLocationEvidence = evidence
                    onState(AdvisorUiState.WaitingForFinalAnswer)
                    proxyResult = when (
                        val continued = safeProxyCall {
                            continueProxyTurn(
                                responseId = locationRequest.responseId,
                                callId = locationRequest.callId,
                                providerId = conversationProviderId,
                                branchId = conversationStoreNumber,
                                contract = turnContract,
                                continuation = AdvisorToolContinuation.Locations(evidence),
                                traceId = turnTraceId,
                            )
                        }
                    ) {
                        is AdvisorProxyCallResult.Success -> {
                            turnTraceId = advisorTraceIdOrNull(continued.traceId) ?: turnTraceId
                            observeUsageSafely(
                                usage = continued.result.usageOrNull(),
                                webSearchCalls = continued.result.webSearchCalls(),
                                callback = onOpenAiResponse,
                            )
                            continued.result
                        }
                        is AdvisorProxyCallResult.Failure ->
                            return continued.toUiError(
                                fallbackTraceId = turnTraceId,
                            ).also(onState)
                    }
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
                                continueProxyTurn(
                                    responseId = toolRequest.responseId,
                                    callId = toolRequest.callId,
                                    providerId = conversationProviderId,
                                    branchId = conversationStoreNumber,
                                    contract = turnContract,
                                    continuation = AdvisorToolContinuation.LocalToolLimitReached(
                                        queries = toolRequest.arguments.queries,
                                        storeNumber =
                                            toolRequest.arguments.storeNumber,
                                        providerId =
                                            toolRequest.arguments.providerId,
                                    ),
                                    traceId = turnTraceId,
                                )
                            }
                        ) {
                            is AdvisorProxyCallResult.Success -> {
                                turnTraceId =
                                    advisorTraceIdOrNull(continued.traceId) ?: turnTraceId
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
                                return continued.toUiError(
                                    fallbackTraceId = turnTraceId,
                                ).also(onState)
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
                            continueProxyTurn(
                                responseId = toolRequest.responseId,
                                callId = toolRequest.callId,
                                providerId = conversationProviderId,
                                branchId = conversationStoreNumber,
                                contract = turnContract,
                                continuation = continuation,
                                traceId = turnTraceId,
                            )
                        }
                    ) {
                        is AdvisorProxyCallResult.Success -> {
                            turnTraceId =
                                continued.traceId ?: turnTraceId
                            observeUsageSafely(
                                usage = continued.result.usageOrNull(),
                                webSearchCalls =
                                    continued.result.webSearchCalls(),
                                callback = onOpenAiResponse,
                            )
                            continued.result
                        }
                        is AdvisorProxyCallResult.Failure ->
                            return continued.toUiError(
                                fallbackTraceId = turnTraceId,
                            ).also(onState)
                    }
                }
            }
        }
    }

    private suspend fun continueProxyTurn(
        responseId: String,
        callId: String,
        providerId: String,
        branchId: String,
        contract: AdvisorTransportContract,
        continuation: AdvisorToolContinuation,
        traceId: String?,
    ): AdvisorProxyCallResult =
        continueAgentWithTrace?.invoke(
            responseId,
            callId,
            providerId,
            branchId,
            contract,
            continuation,
            traceId,
        ) ?: continueAgent(
            responseId,
            callId,
            providerId,
            branchId,
            contract,
            continuation,
        )

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
            val locationsTool = AdvisorLocationsTool()
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
                        contract,
                        continuation,
                    ->
                    continueAdvisorToolTurn(
                        proxyClient = proxyClient,
                        responseId = responseId,
                        callId = callId,
                        providerId = providerId,
                        branchId = branchId,
                        contract = contract,
                        continuation = continuation,
                    )
                },
                continueAgentWithTrace = {
                        responseId,
                        callId,
                        providerId,
                        branchId,
                        contract,
                        continuation,
                        traceId,
                    ->
                    continueAdvisorToolTurn(
                        proxyClient = proxyClient,
                        responseId = responseId,
                        callId = callId,
                        providerId = providerId,
                        branchId = branchId,
                        contract = contract,
                        continuation = continuation,
                        traceId = traceId,
                    )
                },
                executeObiTool = obiTool::execute,
                executeLocationsTool = locationsTool::execute,
                executeProviderTool = providerTool::execute,
                branchDirectory = { providerId ->
                    withContext(Dispatchers.IO) {
                        providers.resolve(providerId).branches()
                    }
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
                startAgentWithAttachments = { message, providerId, branchId, parts ->
                    proxyClient.start(message, providerId, branchId, parts)
                },
                messageAgentWithAttachments = { responseId, message, providerId, branchId, parts ->
                    proxyClient.message(responseId, message, providerId, branchId, parts)
                },
            )
        }
    }
}

internal fun advisorTransportContract(
    providerId: ProviderId,
    hasAttachment: Boolean,
): AdvisorTransportContract =
    when {
        hasAttachment -> AdvisorTransportContract.PROVIDER_V4
        providerId == OBI_PROVIDER_ID -> AdvisorTransportContract.OBI_V2
        else -> AdvisorTransportContract.PROVIDER_V3
    }

internal suspend fun continueAdvisorToolTurn(
    proxyClient: AdvisorProxyClient,
    responseId: String,
    callId: String,
    providerId: String,
    branchId: String,
    contract: AdvisorTransportContract,
    continuation: AdvisorToolContinuation,
    traceId: String? = null,
): AdvisorProxyCallResult =
    when (contract) {
        AdvisorTransportContract.OBI_V2 ->
            proxyClient.continueTurn(
                responseId = responseId,
                callId = callId,
                storeNumber = branchId,
                continuation = continuation,
                traceId = traceId,
            )
        AdvisorTransportContract.PROVIDER_V3,
        AdvisorTransportContract.PROVIDER_V4,
        AdvisorTransportContract.PROVIDER_V5,
        ->
            proxyClient.continueTurn(
                responseId = responseId,
                callId = callId,
                providerId = providerId,
                branchId = branchId,
                continuation = continuation,
                protocolVersion = contract.protocolVersion,
                traceId = traceId,
            )
    }

private sealed interface ToolBranchAuthorization {
    data class Authorized(
        val arguments: AdvisorToolArguments,
    ) : ToolBranchAuthorization

    data object Rejected : ToolBranchAuthorization
    data object Unavailable : ToolBranchAuthorization
}

private fun AdvisorToolArguments.resolveBranchHint(
    branches: List<ProviderBranch>,
): ProviderBranch? {
    val byId = branches.singleOrNull {
        it.branchId.value == storeNumber
    }
    val byRequestedBranch = requestedBranch?.let { hint ->
        BranchResolver.resolveHint(
            hint = hint,
            branches = branches,
        )
    }
    return when {
        byRequestedBranch == null -> byId
        byId == null -> byRequestedBranch
        byRequestedBranch.branchId == byId.branchId ->
            byRequestedBranch
        else -> null
    }
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
        is AdvisorProxyResult.LocationToolRequest -> usage
    }

private fun AdvisorProxyResult.webSearchCalls(): Long =
    when (this) {
        is AdvisorProxyResult.Answer -> webSearchCalls
        is AdvisorProxyResult.ToolRequest -> webSearchCalls
        is AdvisorProxyResult.LocationToolRequest -> webSearchCalls
    }

private fun AdvisorProxyCallResult.Failure.toUiError(
    fallbackTraceId: String?,
): AdvisorUiState.Error {
    val bestTraceId =
        advisorTraceIdOrNull(traceId) ?: advisorTraceIdOrNull(fallbackTraceId)
    val baseDiagnostic = diagnosticOrNull()
    return AdvisorUiState.Error(
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
        diagnostic = when {
            baseDiagnostic != null ->
                baseDiagnostic.copy(traceId = bestTraceId)
            bestTraceId != null ->
                AdvisorFailureDiagnostic(
                    kind = kind,
                    traceId = bestTraceId,
                )
            else -> null
        },
    )
}

private val ADVISOR_CONTROL_OR_WHITESPACE = Regex("""[\s\p{Cc}]+""")

private fun String.normalizeWhitespace(): String =
    replace(ADVISOR_CONTROL_OR_WHITESPACE, " ").trim()

