package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.agent.AdvisorUiState
import pl.lukaszpeciak.towarownik.conversation.PersistedWebSource

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
    fun `verified advisor cards survive save and restore with trusted url`() {
        val card = VerifiedProductUiModel(
            name = "Verified",
            obik = "1234567",
            grossPrice = BigDecimal("12.30"),
            stock = 0,
            productUrl = "https://www.obi.pl/p/1234567/trusted-exact",
            verifiedAt = 1_234_567L,
        )
        val completed = AdvisorCaseUiState(
            messages = listOf(
                AdvisorChatMessage(
                    role = ChatMessageRole.ASSISTANT,
                    text = "Use this.",
                    createdAt = 200L,
                    products = listOf(card),
                    persistedMessageId = 77L,
                ),
            ),
        )

        val restored = restoreAdvisorCase(saveAdvisorCase(completed))

        assertEquals(completed, restored)
        assertEquals(77L, restored.messages.single().persistedMessageId)
        assertEquals(
            "https://www.obi.pl/p/1234567/trusted-exact",
            verifiedProductOpenUrl(restored.messages.single().products.single()),
        )
    }

    @Test
    fun `web sources survive save and restore with https URL unchanged`() {
        val source = PersistedWebSource(
            title = "Manufacturer manual",
            url = "https://manufacturer.example/manual",
        )
        val completed = AdvisorCaseUiState(
            messages = listOf(
                AdvisorChatMessage(
                    role = ChatMessageRole.ASSISTANT,
                    text = "Cited answer.",
                    createdAt = 200L,
                    sources = listOf(source),
                    persistedMessageId = 88L,
                ),
            ),
        )

        val restored = restoreAdvisorCase(saveAdvisorCase(completed))

        assertEquals(listOf(source), restored.messages.single().sources)
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
    fun `completed assistant answer enables composer for next turn`() {
        assertTrue(
            isAdvisorComposerEnabled(
                AdvisorUiState.Success(
                    text = "Done",
                    responseId = "resp_done",
                ),
            ),
        )
        assertTrue(
            !isAdvisorComposerEnabled(
                AdvisorUiState.LoadingProxy,
            ),
        )
    }

    @Test
    fun `multi-turn interrupted trailing user restores only that text as draft`() {
        val interrupted = AdvisorCaseUiState(
            messages = listOf(
                AdvisorChatMessage(
                    role = ChatMessageRole.USER,
                    text = "Pierwszy turn",
                    createdAt = 100L,
                ),
                AdvisorChatMessage(
                    role = ChatMessageRole.ASSISTANT,
                    text = "Pierwsza odpowiedź",
                    createdAt = 200L,
                ),
                AdvisorChatMessage(
                    role = ChatMessageRole.USER,
                    text = "A coś tańszego?",
                    createdAt = 300L,
                ),
            ),
        )

        val restored = restoreAdvisorCase(saveAdvisorCase(interrupted))

        assertEquals("A coś tańszego?", restored.draft)
        assertEquals(2, restored.messages.size)
        assertEquals(ChatMessageRole.ASSISTANT, restored.messages.last().role)
    }

    @Test
    fun `deleting active conversation opens a fresh empty case`() {
        val fresh = freshAdvisorCaseAfterDelete(
            deletedConversationId = 42L,
            activeConversationId = 42L,
        )

        requireNotNull(fresh)
        assertEquals(AdvisorCaseUiState(), fresh)
        assertNull(
            freshAdvisorCaseAfterDelete(
                deletedConversationId = 42L,
                activeConversationId = 99L,
            ),
        )
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
