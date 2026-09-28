package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.aiusage.AiUsageModelTotals
import pl.lukaszpeciak.towarownik.aiusage.AiUsageSnapshot

class AiUsageScreenTest {
    @Test
    fun `model display is unavailable before measured usage`() {
        assertNull(
            measuredModelForDisplay(snapshot()),
        )
    }

    @Test
    fun `model display comes from latest measured usage`() {
        assertEquals(
            "measured-model",
            measuredModelForDisplay(
                snapshot(
                    latestModel = "measured-model",
                    models = listOf(modelTotals("older-model")),
                ),
            ),
        )
    }

    @Test
    fun `single persisted model can provide measured fallback`() {
        assertEquals(
            "recorded-model",
            measuredModelForDisplay(
                snapshot(
                    models = listOf(modelTotals("recorded-model")),
                ),
            ),
        )
    }

    @Test
    fun `unpriced responses mark known cost as partial`() {
        assertFalse(
            knownCostIsPartial(
                snapshot(unpricedRequests = 0),
            ),
        )
        assertTrue(
            knownCostIsPartial(
                snapshot(unpricedRequests = 1),
            ),
        )
    }

    private fun snapshot(
        latestModel: String? = null,
        unpricedRequests: Long = 0,
        models: List<AiUsageModelTotals> = emptyList(),
    ): AiUsageSnapshot =
        AiUsageSnapshot(
            trackingStartedAt = null,
            latestModel = latestModel,
            requests = 0,
            turns = 0,
            toolAssistedTurns = 0,
            inputTokens = 0,
            cachedInputTokens = null,
            cacheWriteTokens = null,
            outputTokens = 0,
            reasoningTokens = null,
            totalTokens = 0,
            webSearchCalls = 0,
            estimatedCostUsd = BigDecimal.ZERO,
            unpricedRequests = unpricedRequests,
            models = models,
            budget = null,
        )

    private fun modelTotals(
        model: String,
    ): AiUsageModelTotals =
        AiUsageModelTotals(
            model = model,
            requests = 1,
            inputTokens = 1,
            cachedInputTokens = 0,
            cacheWriteTokens = 0,
            outputTokens = 1,
            reasoningTokens = 0,
            totalTokens = 2,
            webSearchCalls = 0,
            estimatedCostUsd = BigDecimal("0.0000014"),
        )
}
