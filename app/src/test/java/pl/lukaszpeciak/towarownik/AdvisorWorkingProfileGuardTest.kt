package pl.lukaszpeciak.towarownik

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.agent.AdvisorError
import pl.lukaszpeciak.towarownik.agent.AdvisorUiState
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.WorkingProfile

class AdvisorWorkingProfileGuardTest {
    @Test
    fun `OBI 075 reaches advisor and records one started turn`() = runBlocking {
        var advisorCalls = 0
        var startedTurns = 0
        var seenProvider: String? = null
        var seenBranch: String? = null

        val result = runAdvisorForWorkingProfile(
            workingProfile = WorkingProfile(
                providerId = OBI_PROVIDER_ID,
                branchId = BranchId("075"),
            ),
            onAdvisorStarted = {
                startedTurns += 1
            },
        ) { providerId, branchId ->
            advisorCalls += 1
            seenProvider = providerId
            seenBranch = branchId
            AdvisorUiState.Success(
                text = "Synthetic",
                responseId = "resp_1",
            )
        }

        assertEquals(1, advisorCalls)
        assertEquals(1, startedTurns)
        assertEquals("obi-pl", seenProvider)
        assertEquals("075", seenBranch)
        assertTrue(result is AdvisorUiState.Success)
    }

    @Test
    fun `KWANT 205 reaches advisor with its own profile and records one started turn`() = runBlocking {
        var advisorCalls = 0
        var startedTurns = 0
        var seenProvider: String? = null
        var seenBranch: String? = null

        val result = runAdvisorForWorkingProfile(
            workingProfile = WorkingProfile(
                providerId = KWANT_PROVIDER_ID,
                branchId = BranchId("205"),
            ),
            onAdvisorStarted = {
                startedTurns += 1
            },
        ) { providerId, branchId ->
            advisorCalls += 1
            seenProvider = providerId
            seenBranch = branchId
            AdvisorUiState.Success(
                text = "KWANT answer",
                responseId = "resp_kwant",
            )
        }

        assertEquals(1, advisorCalls)
        assertEquals(1, startedTurns)
        assertEquals("kwant-pl", seenProvider)
        assertEquals("205", seenBranch)
        assertTrue(result is AdvisorUiState.Success)
    }

    @Test
    fun `unknown provider remains unsupported and records no AI turn`() = runBlocking {
        var advisorCalls = 0
        var startedTurns = 0

        val result = runAdvisorForWorkingProfile(
            workingProfile = WorkingProfile(
                providerId = ProviderId("unknown-provider"),
                branchId = BranchId("x"),
            ),
            onAdvisorStarted = {
                startedTurns += 1
            },
        ) { _, _ ->
            advisorCalls += 1
            AdvisorUiState.Idle
        }

        assertEquals(0, advisorCalls)
        assertEquals(0, startedTurns)
        assertEquals(
            AdvisorUiState.Error(AdvisorError.UNSUPPORTED_PROVIDER),
            result,
        )
    }
}
