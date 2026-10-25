package pl.lukaszpeciak.towarownik

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartResumeTest {
    @Test fun shortReturnKeepsConversation() {
        assertFalse(shouldSmartResume(100L, 100L + SMART_RESUME_TIMEOUT_MILLIS - 1, false, false, false))
    }

    @Test fun exactThresholdAndLongReturnOpenHome() {
        assertTrue(shouldSmartResume(100L, 100L + SMART_RESUME_TIMEOUT_MILLIS, false, false, false))
        assertTrue(shouldSmartResume(100L, 100L + SMART_RESUME_TIMEOUT_MILLIS + 1, false, false, false))
    }

    @Test fun freshActivityAndRecreationWithoutPriorStopDoNotTimeout() {
        assertFalse(shouldSmartResume(null, SMART_RESUME_TIMEOUT_MILLIS * 4, false, false, false))
        assertFalse(shouldSmartResume(100L, 99L, false, false, false))
    }

    @Test fun unfinishedWorkAlwaysPreventsNavigation() {
        val elapsed = SMART_RESUME_TIMEOUT_MILLIS + 100
        assertFalse(shouldSmartResume(0L, elapsed, true, false, false))
        assertFalse(shouldSmartResume(0L, elapsed, false, true, false))
        assertFalse(shouldSmartResume(0L, elapsed, false, false, true))
    }
}
