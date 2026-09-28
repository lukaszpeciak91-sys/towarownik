package pl.lukaszpeciak.towarownik.aiusage

import android.content.Context
import android.content.SharedPreferences
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import pl.lukaszpeciak.towarownik.agent.AdvisorUsage

internal val AI_BUDGET_WARNING_THRESHOLD_USD = BigDecimal("1.00")

internal data class AiUsageModelTotals(
    val model: String,
    val requests: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long?,
    val cacheWriteTokens: Long?,
    val outputTokens: Long,
    val reasoningTokens: Long?,
    val totalTokens: Long,
    val webSearchCalls: Long,
    val estimatedCostUsd: BigDecimal,
)

internal data class AiBudgetSnapshot(
    val configuredStartingBudgetUsd: BigDecimal,
    val remainingBudgetUsd: BigDecimal?,
)

internal data class AiUsageSnapshot(
    val trackingStartedAt: Long?,
    val latestModel: String?,
    val requests: Long,
    val turns: Long,
    val toolAssistedTurns: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long?,
    val cacheWriteTokens: Long?,
    val outputTokens: Long,
    val reasoningTokens: Long?,
    val totalTokens: Long,
    val webSearchCalls: Long,
    val estimatedCostUsd: BigDecimal,
    val unpricedRequests: Long,
    val models: List<AiUsageModelTotals>,
    val budget: AiBudgetSnapshot?,
)

