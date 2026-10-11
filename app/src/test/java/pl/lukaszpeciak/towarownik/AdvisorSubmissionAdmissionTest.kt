package pl.lukaszpeciak.towarownik

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic test of the exact synchronous predicate used by MainActivity.
 * Does NOT drive Compose UI or Room/Proxy initiation, so does not establish full B05.
 */
class AdvisorSubmissionAdmissionTest {
    @Test
    fun `rapid second admission is rejected until first job completes`() = runBlocking {
        val blocker = CompletableDeferred<Unit>()
        var job: Job? = null
        var accepted = 0
        var simulatedPersistence = 0
        var simulatedProxyInitiations = 0

        fun submit(): Boolean {
            if (!canSubmitAdvisorTurn(job, attachmentImporting = false)) return false
            accepted++
            job = launch {
                blocker.await()
                simulatedPersistence++
                simulatedProxyInitiations++
            }
            return true
        }

        assertTrue(submit())
        assertFalse(submit())
        assertEquals(1, accepted)
        assertEquals(0, simulatedPersistence)
        assertEquals(0, simulatedProxyInitiations)
        blocker.complete(Unit)
        job!!.join()
        assertEquals(1, simulatedPersistence)
        assertEquals(1, simulatedProxyInitiations)
        assertTrue(canSubmitAdvisorTurn(job, attachmentImporting = false))
        assertTrue(submit())
        job!!.join()
        assertEquals(2, accepted)
    }

    @Test
    fun `cancelled request and attachment import do not admit an overlapping turn`() = runBlocking {
        val blocker = CompletableDeferred<Unit>()
        val running = launch { blocker.await() }
        assertFalse(canSubmitAdvisorTurn(running, attachmentImporting = false))
        assertFalse(canSubmitAdvisorTurn(null, attachmentImporting = true))
        running.cancelAndJoin()
        assertTrue(canSubmitAdvisorTurn(running, attachmentImporting = false))

        val guard = AdvisorRequestGuard()
        val oldToken = guard.token()
        guard.invalidate()
        assertFalse(guard.isCurrent(
            token = oldToken,
            expectedConversationId = 1L,
            activeConversationId = 2L,
        ))
    }
}
