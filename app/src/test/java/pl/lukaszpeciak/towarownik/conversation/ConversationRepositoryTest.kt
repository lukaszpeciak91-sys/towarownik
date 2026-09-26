package pl.lukaszpeciak.towarownik.conversation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private lateinit var repository: ConversationRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `completed conversation survives database recreation`() = runBlocking {
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "Potrzebuję kleju do MDF",
            createdAt = 100L,
        )
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Synthetic answer",
            finalResponseId = "resp_final",
            createdAt = 200L,
        )

        database.close()
        openDatabase()

        val restored = repository.load(started.conversationId)

        requireNotNull(restored)
        assertEquals("resp_final", restored.lastResponseId)
        assertEquals(
            listOf(MESSAGE_ROLE_USER, MESSAGE_ROLE_ASSISTANT),
            restored.messages.map { it.role },
        )
        assertEquals(
            listOf("Potrzebuję kleju do MDF", "Synthetic answer"),
            restored.messages.map { it.text },
        )
    }

    @Test
    fun `messages preserve chronological ordering and timestamps`() = runBlocking {
        val first = repository.beginUserTurn(
            null,
            "Pierwsza wiadomość",
            100L,
        )
        repository.completeAssistantTurn(
            first.conversationId,
            "Pierwsza odpowiedź",
            "resp_1",
            200L,
        )
        repository.beginUserTurn(
            first.conversationId,
            "Druga wiadomość",
            300L,
        )
        repository.completeAssistantTurn(
            first.conversationId,
            "Druga odpowiedź",
            "resp_2",
            400L,
        )

        val restored = requireNotNull(repository.load(first.conversationId))

        assertEquals(
            listOf(100L, 200L, 300L, 400L),
            restored.messages.map { it.createdAt },
        )
    }

    @Test
    fun `conversation list sorts by updatedAt newest first`() = runBlocking {
        val older = repository.beginUserTurn(null, "Starsza rozmowa", 100L)
        repository.completeAssistantTurn(
            older.conversationId,
            "Starsza odpowiedź",
            "resp_old",
            150L,
        )
        val newer = repository.beginUserTurn(null, "Nowsza rozmowa", 200L)
        repository.completeAssistantTurn(
            newer.conversationId,
            "Nowsza odpowiedź",
            "resp_new",
            250L,
        )

        val summaries = repository.observeConversations("").first()

        assertEquals(
            listOf(newer.conversationId, older.conversationId),
            summaries.map { it.id },
        )
    }

    @Test
    fun `first user message creates bounded normalized title`() {
        val title = deriveConversationTitle(
            "  Potrzebuję   bardzo długiej nazwy produktu do zabudowy meblowej MDF i jeszcze kilka słów  ",
        )

        assertTrue(title.length <= CONVERSATION_TITLE_MAX_CHARS)
        assertTrue(title.startsWith("Potrzebuję bardzo długiej"))
        assertTrue(title.endsWith("…"))
    }

    @Test
    fun `draft persists across database recreation`() = runBlocking {
        val started = repository.beginUserTurn(null, "Pierwsza sprawa", 100L)
        repository.completeAssistantTurn(
            started.conversationId,
            "Odpowiedź",
            "resp_1",
            200L,
        )
        repository.updateDraft(
            started.conversationId,
            "A coś tańszego?",
        )

        database.close()
        openDatabase()

        assertEquals(
            "A coś tańszego?",
            repository.load(started.conversationId)?.draft,
        )
    }

    @Test
    fun `untouched blank chat creates no database row`() = runBlocking {
        val conversations = repository.observeConversations("").first()

        assertTrue(conversations.isEmpty())
    }

    @Test
    fun `history search matches title user and assistant messages only locally`() = runBlocking {
        val titleMatch = completedConversation(
            firstUser = "Klej do MDF",
            assistant = "Pierwsza odpowiedź",
            startAt = 100L,
        )
        val userMatch = completedConversation(
            firstUser = "Inna sprawa",
            assistant = "Druga odpowiedź",
            startAt = 300L,
        )
        repository.beginUserTurn(
            userMatch,
            "Pytanie o fugę epoksydową",
            500L,
        )
        repository.completeAssistantTurn(
            userMatch,
            "Odpowiedź o fudze",
            "resp_user_match_2",
            600L,
        )
        val assistantMatch = completedConversation(
            firstUser = "Jeszcze inna sprawa",
            assistant = "Tu pojawia się silikon sanitarny",
            startAt = 700L,
        )
        completedConversation(
            firstUser = "Niepowiązana rozmowa",
            assistant = "Bez szukanej frazy",
            startAt = 900L,
        )

        assertEquals(
            listOf(titleMatch),
            repository.observeConversations("MDF").first().map { it.id },
        )
        assertEquals(
            listOf(userMatch),
            repository.observeConversations("fugę epoksydową").first().map { it.id },
        )
        assertEquals(
            listOf(assistantMatch),
            repository.observeConversations("silikon sanitarny").first().map { it.id },
        )
        assertTrue(
            repository.observeConversations("nieistniejąca fraza").first().isEmpty(),
        )
    }

    @Test
    fun `interrupted user turn becomes draft and keeps last completed response id`() = runBlocking {
        val started = repository.beginUserTurn(null, "Pierwszy turn", 100L)
        repository.completeAssistantTurn(
            started.conversationId,
            "Pierwsza odpowiedź",
            "resp_completed",
            200L,
        )
        val followUp = repository.beginUserTurn(
            started.conversationId,
            "A coś tańszego?",
            300L,
        )
        assertEquals("resp_completed", followUp.previousResponseId)

        val recovered = repository.recoverInterruptedTurn(
            started.conversationId,
        )

        requireNotNull(recovered)
        assertEquals("A coś tańszego?", recovered.draft)
        assertEquals("resp_completed", recovered.lastResponseId)
        assertEquals(2, recovered.messages.size)
        assertEquals(MESSAGE_ROLE_ASSISTANT, recovered.messages.last().role)
    }

    @Test
    fun `new conversation starts with no previous response id`() = runBlocking {
        val first = repository.beginUserTurn(null, "Pierwsza", 100L)
        repository.completeAssistantTurn(
            first.conversationId,
            "Odpowiedź",
            "resp_old",
            200L,
        )

        val fresh = repository.beginUserTurn(null, "Nowa sprawa", 300L)

        assertNull(fresh.previousResponseId)
        assertTrue(fresh.conversationId != first.conversationId)
    }

    private suspend fun completedConversation(
        firstUser: String,
        assistant: String,
        startAt: Long,
    ): Long {
        val started = repository.beginUserTurn(
            null,
            firstUser,
            startAt,
        )
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = assistant,
            finalResponseId = "resp_${started.conversationId}",
            createdAt = startAt + 100L,
        )
        return started.conversationId
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(
            context,
            ConversationDatabase::class.java,
            DB_NAME,
        ).build()
        repository = ConversationRepository(
            dao = database.conversationDao(),
            now = { 10_000L },
        )
    }

    private companion object {
        const val DB_NAME = "conversation-repository-test.db"
    }
}