internal class AiUsageRepository(
    private val preferences: SharedPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun snapshot(): AiUsageSnapshot =
        readState().toSnapshot()

    fun recordTurnStarted() {
        mutate { state ->
            state.copy(
                trackingStartedAt =
                    state.trackingStartedAt ?: now(),
                turns = Math.addExact(state.turns, 1L),
            )
        }
    }

    fun recordToolAssistedTurn() {
        mutate { state ->
            state.copy(
                trackingStartedAt =
                    state.trackingStartedAt ?: now(),
                toolAssistedTurns =
                    Math.addExact(state.toolAssistedTurns, 1L),
            )
        }
    }

    fun recordOpenAiResponse(usage: AdvisorUsage?) {
        mutate { state ->
            val withRequest = state.copy(
                trackingStartedAt =
                    state.trackingStartedAt ?: now(),
                requests = Math.addExact(state.requests, 1L),
            )
            val updated = if (usage == null) {
                withRequest.copy(
                    unpricedRequests =
                        Math.addExact(withRequest.unpricedRequests, 1L),
                )
            } else {
                withRequest.addUsage(usage)
            }
            updated.refreshBudgetWarning()
        }
    }

    fun configureBudget(startingRemainingUsd: BigDecimal) {
        require(startingRemainingUsd.signum() >= 0)
        mutate { state ->
            state.copy(
                budget = BudgetState(
                    configuredStartingBudgetUsd =
                        startingRemainingUsd.stripTrailingZeros(),
                    baselineCostUsd = state.estimatedCostUsd,
                    baselineUnpricedRequests = state.unpricedRequests,
                    warningShownBelowThreshold = false,
                    warningPending = false,
                ),
            ).refreshBudgetWarning()
        }
    }

    fun clearBudget() {
        mutate { state ->
            state.copy(budget = null)
        }
    }

    fun consumePendingBudgetWarning(): Boolean {
        val state = readState()
        val budget = state.budget ?: return false
        if (!budget.warningPending) return false

        writeState(
            state.copy(
                budget = budget.copy(warningPending = false),
            ),
        )
        return true
    }

    private fun UsageState.addUsage(
        usage: AdvisorUsage,
    ): UsageState {
        val cost = usage.estimatedCostUsd
        val existingModel =
            models[usage.model] ?: ModelState(model = usage.model)
        val nextModel = existingModel.copy(
            requests = Math.addExact(existingModel.requests, 1L),
            inputTokens =
                Math.addExact(existingModel.inputTokens, usage.inputTokens),
            cachedInputTokens = usage.cachedInputTokens?.let {
                Math.addExact(existingModel.cachedInputTokens, it)
            } ?: existingModel.cachedInputTokens,
            cachedReportedRequests =
                if (usage.cachedInputTokens != null) {
                    Math.addExact(
                        existingModel.cachedReportedRequests,
                        1L,
                    )
                } else {
                    existingModel.cachedReportedRequests
                },
            cacheWriteTokens = usage.cacheWriteTokens?.let {
                Math.addExact(existingModel.cacheWriteTokens, it)
            } ?: existingModel.cacheWriteTokens,
            cacheWriteReportedRequests =
                if (usage.cacheWriteTokens != null) {
                    Math.addExact(
                        existingModel.cacheWriteReportedRequests,
                        1L,
                    )
                } else {
                    existingModel.cacheWriteReportedRequests
                },
            outputTokens =
                Math.addExact(existingModel.outputTokens, usage.outputTokens),
            reasoningTokens = usage.reasoningTokens?.let {
                Math.addExact(existingModel.reasoningTokens, it)
            } ?: existingModel.reasoningTokens,
            reasoningReportedRequests =
                if (usage.reasoningTokens != null) {
                    Math.addExact(
                        existingModel.reasoningReportedRequests,
                        1L,
                    )
                } else {
                    existingModel.reasoningReportedRequests
                },
            totalTokens =
                Math.addExact(existingModel.totalTokens, usage.totalTokens),
            webSearchCalls =
                Math.addExact(
                    existingModel.webSearchCalls,
                    usage.webSearchCalls,
                ),
            estimatedCostUsd =
                existingModel.estimatedCostUsd +
                    (cost ?: BigDecimal.ZERO),
        )

        return copy(
            latestModel = usage.model,
            inputTokens = Math.addExact(inputTokens, usage.inputTokens),
            cachedInputTokens = usage.cachedInputTokens?.let {
                Math.addExact(cachedInputTokens, it)
            } ?: cachedInputTokens,
            cachedReportedRequests =
                if (usage.cachedInputTokens != null) {
                    Math.addExact(cachedReportedRequests, 1L)
                } else {
                    cachedReportedRequests
                },
            cacheWriteTokens = usage.cacheWriteTokens?.let {
                Math.addExact(cacheWriteTokens, it)
            } ?: cacheWriteTokens,
            cacheWriteReportedRequests =
                if (usage.cacheWriteTokens != null) {
                    Math.addExact(cacheWriteReportedRequests, 1L)
                } else {
                    cacheWriteReportedRequests
                },
            outputTokens = Math.addExact(outputTokens, usage.outputTokens),
            reasoningTokens = usage.reasoningTokens?.let {
                Math.addExact(reasoningTokens, it)
            } ?: reasoningTokens,
            reasoningReportedRequests =
                if (usage.reasoningTokens != null) {
                    Math.addExact(reasoningReportedRequests, 1L)
                } else {
                    reasoningReportedRequests
                },
            totalTokens = Math.addExact(totalTokens, usage.totalTokens),
            webSearchCalls =
                Math.addExact(webSearchCalls, usage.webSearchCalls),
            estimatedCostUsd =
                estimatedCostUsd + (cost ?: BigDecimal.ZERO),
            unpricedRequests =
                if (cost == null) {
                    Math.addExact(unpricedRequests, 1L)
                } else {
                    unpricedRequests
                },
            models = models + (usage.model to nextModel),
        )
    }

    private fun UsageState.refreshBudgetWarning(): UsageState {
        val currentBudget = budget ?: return this
        val remaining = remainingBudget(currentBudget)
            ?: return this

        return if (
            remaining >= AI_BUDGET_WARNING_THRESHOLD_USD
        ) {
            copy(
                budget = currentBudget.copy(
                    warningShownBelowThreshold = false,
                    warningPending = false,
                ),
            )
        } else if (!currentBudget.warningShownBelowThreshold) {
            copy(
                budget = currentBudget.copy(
                    warningShownBelowThreshold = true,
                    warningPending = true,
                ),
            )
        } else {
            this
        }
    }

    private fun UsageState.remainingBudget(
        budget: BudgetState,
    ): BigDecimal? {
        if (unpricedRequests != budget.baselineUnpricedRequests) {
            return null
        }
        val spendSinceBudgetWasSet =
            estimatedCostUsd - budget.baselineCostUsd
        return budget.configuredStartingBudgetUsd -
            spendSinceBudgetWasSet
    }

    private fun UsageState.toSnapshot(): AiUsageSnapshot =
        AiUsageSnapshot(
            trackingStartedAt = trackingStartedAt,
            latestModel = latestModel,
            requests = requests,
            turns = turns,
            toolAssistedTurns = toolAssistedTurns,
            inputTokens = inputTokens,
            cachedInputTokens =
                cachedInputTokens.takeIf {
                    cachedReportedRequests > 0
                },
            cacheWriteTokens =
                cacheWriteTokens.takeIf {
                    cacheWriteReportedRequests > 0
                },
            outputTokens = outputTokens,
            reasoningTokens =
                reasoningTokens.takeIf {
                    reasoningReportedRequests > 0
                },
            totalTokens = totalTokens,
            webSearchCalls = webSearchCalls,
            estimatedCostUsd = estimatedCostUsd,
            unpricedRequests = unpricedRequests,
            models = models.values
                .sortedBy { it.model }
                .map { model ->
                    AiUsageModelTotals(
                        model = model.model,
                        requests = model.requests,
                        inputTokens = model.inputTokens,
                        cachedInputTokens =
                            model.cachedInputTokens.takeIf {
                                model.cachedReportedRequests > 0
                            },
                        cacheWriteTokens =
                            model.cacheWriteTokens.takeIf {
                                model.cacheWriteReportedRequests > 0
                            },
                        outputTokens = model.outputTokens,
                        reasoningTokens =
                            model.reasoningTokens.takeIf {
                                model.reasoningReportedRequests > 0
                            },
                        totalTokens = model.totalTokens,
                        webSearchCalls = model.webSearchCalls,
                        estimatedCostUsd = model.estimatedCostUsd,
                    )
                },
            budget = budget?.let { configured ->
                AiBudgetSnapshot(
                    configuredStartingBudgetUsd =
                        configured.configuredStartingBudgetUsd,
                    remainingBudgetUsd =
                        remainingBudget(configured),
                )
            },
        )

    private fun mutate(
        transform: (UsageState) -> UsageState,
    ) {
        synchronized(lock) {
            writeState(transform(readState()))
        }
    }

    private fun readState(): UsageState =
        preferences.getString(KEY_STATE, null)
            ?.let(::decodeState)
            ?: UsageState()

    private fun writeState(state: UsageState) {
        preferences.edit()
            .putString(KEY_STATE, encodeState(state))
            .commit()
    }

    companion object {
        private const val PREFERENCES_NAME = "taksula-ai-usage-v1"
        private const val KEY_STATE = "state"
        private val lock = Any()

        fun production(context: Context): AiUsageRepository =
            AiUsageRepository(
                context.getSharedPreferences(
                    PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ),
            )
    }
}

