package pl.lukaszpeciak.towarownik

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.agent.AdvisorError
import pl.lukaszpeciak.towarownik.agent.AdvisorUiState
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.WorkingProfile

class AdvisorWorkingProfileGuardTest {
    @Test
    fun `OBI 075 reaches existing advisor and records one started turn`() = runBlocking {
        var advisorCalls = 0
        var startedTurns = 0
        var seenStore: String? = null

        val result = runAdvisorForWorkingProfile(
            workingProfile = WorkingProfile(
                providerId = OBI_PROVIDER_ID,
                branchId = BranchId("075"),
            ),
            onAdvisorStarted = {
                startedTurns += 1
            },
        ) { storeNumber ->
            advisorCalls += 1
            seenStore = storeNumber
            AdvisorUiState.Success(
                text = "Synthetic",
                responseId = "resp_1",
            )
        }

        assertEquals(1, advisorCalls)
        assertEquals(1, startedTurns)
        assertEquals("075", seenStore)
        assertTrue(result is AdvisorUiState.Success)
    }

    @Test
    fun `KWANT 205 records no started turn and never enters OBI advisor boundary`() = runBlocking {
        var startedTurns = 0
        var advisorBoundaryEntered = false
        var authorizationEntered = false
        var proxyCalled = false
        var obiToolCalled = false

        val result = runAdvisorForWorkingProfile(
            workingProfile = WorkingProfile(
                providerId = KWANT_PROVIDER_ID,
                branchId = BranchId("205"),
            ),
            onAdvisorStarted = {
                startedTurns += 1
            },
        ) {
            advisorBoundaryEntered = true
            authorizationEntered = true
            proxyCalled = true
            obiToolCalled = true
            error("KWANT must not enter OBI advisor")
        }

        assertEquals(0, startedTurns)
        assertFalse(advisorBoundaryEntered)
        assertFalse(authorizationEntered)
        assertFalse(proxyCalled)
        assertFalse(obiToolCalled)
        assertEquals(
            AdvisorUiState.Error(AdvisorError.UNSUPPORTED_PROVIDER),
            result,
        )
    }

    @Test
    fun `KWANT guard never falls back to OBI 075`() = runBlocking {
        val stores = mutableListOf<String>()

        val result = runAdvisorForWorkingProfile(
            workingProfile = WorkingProfile(
                providerId = KWANT_PROVIDER_ID,
                branchId = BranchId("205"),
            ),
        ) { storeNumber ->
            stores += storeNumber
            AdvisorUiState.Idle
        }

        assertTrue(stores.isEmpty())
        assertEquals(
            AdvisorUiState.Error(AdvisorError.UNSUPPORTED_PROVIDER),
            result,
        )
    }
}
