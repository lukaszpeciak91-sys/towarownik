package pl.lukaszpeciak.towarownik.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorTurnStoreAuthorizationTest {
    @Test
    fun `OBI 075 keeps existing current-store and explicit-market authorization semantics`() {
        val authorization = AdvisorTurnStoreAuthorization.capture(
            conversationStoreNumber = "075",
            currentUserMessage = "Porównaj z marketem 074",
        )

        assertTrue(authorization.isAuthorized("075"))
        assertTrue(authorization.isAuthorized("074"))
        assertFalse(authorization.isAuthorized("078"))
    }

    @Test
    fun `OBI alternate market is not authorized from embedded or unsupported digits`() {
        val embedded = AdvisorTurnStoreAuthorization.capture(
            conversationStoreNumber = "075",
            currentUserMessage = "Kod 1074 nie jest numerem marketu",
        )
        val unsupported = AdvisorTurnStoreAuthorization.capture(
            conversationStoreNumber = "075",
            currentUserMessage = "Sprawdź market 999",
        )

        assertFalse(embedded.isAuthorized("074"))
        assertFalse(unsupported.isAuthorized("999"))
        assertTrue(embedded.isAuthorized("075"))
        assertTrue(unsupported.isAuthorized("075"))
    }
}
