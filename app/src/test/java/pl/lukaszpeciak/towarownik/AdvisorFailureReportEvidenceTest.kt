package pl.lukaszpeciak.towarownik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorFailureReportEvidenceTest {
    private val trace = "11111111-1111-4111-8111-111111111111"
    private val secondTrace = "22222222-2222-4222-8222-222222222222"

    @Test
    fun `INC-004 failed advisor turn has a dedicated report type rather than prior assistant response`() {
        val failureType = ProblemReportType.valueOf("ADVISOR_FAILURE")
        val category = ProblemReportCategory.valueOf("ADVISOR_REQUEST_FAILED")
        assertNotEquals(ProblemReportType.ASSISTANT_RESPONSE, failureType)
        assertNotEquals(ProblemReportType.GENERAL, failureType)
        assertEquals(failureType, category.reportType)
        assertTrue(reportCategories(failureType).contains(category))
    }

    @Test
    fun `a failed turn pins exact trace and conversation while report editor is open`() {
        val store = AdvisorFailureReportEvidenceStore()
        val failure = AdvisorFailureReportEvidence(
            conversationId = 205L,
            turnId = trace,
            diagnostic = "kind=SERVICE http=502 proxy=upstream_failure endpoint=message trace=$trace",
        )
        store.record(failure)
        assertNull(store.select(206L))
        assertEquals(failure, store.select(205L))
        assertEquals(failure, store.selected)
        assertEquals(failure, store.current)
        assertFalse(store.selected!!.diagnostic!!.contains("previous_success"))
        assertTrue(store.selected!!.diagnostic!!.contains("endpoint=message"))
    }

    @Test
    fun `retry and success release failure evidence and never inherit old trace`() {
        val store = AdvisorFailureReportEvidenceStore()
        store.record(AdvisorFailureReportEvidence(205L, trace, "endpoint=start http=502"))
        assertTrue(store.select(205L) != null)
        store.clear() // New admitted turn, or successful completion.
        assertNull(store.current)
        assertNull(store.selected)
        store.record(AdvisorFailureReportEvidence(205L, secondTrace, "endpoint=continue http=400"))
        assertEquals(secondTrace, store.select(205L)?.turnId)
        assertFalse(store.selected!!.diagnostic!!.contains(trace))
        store.clear()
        assertNull(store.current)
    }

    @Test
    fun `switch and deletion clear only matching conversation evidence`() {
        val store = AdvisorFailureReportEvidenceStore()
        store.record(AdvisorFailureReportEvidence(205L, trace, "endpoint=continue http=400"))
        store.select(205L)
        store.clearConversation(206L)
        assertEquals(trace, store.current?.turnId)
        store.clearConversation(205L)
        assertNull(store.current)
        assertNull(store.selected)
    }

    @Test
    fun `failure evidence does not accept prompt sized diagnostic or malformed identity`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AdvisorFailureReportEvidence(205L, "raw-prompt", null)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AdvisorFailureReportEvidence(205L, trace, "CANARY".repeat(100))
        }
    }
}