private data class UsageState(
    val trackingStartedAt: Long? = null,
    val latestModel: String? = null,
    val requests: Long = 0,
    val turns: Long = 0,
    val toolAssistedTurns: Long = 0,
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val cachedReportedRequests: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheWriteReportedRequests: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val reasoningReportedRequests: Long = 0,
    val totalTokens: Long = 0,
    val webSearchCalls: Long = 0,
    val estimatedCostUsd: BigDecimal = BigDecimal.ZERO,
    val unpricedRequests: Long = 0,
    val models: Map<String, ModelState> = emptyMap(),
    val budget: BudgetState? = null,
)

private data class ModelState(
    val model: String,
    val requests: Long = 0,
    val inputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val cachedReportedRequests: Long = 0,
    val cacheWriteTokens: Long = 0,
    val cacheWriteReportedRequests: Long = 0,
    val outputTokens: Long = 0,
    val reasoningTokens: Long = 0,
    val reasoningReportedRequests: Long = 0,
    val totalTokens: Long = 0,
    val webSearchCalls: Long = 0,
    val estimatedCostUsd: BigDecimal = BigDecimal.ZERO,
)

private data class BudgetState(
    val configuredStartingBudgetUsd: BigDecimal,
    val baselineCostUsd: BigDecimal,
    val baselineUnpricedRequests: Long,
    val warningShownBelowThreshold: Boolean,
    val warningPending: Boolean,
)

