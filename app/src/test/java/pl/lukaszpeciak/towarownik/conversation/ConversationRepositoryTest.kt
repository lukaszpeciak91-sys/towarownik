package pl.lukaszpeciak.towarownik.conversation

import android.content.Context
import java.math.BigDecimal
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
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot

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
    fun `assistant text response id and verified cards commit together`() = runBlocking {
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "Potrzebuję kleju",
            createdAt = 100L,
        )
        val products = listOf(
            snapshot(
                obik = "1234567",
                name = "First verified",
                stock = 3,
                price = BigDecimal("12.30"),
                url = "https://www.obi.pl/p/1234567/first",
                verifiedAt = 150L,
            ),
            snapshot(
                obik = "7654321",
                name = "Second verified",
                stock = null,
                price = null,
                url = "https://www.obi.pl/p/7654321/second",
                verifiedAt = 160L,
            ),
        )

        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Verified answer",
            finalResponseId = "resp_cards",
            products = products,
            createdAt = 200L,
        )

        val restored = requireNotNull(repository.load(started.conversationId))
        val assistant = restored.messages.last()

        assertEquals("resp_cards", restored.lastResponseId)
        assertEquals("Verified answer", assistant.text)
        assertEquals(products, assistant.products)
        assertEquals(2, messageProductCount(started.conversationId))
    }

    @Test
    fun `verified cards survive database recreation with order nullable fields and exact url`() = runBlocking {
        val started = repository.beginUserTurn(null, "Case", 100L)
        val first = snapshot(
            obik = "1111111",
            name = "First",
            stock = null,
            price = null,
            url = "https://www.obi.pl/custom/trusted-one",
            verifiedAt = 120L,
        )
        val second = snapshot(
            obik = "2222222",
            name = "Second",
            stock = 0,
            price = BigDecimal("99.9900"),
            url = "https://www.obi.pl/custom/trusted-two?x=1",
            verifiedAt = 130L,
        )
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Answer",
            finalResponseId = "resp",
            products = listOf(first, second),
            createdAt = 200L,
        )

        database.close()
        openDatabase()

        val cards = requireNotNull(repository.load(started.conversationId))
            .messages.last().products
        assertEquals(listOf("1111111", "2222222"), cards.map { it.obik })
        assertEquals(null, cards[0].stock)
        assertEquals(null, cards[0].grossPrice)
        assertEquals(0, cards[1].stock)
        assertEquals(BigDecimal("99.9900"), cards[1].grossPrice)
        assertEquals(first.productUrl, cards[0].productUrl)
        assertEquals(second.productUrl, cards[1].productUrl)
        assertEquals(listOf(120L, 130L), cards.map { it.verifiedAt })
    }

    @Test
    fun `assistant sources persist reopen and cascade with conversation deletion`() = runBlocking {
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "Sprawdź instrukcję producenta",
            createdAt = 100L,
        )
        val sources = listOf(
            PersistedWebSource(
                title = "Manufacturer manual",
                url = "https://manufacturer.example/manual",
                startIndex = 0,
                endIndex = 8,
            ),
            PersistedWebSource(
                title = "Technical sheet",
                url = "https://manufacturer.example/spec",
            ),
        )
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Verified web answer",
            finalResponseId = "resp_web",
            createdAt = 200L,
            sources = sources,
        )

        database.close()
        openDatabase()

        val restored = requireNotNull(
            repository.load(started.conversationId),
        )
        assertEquals(
            sources,
            restored.messages.last().sources,
        )
        assertEquals(2, messageSourceCount(started.conversationId))

        assertTrue(
            repository.deleteConversation(started.conversationId),
        )
        assertEquals(0, allMessageSourceCount())
    }

    @Test
    fun `web source model accepts only bounded https URLs`() {
        assertEquals(
            PersistedWebSource(
                title = "Manufacturer manual",
                url = "https://manufacturer.example/manual",
            ),
            persistedWebSourceOrNull(
                title = "  Manufacturer   manual ",
                url = "https://manufacturer.example/manual",
            ),
        )
        assertNull(
            persistedWebSourceOrNull(
                title = "Unsafe",
                url = "http://manufacturer.example/manual",
            ),
        )
        assertNull(
            persistedWebSourceOrNull(
                title = "Bad",
                url = "not-a-url",
            ),
        )
        assertNull(
            persistedWebSourceOrNull(
                title = "T".repeat(201),
                url = "https://manufacturer.example/manual",
            ),
        )
        assertNull(
            persistedWebSourceOrNull(
                title = "Valid",
                url = "https://manufacturer.example/" +
                    "x".repeat(2_048),
            ),
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
    fun `older than 30 days is deleted`() = runBlocking {
        val nowMillis = CONVERSATION_RETENTION_MILLIS * 10
        val cutoff = nowMillis - CONVERSATION_RETENTION_MILLIS
        val expired = completedConversationAt(
            title = "Expired case",
            updatedAt = cutoff - 1,
        )

        assertEquals(
            1,
            repository.cleanupExpiredConversations(nowMillis),
        )
        assertNull(repository.load(expired))
    }

    @Test
    fun `exactly 30 days old is kept`() = runBlocking {
        val nowMillis = CONVERSATION_RETENTION_MILLIS * 10
        val cutoff = nowMillis - CONVERSATION_RETENTION_MILLIS
        val kept = completedConversationAt(
            title = "Cutoff case",
            updatedAt = cutoff,
        )

        assertEquals(
            0,
            repository.cleanupExpiredConversations(nowMillis),
        )
        assertEquals(kept, repository.load(kept)?.id)
    }

    @Test
    fun `newer than 30 days is kept`() = runBlocking {
        val nowMillis = CONVERSATION_RETENTION_MILLIS * 10
        val cutoff = nowMillis - CONVERSATION_RETENTION_MILLIS
        val kept = completedConversationAt(
            title = "Recent case",
            updatedAt = cutoff + 1,
        )

        repository.cleanupExpiredConversations(nowMillis)

        assertEquals(kept, repository.load(kept)?.id)
    }

    @Test
    fun `conversation deletion cascades verified product snapshots`() = runBlocking {
        val id = completedConversationWithCard(
            title = "Delete cards",
            updatedAt = 200L,
        )
        assertEquals(1, messageProductCount(id))

        repository.deleteConversation(id)

        assertEquals(0, messageProductCount(id))
    }

    @Test
    fun `retention cleanup cascades verified product snapshots`() = runBlocking {
        val nowMillis = CONVERSATION_RETENTION_MILLIS * 10
        val cutoff = nowMillis - CONVERSATION_RETENTION_MILLIS
        val expired = completedConversationWithCard(
            title = "Expired cards",
            updatedAt = cutoff - 1,
        )
        assertEquals(1, messageProductCount(expired))

        repository.cleanupExpiredConversations(nowMillis)

        assertEquals(0, messageProductCount(expired))
    }

    @Test
    fun `retention cleanup cascades deleted conversation messages`() = runBlocking {
        val nowMillis = CONVERSATION_RETENTION_MILLIS * 10
        val cutoff = nowMillis - CONVERSATION_RETENTION_MILLIS
        val expired = completedConversationAt(
            title = "Expired with messages",
            updatedAt = cutoff - 1,
        )
        assertEquals(2, messageCount(expired))

        repository.cleanupExpiredConversations(nowMillis)

        assertEquals(0, messageCount(expired))
    }

    @Test
    fun `retention cleanup is idempotent`() = runBlocking {
        val nowMillis = CONVERSATION_RETENTION_MILLIS * 10
        val cutoff = nowMillis - CONVERSATION_RETENTION_MILLIS
        completedConversationAt(
            title = "Expired once",
            updatedAt = cutoff - 1,
        )

        assertEquals(1, repository.cleanupExpiredConversations(nowMillis))
        assertEquals(0, repository.cleanupExpiredConversations(nowMillis))
        assertTrue(repository.observeConversations("").first().isEmpty())
    }

    @Test
    fun `expired conversations disappear from local search`() = runBlocking {
        val nowMillis = CONVERSATION_RETENTION_MILLIS * 10
        val cutoff = nowMillis - CONVERSATION_RETENTION_MILLIS
        completedConversationAt(
            title = "fuga retention",
            updatedAt = cutoff - 1,
        )
        completedConversationAt(
            title = "recent other case",
            updatedAt = cutoff + 1,
        )

        repository.cleanupExpiredConversations(nowMillis)

        assertTrue(
            repository.observeConversations("fuga retention").first().isEmpty(),
        )
    }

    @Test
    fun `manual delete removes only selected conversation and cascades messages`() = runBlocking {
        val selected = completedConversation(
            firstUser = "Delete this case",
            assistant = "Selected answer",
            startAt = 100L,
        )
        val kept = completedConversation(
            firstUser = "Keep this case",
            assistant = "Kept answer",
            startAt = 300L,
        )
        assertEquals(2, messageCount(selected))

        assertTrue(repository.deleteConversation(selected))

        assertNull(repository.load(selected))
        assertEquals(0, messageCount(selected))
        assertEquals(
            listOf(kept),
            repository.observeConversations("").first().map { it.id },
        )
    }

    @Test
    fun `deleted conversation cannot be resurrected by stale completion`() = runBlocking {
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "Case to delete",
            createdAt = 100L,
        )

        assertTrue(repository.deleteConversation(started.conversationId))
        val staleCompletion = runCatching {
            repository.completeAssistantTurn(
                conversationId = started.conversationId,
                text = "Late answer",
                finalResponseId = "resp_late",
                createdAt = 200L,
            )
        }

        assertTrue(staleCompletion.isFailure)
        assertNull(repository.load(started.conversationId))
        assertTrue(repository.observeConversations("").first().isEmpty())
    }

    @Test
    fun `cleanup and delete are local database operations`() = runBlocking {
        val nowMillis = CONVERSATION_RETENTION_MILLIS * 10
        val cutoff = nowMillis - CONVERSATION_RETENTION_MILLIS
        completedConversationAt(
            title = "Expired local case",
            updatedAt = cutoff - 1,
        )
        val manual = completedConversationAt(
            title = "Manual local case",
            updatedAt = cutoff + 1,
        )

        repository.cleanupExpiredConversations(nowMillis)
        repository.deleteConversation(manual)

        assertTrue(repository.observeConversations("").first().isEmpty())
    }

    @Test
    fun `interrupted turn leaves no orphan verified product snapshots`() = runBlocking {
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "Interrupted",
            createdAt = 100L,
        )

        repository.recoverInterruptedTurn(started.conversationId)

        assertEquals(0, messageProductCount(started.conversationId))
    }

    @Test
    fun `multi turn cards remain attached to their own assistant messages`() = runBlocking {
        val started = repository.beginUserTurn(null, "First", 100L)
        val firstCard = snapshot(
            obik = "1234567",
            name = "Historical first",
            stock = 5,
            price = BigDecimal("20.00"),
            url = "https://www.obi.pl/p/1234567/first",
            verifiedAt = 150L,
        )
        repository.completeAssistantTurn(
            started.conversationId,
            "First answer",
            "resp_first",
            products = listOf(firstCard),
            createdAt = 200L,
        )
        repository.beginUserTurn(
            started.conversationId,
            "A coś tańszego?",
            300L,
        )
        val secondCard = snapshot(
            obik = "7654321",
            name = "New independent",
            stock = 2,
            price = BigDecimal("10.00"),
            url = "https://www.obi.pl/p/7654321/second",
            verifiedAt = 350L,
        )
        repository.completeAssistantTurn(
            started.conversationId,
            "Second answer",
            "resp_second",
            products = listOf(secondCard),
            createdAt = 400L,
        )

        val assistants = requireNotNull(repository.load(started.conversationId))
            .messages.filter { it.role == MESSAGE_ROLE_ASSISTANT }

        assertEquals(listOf(firstCard), assistants[0].products)
        assertEquals(listOf(secondCard), assistants[1].products)
    }

    @Test
    fun `new conversation cannot inherit old verified cards`() = runBlocking {
        val first = completedConversationWithCard(
            title = "First case",
            updatedAt = 200L,
        )
        val second = repository.beginUserTurn(null, "Second case", 300L)
        repository.completeAssistantTurn(
            second.conversationId,
            "No cards",
            "resp_second",
            createdAt = 400L,
        )

        assertEquals(1, requireNotNull(repository.load(first)).messages.last().products.size)
        assertTrue(
            requireNotNull(repository.load(second.conversationId))
                .messages.last().products.isEmpty(),
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
    fun `completed follow up replaces previous conversation response id`() = runBlocking {
        val started = repository.beginUserTurn(null, "Pierwszy turn", 100L)
        repository.completeAssistantTurn(
            started.conversationId,
            "Pierwsza odpowiedź",
            "resp_first",
            200L,
        )

        val followUp = repository.beginUserTurn(
            started.conversationId,
            "Drugi turn",
            300L,
        )
        assertEquals("resp_first", followUp.previousResponseId)

        repository.completeAssistantTurn(
            started.conversationId,
            "Druga odpowiedź",
            "resp_second",
            400L,
        )

        assertEquals(
            "resp_second",
            repository.load(started.conversationId)?.lastResponseId,
        )
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

    @Test
    fun `new conversation defaults to 075 and selected store persists`() = runBlocking {
        val defaultTurn = repository.beginUserTurn(
            conversationId = null,
            text = "Default",
            createdAt = 100L,
        )
        assertEquals("075", defaultTurn.storeNumber)
        assertEquals(
            "075",
            requireNotNull(repository.load(defaultTurn.conversationId))
                .storeNumber,
        )

        val alternateTurn = repository.beginUserTurn(
            conversationId = null,
            text = "Alternate",
            createdAt = 200L,
            storeNumber = "074",
        )
        assertEquals(
            "074",
            requireNotNull(repository.load(alternateTurn.conversationId))
                .storeNumber,
        )

        database.close()
        openDatabase()
        assertEquals(
            "074",
            requireNotNull(repository.load(alternateTurn.conversationId))
                .storeNumber,
        )
    }

    @Test
    fun `changing selected store does not rewrite historical product store`() = runBlocking {
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "Historical",
            createdAt = 100L,
            storeNumber = "074",
        )
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Answer",
            finalResponseId = "resp_store",
            createdAt = 200L,
            products = listOf(
                snapshot(
                    obik = "3496072",
                    name = "Product",
                    stock = 13,
                    price = BigDecimal("12.99"),
                    url = "https://example.invalid/p/3496072",
                    verifiedAt = 150L,
                    storeNumber = "074",
                ),
            ),
        )

        assertTrue(
            repository.updateStoreNumber(
                started.conversationId,
                "075",
            ),
        )
        val restored = requireNotNull(
            repository.load(started.conversationId),
        )
        assertEquals("075", restored.storeNumber)
        assertEquals(
            "074",
            restored.messages.last().products.single().storeNumber,
        )
    }

    @Test
    fun `same OBIK in two stores persists as distinct snapshots`() = runBlocking {
        val started = repository.beginUserTurn(
            null,
            "Compare",
            100L,
        )
        val first = snapshot(
            obik = "3496072",
            name = "Product",
            stock = 13,
            price = BigDecimal("12.99"),
            url = "https://example.invalid/p/3496072",
            verifiedAt = 150L,
            storeNumber = "074",
        )
        val second = first.copy(
            stock = 25,
            verifiedAt = 160L,
            storeNumber = "075",
        )

        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Comparison",
            finalResponseId = "resp_compare",
            createdAt = 200L,
            products = listOf(first, second),
        )

        val products = requireNotNull(
            repository.load(started.conversationId),
        ).messages.last().products
        assertEquals(2, products.size)
        assertEquals(
            listOf("074", "075"),
            products.map { it.storeNumber },
        )
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

    private suspend fun completedConversationAt(
        title: String,
        updatedAt: Long,
    ): Long {
        val started = repository.beginUserTurn(
            conversationId = null,
            text = title,
            createdAt = updatedAt - 1,
        )
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Synthetic answer",
            finalResponseId = "resp_${started.conversationId}",
            createdAt = updatedAt,
        )
        return started.conversationId
    }

    private suspend fun completedConversationWithCard(
        title: String,
        updatedAt: Long,
    ): Long {
        val started = repository.beginUserTurn(
            conversationId = null,
            text = title,
            createdAt = updatedAt - 100L,
        )
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Answer",
            finalResponseId = "resp_card",
            products = listOf(
                snapshot(
                    obik = "1234567",
                    name = "Verified",
                    stock = 1,
                    price = BigDecimal("9.99"),
                    url = "https://www.obi.pl/p/1234567/trusted",
                    verifiedAt = updatedAt - 10L,
                ),
            ),
            createdAt = updatedAt,
        )
        return started.conversationId
    }

    private fun snapshot(
        obik: String,
        name: String,
        stock: Int?,
        price: BigDecimal?,
        url: String,
        verifiedAt: Long,
        storeNumber: String = "075",
    ) = VerifiedProductSnapshot(
        obik = obik,
        name = name,
        stock = stock,
        grossPrice = price,
        productUrl = url,
        verifiedAt = verifiedAt,
        storeNumber = storeNumber,
    )

    private fun allMessageSourceCount(): Int {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM message_sources",
        )
        return cursor.use {
            check(it.moveToFirst())
            it.getInt(0)
        }
    }

    private fun messageSourceCount(
        conversationId: Long,
    ): Int {
        val cursor = database.openHelper.readableDatabase.query(
            """
            SELECT COUNT(*) FROM message_sources
            WHERE messageId IN (
                SELECT id FROM messages WHERE conversationId = ?
            )
            """.trimIndent(),
            arrayOf(conversationId),
        )
        return cursor.use {
            check(it.moveToFirst())
            it.getInt(0)
        }
    }

    private fun messageProductCount(
        conversationId: Long,
    ): Int {
        val cursor = database.openHelper.readableDatabase.query(
            """
            SELECT COUNT(*) FROM message_products
            WHERE messageId IN (
                SELECT id FROM messages WHERE conversationId = ?
            )
            """.trimIndent(),
            arrayOf(conversationId),
        )
        return cursor.use {
            check(it.moveToFirst())
            it.getInt(0)
        }
    }

    private fun messageCount(
        conversationId: Long,
    ): Int {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM messages WHERE conversationId = ?",
            arrayOf(conversationId),
        )
        return cursor.use {
            check(it.moveToFirst())
            it.getInt(0)
        }
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
