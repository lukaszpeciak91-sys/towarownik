package pl.lukaszpeciak.towarownik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatShellModelsTest {
    @Test
    fun `new case clears draft and rendered messages`() {
        val previous = AdvisorCaseUiState(
            draft = "unfinished",
            messages = listOf(
                AdvisorChatMessage(
                    role = ChatMessageRole.USER,
                    text = "old request",
                    createdAt = 123L,
                ),
                AdvisorChatMessage(
                    role = ChatMessageRole.ASSISTANT,
                    text = "old answer",
                    createdAt = 456L,
                ),
            ),
        )

        val fresh = previous.newCase()

        assertEquals("", fresh.draft)
        assertTrue(fresh.messages.isEmpty())
    }

    @Test
    fun `advisor display normalization hides simple markdown markers`() {
        assertEquals(
            "Produkt\nDobra opcja",
            normalizeAdvisorDisplayText(
                """
                **Produkt**
                `Dobra opcja`
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `chat message carries creation timestamp`() {
        val message = AdvisorChatMessage(
            role = ChatMessageRole.USER,
            text = "test",
            createdAt = 1_234_567L,
        )

        assertEquals(1_234_567L, message.createdAt)
    }
}