private fun encodeState(state: UsageState): String =
    buildJsonObject {
        putNullableLong("trackingStartedAt", state.trackingStartedAt)
        put(
            "latestModel",
            state.latestModel?.let(::JsonPrimitive) ?: JsonNull,
        )
        put("requests", state.requests)
        put("turns", state.turns)
        put("toolAssistedTurns", state.toolAssistedTurns)
        put("inputTokens", state.inputTokens)
        put("cachedInputTokens", state.cachedInputTokens)
        put("cachedReportedRequests", state.cachedReportedRequests)
        put("cacheWriteTokens", state.cacheWriteTokens)
        put(
            "cacheWriteReportedRequests",
            state.cacheWriteReportedRequests,
        )
        put("outputTokens", state.outputTokens)
        put("reasoningTokens", state.reasoningTokens)
        put(
            "reasoningReportedRequests",
            state.reasoningReportedRequests,
        )
        put("totalTokens", state.totalTokens)
        put("webSearchCalls", state.webSearchCalls)
        put("estimatedCostUsd", state.estimatedCostUsd.toPlainString())
        put("unpricedRequests", state.unpricedRequests)
        put(
            "models",
            buildJsonArray {
                state.models.values
                    .sortedBy { it.model }
                    .forEach { model ->
                        add(
                            buildJsonObject {
                                put("model", model.model)
                                put("requests", model.requests)
                                put("inputTokens", model.inputTokens)
                                put(
                                    "cachedInputTokens",
                                    model.cachedInputTokens,
                                )
                                put(
                                    "cachedReportedRequests",
                                    model.cachedReportedRequests,
                                )
                                put(
                                    "cacheWriteTokens",
                                    model.cacheWriteTokens,
                                )
                                put(
                                    "cacheWriteReportedRequests",
                                    model.cacheWriteReportedRequests,
                                )
                                put("outputTokens", model.outputTokens)
                                put(
                                    "reasoningTokens",
                                    model.reasoningTokens,
                                )
                                put(
                                    "reasoningReportedRequests",
                                    model.reasoningReportedRequests,
                                )
                                put("totalTokens", model.totalTokens)
                                put("webSearchCalls", model.webSearchCalls)
                                put(
                                    "estimatedCostUsd",
                                    model.estimatedCostUsd
                                        .toPlainString(),
                                )
                            },
                        )
                    }
            },
        )
        put(
            "budget",
            state.budget?.let { budget ->
                buildJsonObject {
                    put(
                        "configuredStartingBudgetUsd",
                        budget.configuredStartingBudgetUsd
                            .toPlainString(),
                    )
                    put(
                        "baselineCostUsd",
                        budget.baselineCostUsd.toPlainString(),
                    )
                    put(
                        "baselineUnpricedRequests",
                        budget.baselineUnpricedRequests,
                    )
                    put(
                        "warningShownBelowThreshold",
                        budget.warningShownBelowThreshold,
                    )
                    put("warningPending", budget.warningPending)
                }
            } ?: JsonNull,
        )
    }.toString()

