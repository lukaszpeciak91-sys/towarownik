package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.diagnostics.DiagnosticSource
import pl.lukaszpeciak.towarownik.diagnostics.DiagnosticStage
import pl.lukaszpeciak.towarownik.diagnostics.SafeDiagnosticEvent
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
        val locationReportEvidence: List<AdvisorLocationEvidence> = emptyList(),
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
        AdvisorInventoryAuthorization.Inventory,
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
        locationHistory: List<AdvisorLocationHistoryMessage> = emptyList(),
        onDiagnosticEvent: (SafeDiagnosticEvent) -> Unit = {},
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

        var obiCityWideMarketIds: Set<String> = emptySet()
        // A wrong model-selected OBIK in a historical same-product follow-up
        // blocks further lookups in this USER turn, not just the first request.
        var blockedObiFollowUpReason: String? = null
        var trustedObiFollowUpId: String? = null
        var trustedObiFollowUpBranch: String? = null
        var trustedObiFollowUpResult: AdvisorToolContinuation.Verified? = null
        var independentObiDiscoveryObserved = false
        val correctedGroupedModelObiks = mutableSetOf<String>()

        suspend fun authorizeToolArguments(
            arguments: AdvisorToolArguments,
        ): ToolBranchAuthorization {
            if (arguments.providerId != conversationProviderId) {
                return ToolBranchAuthorization.Rejected("wrong_provider")
            }
            blockedObiFollowUpReason?.let { return ToolBranchAuthorization.Rejected(it) }
            val branches = when (val result = loadTurnBranches()) {
                is ProviderBranchResult.Available -> result.branches
                is ProviderBranchResult.Unavailable ->
                    return ToolBranchAuthorization.Unavailable
            }
            if (branches.isEmpty() || branches.size > 100 ||
                branches.map { it.branchId }.distinct().size != branches.size ||
                branches.any { it.name.isBlank() }
            ) {
                return ToolBranchAuthorization.Unavailable
            }
            if (branches.none { it.branchId == currentBranchId }) {
                return ToolBranchAuthorization.Rejected("directory_unavailable")
            }

            // Resolve a bounded USER-owned referent before trusting model search hints.
            // An explicit current USER product always stays an independent search.
            var trustedBranch: ProviderBranch? = null
            var effectiveArguments = arguments
            val explicitObiProduct = if (conversationProvider == OBI_PROVIDER_ID) {
                advisorUserProductEvidence(normalizedInput)
                    .filter { it.matches(Regex("""[0-9]{7}""")) }
                    .singleOrNull()
            } else null
            val explicitStockIntent = advisorHasStockEvidence(normalizedInput) ||
                Regex("""\b(?:mamy|jest|sa)\b.*\bna stanie\b""")
                    .containsMatchIn(advisorAuthorityText(normalizedInput))
            if (explicitObiProduct != null && explicitStockIntent) {
                // Correct only one unambiguous exact-OBIK query slot. Other
                // grouped queries (e.g. a separate product-name search) are
                // never rewritten or dropped, and later tool calls remain
                // independent of this single-query correction.
                val numericSlots = arguments.queries.withIndex().filter {
                    it.value.query.trim().matches(Regex("""[0-9]{7}"""))
                }
                if (numericSlots.size > 1) {
                    return ToolBranchAuthorization.Rejected("ambiguous_product")
                }
                if (numericSlots.size == 1) {
                    val targetIndex = numericSlots.single().index
                    effectiveArguments = arguments.copy(
                        queries = arguments.queries.mapIndexed { index, query ->
                            if (index == targetIndex) AdvisorToolQuery(explicitObiProduct, 1)
                            else query
                        },
                    )
                    if (arguments.queries.size == 1) {
                        trustedObiFollowUpId = explicitObiProduct
                    } else if (numericSlots.single().value.query.trim() != explicitObiProduct) {
                        correctedGroupedModelObiks += numericSlots.single().value.query.trim()
                    }
                }
            }
            if (conversationProvider == OBI_PROVIDER_ID &&
                !advisorHasCurrentProductEvidence(normalizedInput)
            ) {
                val followUp = resolveAdvisorLocationFollowUp(
                    normalizedInput, locationHistory,
                    historicalVerifiedProducts.filter { it.providerId == conversationProviderId },
                    branches, conversationProviderId, conversationStoreNumber,
                )
                val explicitlySameProduct = Regex(
                    """\b(?:ten(?: sam)? produkt|tego(?: samego)? produktu)\b""",
                ).containsMatchIn(advisorAuthorityText(normalizedInput))
                val boundedExplicitFollowUp = explicitlySameProduct &&
                    advisorIsBoundedHistoricalScopeReference(normalizedInput, branches)
                val anaphoricUserQuestion = normalizedInput.trimStart().lowercase().startsWith("a ") ||
                    Regex("""^(?:ile|a ile) (?:maja|ma|jest|sa)\b""")
                        .containsMatchIn(advisorAuthorityText(normalizedInput))
                val inheritedShortFollowUp = followUp.isShortLocationFollowUp &&
                    (anaphoricUserQuestion || followUp.hasBoundedProductReferent)
                if (inheritedShortFollowUp || boundedExplicitFollowUp) {
                    val trusted = followUp.historicalProducts.filter {
                        it.providerId == conversationProviderId && it.verifiedAt > 0 &&
                            it.effectiveProductId.matches(Regex("""[0-9]{7}"""))
                    }.distinctBy { it.effectiveProductId }
                    val reason = when {
                        followUp.failureReason != null -> followUp.failureReason
                        inheritedShortFollowUp &&
                            (!followUp.inheritsInventoryContext || !followUp.hasPendingInventoryRequest) ->
                            "untrusted_product"
                        boundedExplicitFollowUp && !inheritedShortFollowUp &&
                            !followUp.hasBoundedProductReferent -> "untrusted_product"
                        trusted.size > 1 -> "ambiguous_product"
                        trusted.size != 1 -> "untrusted_product"
                        // The model may request the WRONG OBIK. The USER-owned,
                        // uniquely selected product, not the model, is authority.
                        else -> null
                    }
                    if (reason != null) {
                        blockedObiFollowUpReason = reason
                        return ToolBranchAuthorization.Rejected(reason)
                    }
                    val exactId = trusted.single().effectiveProductId
                    trustedObiFollowUpId = exactId
                    trustedBranch = followUp.resolvedBranch
                    effectiveArguments = arguments.copy(
                        queries = listOf(AdvisorToolQuery(exactId, 1)),
                    )
                }
            }

            // Both source and target come from bounded USER evidence. A known
            // one-off market can be reused from the canonical follow-up resolver;
            // model branch hints remain incapable of changing it.
            val resolution = trustedBranch?.let { BranchResolution.Resolved(it) }
                ?: BranchResolver.resolveDiscovery(
                    userText = normalizedInput,
                    branches = branches,
                    currentBranchId = currentBranchId,
                    obiMarketScope = conversationProvider == OBI_PROVIDER_ID,
                )
            return when (resolution) {
                is BranchResolution.CurrentBranch ->
                    ToolBranchAuthorization.Authorized(
                        effectiveArguments.forResolvedBranch(
                            resolution.branch.branchId,
                        ),
                    )

                is BranchResolution.Resolved ->
                    ToolBranchAuthorization.Authorized(
                        effectiveArguments.forResolvedBranch(
                            resolution.branch.branchId,
                        ),
                    )

                is BranchResolution.Ambiguous -> {
                    if (resolution.isCityWideScope) {
                        obiCityWideMarketIds = resolution.candidates.map { it.branchId.value }.toSet()
                        // Identity verification uses one USER-authorized member, deterministically.
                        // The original full scope remains pending for the separate inventory tool.
                        ToolBranchAuthorization.Authorized(effectiveArguments.forResolvedBranch(
                            resolution.candidates.minBy { it.branchId.value }.branchId,
                        ))
                    } else if (!resolution.allowsExplicitBranchHint) {
                        ToolBranchAuthorization.Rejected("ambiguous_location")
                    } else {
                        val hinted = arguments.resolveBranchHint(branches)
                        if (
                            hinted != null &&
                            resolution.candidates.any {
                                it.branchId == hinted.branchId
                            }
                        ) {
                            ToolBranchAuthorization.Authorized(
                                effectiveArguments.forResolvedBranch(
                                    hinted.branchId,
                                ),
                            )
                        } else {
                            ToolBranchAuthorization.Rejected("tool_rejected")
                        }
                    }
                }

                BranchResolution.UnknownMention ->
                    ToolBranchAuthorization.Rejected("unknown_location")

                // A model hint is not USER-owned branch authority. With no
                // location selection, normalize execution to the saved profile.
                BranchResolution.NotMentioned ->
                    ToolBranchAuthorization.Authorized(
                        effectiveArguments.forResolvedBranch(currentBranchId),
                    )
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
        val locationReportEvidence = mutableListOf<AdvisorLocationEvidence>()
        var lastDiscoveryRejectionReason: String? = null
        var localToolLimitContinuationSent = false
        var toolAssistedObserved = false
        val independentQuery = advisorBareProductQuery(normalizedInput, conversationProviderId)
        var recoveredIndependentQuery = false
        val deterministicLocationOutcomes =
            linkedMapOf<Triple<String, String, List<String>>, AdvisorLocationEvidence>()
        val verifiedByKey =
            linkedMapOf<VerifiedProductKey, VerifiedProductSnapshot>()
        val searchActionsByKey =
            linkedMapOf<String, AdvisorSearchAction>()

        fun emptyIndependentQuery(reason: String, status: String = "rejected") = AdvisorLocationEvidence(
            providerId = conversationProviderId, productId = null, status = status, reason = reason,
            coverage = "unknown", checkedIds = emptyList(), returnedIds = emptyList(), missingIds = emptyList(),
            locations = emptyList(), verifiedAtMillis = null, centralStock = null,
        )

        fun independentQueryEvidence(
            query: String,
            expectedBranchId: String,
            discovery: AdvisorToolExecutionResult.Success,
        ): AdvisorLocationEvidence {
            val matches = discovery.snapshots.filter { product ->
                product.providerId == conversationProviderId && product.effectiveBranchId == expectedBranchId &&
                    product.verifiedAt > 0 && (product.stock == null || product.stock >= 0) &&
                    (product.centralStock == null || product.centralStock >= 0) &&
                    if (conversationProvider == OBI_PROVIDER_ID) {
                        product.effectiveProductId.matches(Regex("[0-9]{7}")) &&
                            (query.length != 7 || product.effectiveProductId == query)
                    } else {
                        product.effectiveProductId.matches(Regex("[0-9]{1,12}")) &&
                            (product.effectiveProductId.equals(query, ignoreCase = true) ||
                                advisorMatchesArticle(product.articleNumber, query))
                    }
            }.distinctBy { it.key }
            val product = matches.singleOrNull() ?: return emptyIndependentQuery(
                if (matches.size > 1) "ambiguous_product" else "untrusted_product",
                if (discovery.result.results.any { it.status == AdvisorQueryResultStatus.UNAVAILABLE }) "unavailable" else "rejected",
            )
            val branch = turnBranches?.singleOrNull { it.branchId.value == product.effectiveBranchId }
                ?: return emptyIndependentQuery("directory_unavailable", "unavailable")
            verifiedByKey[product.key] = product
            // A bare identifier authorizes ordinary exact-product discovery,
            // not a departments/all-market scan. These are ONLY the fresh
            // selected-branch facts returned by that existing discovery tool.
            val confirmed = product.stock != null
            return AdvisorLocationEvidence(
                providerId = conversationProviderId, productId = product.effectiveProductId,
                status = if (confirmed) "verified" else "unavailable",
                reason = if (confirmed) null else "no_trusted_rows",
                coverage = if (confirmed) "requested_subset" else "unknown",
                checkedIds = listOf(branch.branchId.value),
                returnedIds = if (confirmed) listOf(branch.branchId.value) else emptyList(),
                missingIds = if (confirmed) emptyList() else listOf(branch.branchId.value),
                locations = listOf(AdvisorLocationEntry(branch.branchId.value, branch.name.take(100), product.stock)),
                verifiedAtMillis = if (confirmed) product.verifiedAt else null,
                centralStock = if (confirmed) product.centralStock else null,
                stockUnit = product.stockUnit,
            )
        }

        suspend fun recoverIndependentQuery(query: String): AdvisorLocationEvidence {
            recoveredIndependentQuery = true
            // The mistaken location request already consumed one call. Actual
            // discovery consumes another; there are no synthesized model IDs
            // or extra continuations. Never borrow next turn's allowance.
            if (toolCalls >= MAX_LOCAL_TOOL_CALLS_PER_TURN) return emptyIndependentQuery("local_tool_limit_reached")
            toolCalls += 1
            val proposed = AdvisorToolArguments(
                storeNumber = conversationStoreNumber, providerId = conversationProviderId,
                queries = listOf(AdvisorToolQuery(query, MAX_TOOL_PRODUCTS)),
            )
            val authorized = when (val result = authorizeToolArguments(proposed)) {
                is ToolBranchAuthorization.Authorized -> result.arguments
                is ToolBranchAuthorization.Rejected -> return emptyIndependentQuery(result.reason)
                ToolBranchAuthorization.Unavailable -> return emptyIndependentQuery("directory_unavailable", "unavailable")
            }
            val discovery = when (val result = safeExecuteTool(conversationProviderId, authorized)) {
                is AdvisorToolExecutionResult.Success -> result
                else -> return emptyIndependentQuery("product_verification_unavailable", "unavailable")
            }
            return independentQueryEvidence(query, authorized.branchId, discovery)
        }

        while (true) {
            when (proxyResult) {
                is AdvisorProxyResult.Answer -> {
                    // A later LLM answer/retry must not turn a rejected OBIK
                    // substitution into a claimed stock of the selected product.
                    blockedObiFollowUpReason?.let { reason ->
                        return AdvisorUiState.Success(
                            text = renderAdvisorLocalFailure(reason),
                            responseId = proxyResult.responseId,
                            products = emptyList(), sources = emptyList(),
                            traceId = turnTraceId,
                        ).also(onState)
                    }
                    trustedObiFollowUpId?.takeUnless { independentObiDiscoveryObserved }?.let { id ->
                        val evidence = lastLocationEvidence
                        val verified = verifiedByKey.values.singleOrNull {
                            it.providerId == conversationProviderId &&
                                it.effectiveProductId == id &&
                                it.effectiveBranchId == trustedObiFollowUpBranch
                        }
                        // Ignore potentially inconsistent model prose and refs.
                        // A city-wide USER request is not satisfied by the single
                        // discovery lookup used to seed a multi-market verification.
                        val partialCity = obiCityWideMarketIds.isNotEmpty() &&
                            evidence?.checkedIds?.toSet() != obiCityWideMarketIds
                        val observed = evidence?.let(::renderAdvisorLocationDetails)
                            ?: renderAdvisorLocalFailure("untrusted_product")
                        return AdvisorUiState.Success(
                            text = lastDiscoveryRejectionReason?.let(::renderAdvisorLocalFailure)
                                ?: ("OBIK $id. " + observed +
                                    if (partialCity) "\nNie sprawdziłam jeszcze wszystkich marketów wskazanego miasta." else ""),
                            responseId = proxyResult.responseId,
                            products = if (lastDiscoveryRejectionReason != null || evidence == null)
                                emptyList() else listOfNotNull(verified),
                            sources = emptyList(),
                            traceId = turnTraceId,
                            locationReportEvidence = locationReportEvidence.toList(),
                        ).also(onState)
                    }
                    val selectedProducts = if (correctedGroupedModelObiks.isNotEmpty()) {
                        // A corrected grouped model plan may carry stale product
                        // refs. Only current-turn provider-verified cards can be
                        // displayed; keep OTHER independent grouped products.
                        verifiedByKey.values.filter { it.providerId == conversationProviderId }
                            .take(MAX_TOOL_PRODUCTS)
                    } else proxyResult.productRefs
                        .distinctBy { it.key }
                        .mapNotNull { reference ->
                            verifiedByKey[reference.key]
                        }
                        .take(MAX_TOOL_PRODUCTS)
                    val misleadingGroupedModelText = correctedGroupedModelObiks.any { badId ->
                        Regex("""(?<![0-9])${Regex.escape(badId)}(?![0-9])""")
                            .containsMatchIn(proxyResult.text)
                    }
                    val safeAnswerText = if (misleadingGroupedModelText) {
                        // A corrected model OBIK must not reappear in prose as
                        // another product's stock. Report only fresh local facts.
                        selectedProducts.joinToString(
                            separator = "\n",
                            prefix = "Bieżące zweryfikowane wyniki:\n",
                        ) { product ->
                            "${product.name} (OBIK ${product.effectiveProductId}, OBI ${product.effectiveBranchId}): " +
                                (product.stock?.let { "${it} szt." } ?: "stan niepotwierdzony")
                        }.ifBlank { renderAdvisorLocalFailure("untrusted_product") }
                    } else proxyResult.text
                    val identityOnlyCityWide = obiCityWideMarketIds.isNotEmpty() &&
                        lastLocationEvidence == null && lastDiscoveryRejectionReason == null
                    val requestedIds = AdvisorProductIdentityReferences.fromUserText(normalizedInput).referencedNumericIds
                    val requestedIdentityVerified = verifiedByKey.isNotEmpty() && requestedIds.all { id ->
                        verifiedByKey.values.any { it.effectiveProductId == id || it.articleNumber == id }
                    }
                    val completeLocationList = if (identityOnlyCityWide) {
                        if (!requestedIdentityVerified) renderAdvisorLocalFailure("untrusted_product") else
                            "Potwierdziłam tożsamość produktu. Nie sprawdziłam jeszcze stanów we wszystkich wskazanych marketach."
                    } else lastLocationEvidence
                        ?.let(::renderAdvisorLocationDetails)
                        ?: lastDiscoveryRejectionReason?.let(::renderAdvisorLocalFailure).orEmpty()
                    val authoritativeLocalText = lastLocationEvidence?.let {
                        completeLocationList.isNotBlank() &&
                            (verifiedByKey.isEmpty() || it.status != "verified" || it.coverage == "partial" || it.coverage == "unknown")
                    } == true || lastDiscoveryRejectionReason != null && lastLocationEvidence == null ||
                        obiCityWideMarketIds.isNotEmpty() || recoveredIndependentQuery
                    return AdvisorUiState.Success(
                        text = if (authoritativeLocalText) completeLocationList else if (completeLocationList.isBlank()) safeAnswerText else {
                            safeAnswerText + "\n\n" + completeLocationList
                        },
                        responseId = proxyResult.responseId,
                        products = selectedProducts,
                        sources = if (authoritativeLocalText || misleadingGroupedModelText) emptyList() else proxyResult.sources,
                        searchActions = searchActionsByKey.values.toList(),
                        traceId = turnTraceId,
                        locationReportEvidence = locationReportEvidence.toList(),
                    ).also(onState)
                }

                is AdvisorProxyResult.LocationToolRequest -> {
                    val locationRequest = proxyResult
                    runCatching { onDiagnosticEvent(SafeDiagnosticEvent(
                        DiagnosticSource.ADVISOR, DiagnosticStage.TOOL_START,
                        providerId = conversationProviderId, branchId = conversationStoreNumber,
                        traceId = turnTraceId, tool = "find_product_locations",
                    )) }
                    if (!toolAssistedObserved) {
                        toolAssistedObserved = true
                        observeToolSafely(onToolRequestObserved)
                    }
                    val directory = (loadTurnBranches() as? ProviderBranchResult.Available)?.branches.orEmpty()
                    if (conversationProvider == OBI_PROVIDER_ID) {
                        val scope = BranchResolver.resolveObiMarketScope(normalizedInput, directory, currentBranchId, false)
                        if (scope is BranchResolution.Ambiguous && scope.isCityWideScope) {
                            obiCityWideMarketIds = scope.candidates.map { it.branchId.value }.toSet()
                        }
                    }
                    val repeatKey = Triple(
                        locationRequest.arguments.providerId,
                        locationRequest.arguments.productId,
                        locationRequest.arguments.locations,
                    )
                    // A deterministic denial is returned unchanged for an
                    // identical retry, rather than spending a second local
                    // lookup budget (or performing duplicate provider work).
                    val priorDecision = deterministicLocationOutcomes[repeatKey]
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
                    } else if (blockedObiFollowUpReason != null) {
                        toolCalls += 1
                        inventoryAuthorizationFailure(
                            requireNotNull(blockedObiFollowUpReason), "rejected", conversationProviderId,
                        )
                    } else if (priorDecision != null) {
                        // Cached denials avoid duplicate provider work but still
                        // count as logical model tool requests (hard budget = 3).
                        toolCalls += 1
                        priorDecision
                    } else {
                        toolCalls += 1
                        onState(AdvisorUiState.RunningLocalTool)
                        try {
                            val authority = AdvisorInventoryAuthorizer.resolve(
                                locationRequest.arguments, conversationProviderId, conversationStoreNumber,
                                normalizedInput, verifiedByKey.values, historicalVerifiedProducts,
                                directory, locationHistory,
                            )
                            when (authority) {
                                is AdvisorInventoryAuthorization.Discovery -> recoverIndependentQuery(authority.query)
                                is AdvisorInventoryAuthorization.Inventory -> executeLocationsTool?.invoke(authority)
                                    ?: inventoryAuthorizationFailure("location_service_unavailable", "unavailable", conversationProviderId)
                                is AdvisorInventoryAuthorization.ClarificationRequired ->
                                    inventoryAuthorizationFailure(authority.reason, "rejected", conversationProviderId)
                                is AdvisorInventoryAuthorization.Denied -> inventoryAuthorizationFailure(
                                    authority.reason,
                                    if (authority.reason in setOf("directory_unavailable", "multi_product_tool_contract")) "unavailable" else "rejected",
                                    conversationProviderId,
                                )
                            }
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
                    // Product-trust and ambiguity decisions may change after
                    // a local discovery call in this same USER turn. Cache only
                    // scope/provider denials invariant under new verified cards.
                    if (priorDecision == null &&
                        evidence.status == "rejected" &&
                        isInvariantLocationDenial(evidence.reason)
                    ) {
                        deterministicLocationOutcomes[repeatKey] = evidence
                    }
                    // An invalid later location-tool request must not erase
                    // already VERIFIED stock from ordinary OBI discovery.
                    // Keep its denied continuation/diagnostics; any USER-requested
                    // wider city scope remains explicitly incomplete at rendering.
                    val preserveVerifiedObi = conversationProvider == OBI_PROVIDER_ID &&
                        trustedObiFollowUpId != null && evidence.status == "rejected" &&
                        lastLocationEvidence?.status == "verified" &&
                        lastLocationEvidence?.productId == trustedObiFollowUpId &&
                        lastLocationEvidence?.checkedIds?.any { it == trustedObiFollowUpBranch } == true
                    if (!preserveVerifiedObi) lastLocationEvidence = evidence
                    runCatching { onDiagnosticEvent(SafeDiagnosticEvent(
                        DiagnosticSource.ADVISOR, DiagnosticStage.TOOL_RESULT,
                        providerId = conversationProviderId, branchId = conversationStoreNumber,
                        traceId = turnTraceId, tool = "find_product_locations",
                        outcome = evidence.status, reason = evidence.reason,
                    )) }
                    if (evidence.providerId == "kwant-pl" && locationReportEvidence.size < MAX_LOCAL_TOOL_CALLS_PER_TURN) {
                        locationReportEvidence += evidence
                    }
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
                    val diagnosticTool = if (conversationProviderId == "kwant-pl") "find_products" else "find_obi_products"
                    runCatching { onDiagnosticEvent(SafeDiagnosticEvent(
                        DiagnosticSource.ADVISOR, DiagnosticStage.TOOL_START,
                        providerId = conversationProviderId, branchId = conversationStoreNumber,
                        traceId = turnTraceId, tool = diagnosticTool,
                    )) }
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
                            is ToolBranchAuthorization.Rejected -> {
                                lastDiscoveryRejectionReason = authorization.reason
                                AdvisorToolContinuation.RejectedStore(
                                    queries = arguments.queries,
                                    storeNumber = arguments.storeNumber,
                                    providerId = arguments.providerId,
                                )

                            }
                            ToolBranchAuthorization.Unavailable ->
                                return AdvisorUiState.Error(
                                    AdvisorError.PRODUCT_PROVIDER,
                                ).also(onState)

                            is ToolBranchAuthorization.Authorized -> {
                                lastDiscoveryRejectionReason = null
                                val resolvedArguments =
                                    authorization.arguments
                                onState(AdvisorUiState.RunningLocalTool)
                                val previousVerified = trustedObiFollowUpResult?.takeIf {
                                    trustedObiFollowUpId != null &&
                                        trustedObiFollowUpBranch == resolvedArguments.branchId &&
                                        resolvedArguments.queries.singleOrNull()?.query == trustedObiFollowUpId
                                }
                                if (previousVerified != null) {
                                    previousVerified // Same verified read, new logical tool-call budget still applies.
                                } else when (
                                    val localResult = safeExecuteTool(
                                        conversationProviderId =
                                            conversationProviderId,
                                        arguments = resolvedArguments,
                                    )
                                ) {
                                is AdvisorToolExecutionResult.Success -> {
                                    val finishesIndependentRecovery = recoveredIndependentQuery && independentQuery != null &&
                                        resolvedArguments.queries.singleOrNull()?.query?.trim()
                                            ?.equals(independentQuery, ignoreCase = true) == true
                                    if (finishesIndependentRecovery) {
                                        lastLocationEvidence = independentQueryEvidence(
                                            requireNotNull(independentQuery), resolvedArguments.branchId, localResult,
                                        )
                                    } else if (trustedObiFollowUpId != null &&
                                        resolvedArguments.queries.singleOrNull()?.query == trustedObiFollowUpId
                                    ) {
                                        val id = requireNotNull(trustedObiFollowUpId)
                                        trustedObiFollowUpBranch = resolvedArguments.branchId
                                        lastLocationEvidence = independentQueryEvidence(
                                            id, resolvedArguments.branchId, localResult,
                                        )
                                    } else {
                                        // A separate tool call or a grouped
                                        // search must keep its own fresh cards.
                                        if (conversationProvider == OBI_PROVIDER_ID) {
                                            independentObiDiscoveryObserved = true
                                        }
                                        localResult.snapshots.forEach { verifiedByKey[it.key] = it }
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
                                    AdvisorToolContinuation.Verified(localResult.result).also {
                                        if (trustedObiFollowUpId != null) trustedObiFollowUpResult = it
                                    }
                                }

                                    AdvisorToolExecutionResult.UnsupportedStore -> {
                                        lastDiscoveryRejectionReason = "tool_rejected"
                                        AdvisorToolContinuation.RejectedStore(
                                            queries =
                                                resolvedArguments.queries,
                                            storeNumber =
                                                resolvedArguments.storeNumber,
                                            providerId =
                                                resolvedArguments.providerId,
                                        )

                                    }
                                    AdvisorToolExecutionResult.Failure ->
                                        return AdvisorUiState.Error(
                                            AdvisorError.PRODUCT_PROVIDER,
                                            diagnostic = AdvisorFailureDiagnostic(
                                                kind = AdvisorProxyFailureKind.SERVICE,
                                                failureClass = AdvisorFailureClass.LOCAL_PROVIDER_FAILURE,
                                                traceId = turnTraceId,
                                            ),
                                        ).also(onState)
                                }
                            }
                        }

                    runCatching { onDiagnosticEvent(SafeDiagnosticEvent(
                        DiagnosticSource.ADVISOR, DiagnosticStage.TOOL_RESULT,
                        providerId = conversationProviderId, branchId = conversationStoreNumber,
                        traceId = turnTraceId, tool = diagnosticTool,
                        reason = lastDiscoveryRejectionReason,
                        outcome = when (continuation) {
                            is AdvisorToolContinuation.Verified -> "verified"
                            is AdvisorToolContinuation.RejectedStore -> "rejected"
                            else -> "unavailable"
                        },
                    )) }
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

    private fun isInvariantLocationDenial(reason: String?): Boolean =
        reason in setOf(
            "location_intent_required",
            "unknown_location",
            "location_not_authorized",
            "wrong_provider",
            "unsupported_provider",
        ) || reason?.startsWith("location_conflict_") == true


    private fun inventoryAuthorizationFailure(reason: String, status: String, providerId: String) =
        AdvisorLocationEvidence(
            providerId, null, status, reason, "unknown", emptyList(), emptyList(), emptyList(),
            emptyList(), null, null,
        )

    companion object {
        fun production(context: android.content.Context): AdvisorController {
            val attachmentStorage = AttachmentStorage(context.applicationContext)
            val proxyClient = AdvisorProxyClient(attachmentStorage = attachmentStorage)
            val providers = ProductProviderRegistry.production()
            val obiTool = FindObiProductsTool()
            val locationsTool = AdvisorLocationsTool(providers = providers)
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

    data class Rejected(val reason: String) : ToolBranchAuthorization
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

