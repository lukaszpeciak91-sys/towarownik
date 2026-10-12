package pl.lukaszpeciak.towarownik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorFailureReportEvidenceTest {
    @Test
    fun `INC-004 RED failed advisor turn has a dedicated report type rather than prior assistant response`() {
        val failureType = ProblemReportType.valueOf("ADVISOR_FAILURE")
        val category = ProblemReportCategory.valueOf("ADVISOR_REQUEST_FAILED")
        assertNotEquals(ProblemReportType.ASSISTANT_RESPONSE, failureType)
        assertNotEquals(ProblemReportType.GENERAL, failureType)
        assertEquals(failureType, category.reportType)
        assertTrue(reportCategories(failureType).contains(category))
    }
}
