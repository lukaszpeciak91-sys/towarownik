package pl.lukaszpeciak.towarownik

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorRequestGuardTest {
    @Test
    fun `switching conversations invalidates stale callback token`() {
        val guard = AdvisorRequestGuard()
        val oldToken = guard.token()

        assertTrue(
            guard.isCurrent(
                token = oldToken,
                expectedConversationId = 10L,
                activeConversationId = 10L,
            ),
        )

        guard.invalidate()

        assertFalse(
            guard.isCurrent(
                token = oldToken,
                expectedConversationId = 10L,
                activeConversationId = 20L,
            ),
        )
    }
}