private fun decodeState(raw: String): UsageState? =
    runCatching {
        val root = Json.parseToJsonElement(raw) as JsonObject
        val models = (root["models"] as? JsonArray)
            ?.associate { element ->
                val model = element as JsonObject
                val modelName = model.requireString("model")
                modelName to ModelState(
                    model = modelName,
                    requests = model.requireLong("requests"),
                    inputTokens = model.requireLong("inputTokens"),
                    cachedInputTokens =
                        model.requireLong("cachedInputTokens"),
                    cachedReportedRequests =
                        model.requireLong("cachedReportedRequests"),
                    cacheWriteTokens =
                        model.optionalNonNegativeLong("cacheWriteTokens"),
                    cacheWriteReportedRequests =
                        model.optionalNonNegativeLong(
                            "cacheWriteReportedRequests",
                        ),
                    outputTokens = model.requireLong("outputTokens"),
                    reasoningTokens =
                        model.requireLong("reasoningTokens"),
                    reasoningReportedRequests =
                        model.requireLong(
                            "reasoningReportedRequests",
                        ),
                    totalTokens = model.requireLong("totalTokens"),
                    webSearchCalls =
                        model.optionalNonNegativeLong("webSearchCalls"),
                    estimatedCostUsd =
                        model.requireDecimal("estimatedCostUsd"),
                )
            }
            ?: emptyMap()

        UsageState(
            trackingStartedAt = root.optionalLong("trackingStartedAt"),
            latestModel = root["latestModel"]
                ?.let { value ->
                    if (value is JsonNull) null
                    else value.jsonPrimitive.contentOrNull
                },
            requests = root.requireLong("requests"),
            turns = root.requireLong("turns"),
            toolAssistedTurns =
                root.requireLong("toolAssistedTurns"),
            inputTokens = root.requireLong("inputTokens"),
            cachedInputTokens =
                root.requireLong("cachedInputTokens"),
            cachedReportedRequests =
                root.requireLong("cachedReportedRequests"),
            cacheWriteTokens =
                root.optionalNonNegativeLong("cacheWriteTokens"),
            cacheWriteReportedRequests =
                root.optionalNonNegativeLong(
                    "cacheWriteReportedRequests",
                ),
            outputTokens = root.requireLong("outputTokens"),
            reasoningTokens =
                root.requireLong("reasoningTokens"),
            reasoningReportedRequests =
                root.requireLong("reasoningReportedRequests"),
            totalTokens = root.requireLong("totalTokens"),
            webSearchCalls =
                root.optionalNonNegativeLong("webSearchCalls"),
            estimatedCostUsd =
                root.requireDecimal("estimatedCostUsd"),
            unpricedRequests =
                root.requireLong("unpricedRequests"),
            models = models,
            budget = (root["budget"] as? JsonObject)?.let {
                BudgetState(
                    configuredStartingBudgetUsd =
                        it.requireDecimal(
                            "configuredStartingBudgetUsd",
                        ),
                    baselineCostUsd =
                        it.requireDecimal("baselineCostUsd"),
                    baselineUnpricedRequests =
                        it.requireLong("baselineUnpricedRequests"),
                    warningShownBelowThreshold =
                        it.requireBoolean(
                            "warningShownBelowThreshold",
                        ),
                    warningPending =
                        it.requireBoolean("warningPending"),
                )
            },
        )
    }.getOrNull()

private fun JsonObject.requireLong(key: String): Long =
    get(key)?.jsonPrimitive?.longOrNull
        ?.takeIf { it >= 0 }
        ?: error("Invalid long")

private fun JsonObject.optionalNonNegativeLong(
    key: String,
): Long =
    get(key)?.jsonPrimitive?.longOrNull
        ?.takeIf { it >= 0 }
        ?: 0L

private fun JsonObject.optionalLong(key: String): Long? {
    val value = get(key) ?: return null
    if (value is JsonNull) return null
    return value.jsonPrimitive.longOrNull
}

private fun JsonObject.requireString(key: String): String =
    get(key)?.jsonPrimitive?.contentOrNull
        ?.takeIf { it.isNotEmpty() }
        ?: error("Invalid string")

private fun JsonObject.requireDecimal(key: String): BigDecimal =
    requireString(key).toBigDecimalOrNull()
        ?.takeIf { it.signum() >= 0 }
        ?: error("Invalid decimal")

private fun JsonObject.requireBoolean(key: String): Boolean =
    get(key)?.jsonPrimitive?.contentOrNull
        ?.let {
            when (it) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
        ?: error("Invalid boolean")

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullableLong(
    key: String,
    value: Long?,
) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}
