package pl.lukaszpeciak.towarownik.aiusage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.lukaszpeciak.towarownik.agent.AdvisorRequestType
import pl.lukaszpeciak.towarownik.agent.AdvisorUsage

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiUsageRepositoryTest {
    private lateinit var repository: AiUsageRepository

    @Before
    fun setUp() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(
            "ai-usage-test-" + System.nanoTime(),
            Context.MODE_PRIVATE,
        )
        repository = AiUsageRepository(
            preferences = preferences,
            now = { 1_700_000_000_000L },
        )
    }

    @Test
    fun `one paid response is counted with exact decimal cost`() {
        repository.recordTurnStarted()
        repository.recordOpenAiResponse(
            usage(
                requestType = AdvisorRequestType.START,
                input = 1_000,
                cached = 400,
                output = 100,
                reasoning = 50,
                total = 1_100,
                cost = "0.000248",
            ),
        )

        val snapshot = repository.snapshot()
        assertEquals(1L, snapshot.requests)
        assertEquals(1L, snapshot.turns)
        assertEquals(1_000L, snapshot.inputTokens)
        assertEquals(400L, snapshot.cachedInputTokens)
        assertEquals(0L, snapshot.cacheWriteTokens)
        assertEquals(100L, snapshot.outputTokens)
        assertEquals(50L, snapshot.reasoningTokens)
        assertEquals(1_100L, snapshot.totalTokens)
        assertEquals(
            BigDecimal("0.000248"),
            snapshot.estimatedCostUsd,
        )
    }

    @Test
    fun `tool assisted START plus CONTINUE sums every response`() {
        repository.recordTurnStarted()
        repository.recordToolAssistedTurn()
        repository.recordOpenAiResponse(
            usage(
                requestType = AdvisorRequestType.START,
                input = 100,
                cached = 20,
                output = 10,
                reasoning = 4,
                total = 110,
                cost = "0.00003",
            ),
        )
        repository.recordOpenAiResponse(
            usage(
                requestType = AdvisorRequestType.CONTINUE,
                input = 150,
                cached = 100,
                output = 30,
                reasoning = 8,
                total = 180,
                cost = "0.000048",
            ),
        )

        val snapshot = repository.snapshot()
        assertEquals(2L, snapshot.requests)
        assertEquals(1L, snapshot.turns)
        assertEquals(1L, snapshot.toolAssistedTurns)
        assertEquals(250L, snapshot.inputTokens)
        assertEquals(120L, snapshot.cachedInputTokens)
        assertEquals(0L, snapshot.cacheWriteTokens)
        assertEquals(40L, snapshot.outputTokens)
        assertEquals(12L, snapshot.reasoningTokens)
        assertEquals(BigDecimal("0.000078"), snapshot.estimatedCostUsd)
    }

    @Test
    fun `cache-write tokens aggregate globally and per model`() {
        repository.recordOpenAiResponse(
            usage(
                input = 1_000,
                cached = 300,
                cacheWrite = 200,
                cost = "0.00027",
            ),
        )
        repository.recordOpenAiResponse(
            usage(
                input = 500,
                cached = 100,
                cacheWrite = 50,
                cost = "0.00015",
            ),
        )

        val snapshot = repository.snapshot()
        assertEquals(250L, snapshot.cacheWriteTokens)
        assertEquals(
            250L,
            snapshot.models.single().cacheWriteTokens,
        )
    }

    @Test
    fun `latest measured model comes from recorded usage`() {
        assertNull(repository.snapshot().latestModel)

        repository.recordOpenAiResponse(
            usage(
                model = "gpt-5.6-luna",
                pricingVersion =
                    "openai-gpt-5.6-luna-2026-09-27-v2",
            ),
        )
        assertEquals(
            "gpt-5.6-luna",
            repository.snapshot().latestModel,
        )

        repository.recordOpenAiResponse(
            usage(model = "gpt-6-luna"),
        )
        assertEquals(
            "gpt-6-luna",
            repository.snapshot().latestModel,
        )
    }

    @Test
    fun `earlier successful response remains after later turn failure`() {
        repository.recordTurnStarted()
        repository.recordOpenAiResponse(
            usage(
                requestType = AdvisorRequestType.START,
                cost = "0.0002",
            ),
        )
        repository.recordToolAssistedTurn()

        val snapshotAfterSyntheticFailure = repository.snapshot()
        assertEquals(1L, snapshotAfterSyntheticFailure.requests)
        assertEquals(1L, snapshotAfterSyntheticFailure.toolAssistedTurns)
        assertEquals(
            BigDecimal("0.0002"),
            snapshotAfterSyntheticFailure.estimatedCostUsd,
        )
    }

    @Test
    fun `per model totals remain distinguishable`() {
        repository.recordOpenAiResponse(
            usage(
                model = "gpt-5.6-luna",
                cost = "0.1",
                pricingVersion =
                    "openai-gpt-5.6-luna-2026-09-27-v2",
            ),
        )
        repository.recordOpenAiResponse(
            usage(model = "gpt-6-luna", cost = "0.2"),
        )

        val models = repository.snapshot().models
            .associateBy { it.model }
        assertEquals(2, models.size)
        assertEquals(
            BigDecimal("0.1"),
            models.getValue("gpt-5.6-luna").estimatedCostUsd,
        )
        assertEquals(
            BigDecimal("0.2"),
            models.getValue("gpt-6-luna").estimatedCostUsd,
        )
    }

    @Test
    fun `budget baseline subtracts only spend after configuration`() {
        repository.recordOpenAiResponse(
            usage(cost = "0.50"),
        )
        repository.configureBudget(BigDecimal("5.00"))
        assertEquals(
            BigDecimal("5.00"),
            repository.snapshot().budget?.remainingBudgetUsd,
        )

        repository.recordOpenAiResponse(
            usage(cost = "0.75"),
        )

        assertEquals(
            BigDecimal("4.25"),
            repository.snapshot().budget?.remainingBudgetUsd,
        )
    }

    @Test
    fun `below one dollar warning fires once and rearms after reset`() {
        repository.configureBudget(BigDecimal("1.0001"))
        assertFalse(repository.consumePendingBudgetWarning())

        repository.recordOpenAiResponse(
            usage(cost = "0.0002"),
        )
        assertTrue(repository.consumePendingBudgetWarning())
        assertFalse(repository.consumePendingBudgetWarning())

        repository.recordOpenAiResponse(
            usage(cost = "0.10"),
        )
        assertFalse(repository.consumePendingBudgetWarning())

        repository.configureBudget(BigDecimal("2.00"))
        assertFalse(repository.consumePendingBudgetWarning())
        repository.recordOpenAiResponse(
            usage(cost = "1.10"),
        )
        assertTrue(repository.consumePendingBudgetWarning())
    }

    @Test
    fun `no configured budget means no warning`() {
        repository.recordOpenAiResponse(
            usage(cost = "20.00"),
        )

        assertNull(repository.snapshot().budget)
        assertFalse(repository.consumePendingBudgetWarning())
    }

    @Test
    fun `unpriced response makes remaining budget unavailable rather than guessed`() {
        repository.configureBudget(BigDecimal("5.00"))
        repository.recordOpenAiResponse(
            usage(
                model = "unknown-model",
                cost = null,
            ),
        )

        val snapshot = repository.snapshot()
        assertEquals(1L, snapshot.unpricedRequests)
        assertNull(snapshot.budget?.remainingBudgetUsd)
        assertFalse(repository.consumePendingBudgetWarning())
    }

    private fun usage(
        model: String = "gpt-6-luna",
        requestType: AdvisorRequestType = AdvisorRequestType.START,
        input: Long = 100,
        cached: Long? = 0,
        cacheWrite: Long? = 0,
        output: Long = 10,
        reasoning: Long? = 0,
        total: Long = 110,
        cost: String? = "0.0001",
        pricingVersion: String =
            "openai-gpt-6-luna-2026-09-27-v1",
    ): AdvisorUsage =
        AdvisorUsage(
            model = model,
            requestType = requestType,
            inputTokens = input,
            cachedInputTokens = cached,
            cacheWriteTokens = cacheWrite,
            outputTokens = output,
            reasoningTokens = reasoning,
            totalTokens = total,
            estimatedCostUsd = cost?.let(::BigDecimal),
            pricingVersion = pricingVersion,
        )
}
