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
    fun `completed case survives save and restore unchanged`() {
        val completed = AdvisorCaseUiState(
            draft = "",
            messages = listOf(
                AdvisorChatMessage(
                    role = ChatMessageRole.USER,
                    text = "Szukam kleju do listew.",
                    createdAt = 100L,
                ),
                AdvisorChatMessage(
                    role = ChatMessageRole.ASSISTANT,
                    text = "Sprawdź ten produkt.",
                    createdAt = 200L,
                ),
            ),
        )

        val restored = restoreAdvisorCase(saveAdvisorCase(completed))

        assertEquals(completed, restored)
    }

    @Test
    fun `unfinished user only case restores as retryable draft`() {
        val interrupted = AdvisorCaseUiState(
            draft = "",
            messages = listOf(
                AdvisorChatMessage(
                    role = ChatMessageRole.USER,
                    text = "Potrzebuję silikonu do łazienki.",
                    createdAt = 300L,
                ),
            ),
        )

        val restored = restoreAdvisorCase(saveAdvisorCase(interrupted))

        assertEquals("Potrzebuję silikonu do łazienki.", restored.draft)
        assertTrue(restored.messages.isEmpty())
    }

    @Test
    fun `restoration never creates a submitted advisor case automatically`() {
        val interrupted = AdvisorCaseUiState(
            messages = listOf(
                AdvisorChatMessage(
                    role = ChatMessageRole.USER,
                    text = "Znajdź pochłaniacz wilgoci.",
                    createdAt = 400L,
                ),
            ),
        )

        val restored = restoreAdvisorCase(saveAdvisorCase(interrupted))

        assertEquals("Znajdź pochłaniacz wilgoci.", restored.draft)
        assertTrue(restored.messages.isEmpty())
        assertTrue(restored.draft.isNotBlank())
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
