package pl.lukaszpeciak.towarownik

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.lukaszpeciak.towarownik.conversation.ConversationDatabase
import pl.lukaszpeciak.towarownik.conversation.ConversationRepository
import pl.lukaszpeciak.towarownik.conversation.MESSAGE_ROLE_ASSISTANT
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProblemReportResolverTest {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private lateinit var repository: ConversationRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        database = Room.databaseBuilder(
            context,
            ConversationDatabase::class.java,
            DB_NAME,
        ).build()
        repository = ConversationRepository(database.conversationDao())
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `assistant target resolves exact persisted message and products only through target`() = runBlocking {
        val started = repository.beginUserTurn(null, "First user", 100L)
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "First answer",
            finalResponseId = "resp_1",
            createdAt = 200L,
            products = listOf(snapshot("1234567")),
        )
        repository.beginUserTurn(started.conversationId, "Second user", 300L)
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Reported answer",
            finalResponseId = "resp_2",
            createdAt = 400L,
            products = listOf(snapshot("7654321")),
        )
        repository.beginUserTurn(started.conversationId, "Later user", 500L)
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Later answer",
            finalResponseId = "resp_3",
            createdAt = 600L,
        )
        val persisted = requireNotNull(repository.load(started.conversationId))
        val target = persisted.messages.single { it.text == "Reported answer" }

        val resolved = ProblemReportResolver(repository).resolve(
            ProblemReportRequest(
                type = ProblemReportType.ASSISTANT_RESPONSE,
                category = ProblemReportCategory.WRONG_PRODUCT_SELECTION,
                description = "",
                includeConversation = true,
                conversationId = started.conversationId,
                reportedMessageId = target.id,
            ),
        )

        require(resolved is ProblemReportResolution.Success)
        assertEquals(target.id, resolved.evidence.reportedMessage?.id)
        assertEquals(MESSAGE_ROLE_ASSISTANT, resolved.evidence.reportedMessage?.role)
        assertEquals("7654321", resolved.evidence.reportedMessage?.products?.single()?.obik)
        assertEquals(
            listOf("First user", "First answer", "Second user", "Reported answer"),
            resolved.evidence.conversationMessages.map { it.text },
        )
        assertFalse(
            resolved.evidence.conversationMessages.any {
                it.text == "Later user" || it.text == "Later answer"
            },
        )
    }

    @Test
    fun `user message cannot be assistant report target`() = runBlocking {
        val started = repository.beginUserTurn(null, "User target", 100L)
        val persisted = requireNotNull(repository.load(started.conversationId))
        val userMessageId = persisted.messages.single().id

        val resolved = ProblemReportResolver(repository).resolve(
            ProblemReportRequest(
                type = ProblemReportType.ASSISTANT_RESPONSE,
                category = ProblemReportCategory.UNEXPECTED_BEHAVIOR,
                description = "",
                includeConversation = false,
                conversationId = started.conversationId,
                reportedMessageId = userMessageId,
            ),
        )

        assertTrue(resolved is ProblemReportResolution.TargetUnavailable)
    }

    @Test
    fun `message from another conversation is rejected`() = runBlocking {
        val first = completedConversation("First", "First answer", 100L)
        val second = completedConversation("Second", "Second answer", 300L)
        val otherMessage = requireNotNull(repository.load(second))
            .messages
            .single { it.role == MESSAGE_ROLE_ASSISTANT }

        val resolved = ProblemReportResolver(repository).resolve(
            ProblemReportRequest(
                type = ProblemReportType.ASSISTANT_RESPONSE,
                category = ProblemReportCategory.ASSISTANT_OTHER,
                description = "",
                includeConversation = false,
                conversationId = first,
                reportedMessageId = otherMessage.id,
            ),
        )

        assertTrue(resolved is ProblemReportResolution.TargetUnavailable)
    }

    @Test
    fun `deleted assistant target fails safely`() = runBlocking {
        val id = completedConversation("Delete", "Gone answer", 100L)
        val target = requireNotNull(repository.load(id))
            .messages
            .single { it.role == MESSAGE_ROLE_ASSISTANT }
        repository.deleteConversation(id)

        val resolved = ProblemReportResolver(repository).resolve(
            ProblemReportRequest(
                type = ProblemReportType.ASSISTANT_RESPONSE,
                category = ProblemReportCategory.INCORRECT_FABRICATED,
                description = "",
                includeConversation = false,
                conversationId = id,
                reportedMessageId = target.id,
            ),
        )

        assertTrue(resolved is ProblemReportResolution.TargetUnavailable)
    }

    @Test
    fun `general conversation evidence uses persisted messages and excludes draft`() = runBlocking {
        val id = completedConversation("User message", "Assistant message", 100L)
        repository.updateDraft(id, "UNSENT DRAFT")

        val resolved = ProblemReportResolver(repository).resolve(
            ProblemReportRequest(
                type = ProblemReportType.GENERAL,
                category = ProblemReportCategory.APP_PROBLEM,
                description = "General problem",
                includeConversation = true,
                conversationId = id,
            ),
        )

        require(resolved is ProblemReportResolution.Success)
        assertEquals(
            listOf("User message", "Assistant message"),
            resolved.evidence.conversationMessages.map { it.text },
        )
        assertFalse(
            resolved.evidence.conversationMessages.any {
                it.text == "UNSENT DRAFT"
            },
        )
    }

    private suspend fun completedConversation(
        user: String,
        assistant: String,
        startAt: Long,
    ): Long {
        val started = repository.beginUserTurn(null, user, startAt)
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = assistant,
            finalResponseId = "resp_${started.conversationId}",
            createdAt = startAt + 100L,
        )
        return started.conversationId
    }

    private fun snapshot(
        obik: String,
    ) = VerifiedProductSnapshot(
        obik = obik,
        name = "Product $obik",
        stock = 0,
        grossPrice = BigDecimal("10.00"),
        productUrl = "https://example.invalid/p/$obik",
        verifiedAt = 150L,
    )

    private companion object {
        const val DB_NAME = "problem-report-resolver-test.db"
    }
}
