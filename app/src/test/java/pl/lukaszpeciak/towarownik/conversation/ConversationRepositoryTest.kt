package pl.lukaszpeciak.towarownik.conversation

import android.content.Context
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.attachment.ATTACHMENT_LOCAL_STORAGE_MAX_BYTES
import pl.lukaszpeciak.towarownik.attachment.AttachmentRenderKind
import pl.lukaszpeciak.towarownik.attachment.AttachmentStorage
import pl.lukaszpeciak.towarownik.attachment.AttachmentType
import pl.lukaszpeciak.towarownik.formatAttachmentByteSize
import pl.lukaszpeciak.towarownik.attachment.PendingAttachmentOwnership
import pl.lukaszpeciak.towarownik.attachment.MultiPendingAttachmentOwnership
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope
import pl.lukaszpeciak.towarownik.product.provider.WorkingProfile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: ConversationDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var attachmentStorage: AttachmentStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        context.filesDir.resolve("advisor_attachments").deleteRecursively()
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DB_NAME)
        context.filesDir.resolve("advisor_attachments").deleteRecursively()
    }

    @Test
    fun mixedTextPdfImagePersistsInOrderAfterRestartAndBadTextFailsSoft() = runBlocking {
        val image = attachmentStorage.importValidated(
            AttachmentType.IMAGE, "photo.jpg", "image/jpeg", 3, width = 1, height = 1,
            source = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        val pdf = attachmentStorage.importValidated(
            AttachmentType.PDF, "manual.pdf", "application/pdf", 5,
            source = { ByteArrayInputStream("%PDF-".toByteArray()) },
        )
        val textBytes = "zażółć\n".toByteArray(Charsets.UTF_8)
        val text = attachmentStorage.importValidated(
            AttachmentType.TEXT, "quote.csv", "text/csv", textBytes.size.toLong(),
            source = { ByteArrayInputStream(textBytes) },
        )
        val items = listOf(image, pdf, text)
        val started = repository.beginUserTurn(null, "Inspect", 100, attachments = items)
        database.close()
        openDatabase()
        val loaded = repository.load(started.conversationId)!!.messages.single().attachments
        assertEquals(items.map { it.localId }, loaded.map { it.localId })
        assertEquals(
            listOf(AttachmentRenderKind.IMAGE, AttachmentRenderKind.PDF, AttachmentRenderKind.TEXT),
            loaded.map { attachmentStorage.renderKind(it) },
        )
        // Corrupt only the TEXT private bytes; other parts must remain visible and ordered.
        val textFile = context.filesDir.resolve("advisor_attachments/undefined")
        textFile.writeBytes(ByteArray(textBytes.size) { 0 })
        val afterCorrupt = repository.load(started.conversationId)!!.messages.single().attachments
        assertEquals(3, afterCorrupt.size)
        assertEquals(
            listOf(AttachmentRenderKind.IMAGE, AttachmentRenderKind.PDF, AttachmentRenderKind.UNAVAILABLE),
            afterCorrupt.map { attachmentStorage.renderKind(it) },
        )
    }

    @Test
    fun mixedTextFailedTurnRestoresAllOrderedFilesAndDraft() = runBlocking {
        val image = attachmentStorage.importValidated(
            AttachmentType.IMAGE, "label.jpg", "image/jpeg", 3, width = 1, height = 1,
            source = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        val pdf = attachmentStorage.importValidated(
            AttachmentType.PDF, "manual.pdf", "application/pdf", 5,
            source = { ByteArrayInputStream("%PDF-".toByteArray()) },
        )
        val text = attachmentStorage.importValidated(
            AttachmentType.TEXT, "notes.txt", "text/plain", 4,
            source = { ByteArrayInputStream("info".toByteArray()) },
        )
        val parts = listOf(image, pdf, text)
        val started = repository.beginUserTurn(null, "Restore draft", 150, attachments = parts)
        val owner = MultiPendingAttachmentOwnership(
            attachmentStorage,
            context.getSharedPreferences("mixed-text-recovery", Context.MODE_PRIVATE),
        )
        val result = repository.recoverFailedAdvisorTurn(
            started.conversationId,
            claimPendingAttachment = { owner.claimRecovered(listOf(it)) },
            claimPendingAttachments = owner::claimRecovered,
        )
        assertEquals(parts, result.pendingAttachments)
        assertEquals("Restore draft", result.conversation?.draft)
        assertEquals(0, messageAttachmentCount(started.conversationId))
        assertTrue(parts.all { attachmentStorage.exists(it.localId) })
        assertEquals(AttachmentRenderKind.TEXT, attachmentStorage.renderKind(text))
    }

    @Test
    fun repositoryRejectsTextSumAboveOneMiBBeforeRoomInsert() = runBlocking {
        fun text(name: String, bytes: Int) = attachmentStorage.importValidated(
            AttachmentType.TEXT, name, "text/plain", bytes.toLong(),
            source = { ByteArrayInputStream(ByteArray(bytes) { 65 }) },
        )
        val first = text("first.txt", 512 * 1024)
        val excess = text("second.txt", 512 * 1024 + 1)
        assertTrue(runCatching {
            repository.beginUserTurn(null, "invalid", attachments = listOf(first, excess))
        }.isFailure)
        assertTrue(repository.list().first().isEmpty())
    }

    @Test
    fun oneTwoAndThreeAttachmentsRoundTripWithOrderAfterDatabaseRestart() = runBlocking {
        fun make(name: String) = attachmentStorage.importValidated(
            AttachmentType.PDF, name, "application/pdf", 5, createdAt = 1,
            source = { ByteArrayInputStream("%PDF-".toByteArray()) },
        )
        val groups = listOf(
            listOf(make("one-first.pdf")),
            listOf(make("two-first.pdf"), make("two-second.pdf")),
            listOf(make("three-first.pdf"), make("three-second.pdf"), make("three-third.pdf")),
        )
        val ids = groups.mapIndexed { index, group ->
            repository.beginUserTurn(
                conversationId = null, text = "turn$index",
                createdAt = (100 + index).toLong(),
                attachments = group,
            ).conversationId
        }
        database.close()
        openDatabase()
        ids.forEachIndexed { index, id ->
            assertEquals(
                groups[index].map { it.localId },
                repository.load(id)!!.messages.single().attachments.map { it.localId },
            )
        }
        attachmentStorage.delete(groups[2][1].localId)
        val restored = repository.load(ids[2])!!.messages.single().attachments
        assertEquals(3, restored.size)
        assertEquals(
            listOf(AttachmentRenderKind.PDF, AttachmentRenderKind.UNAVAILABLE, AttachmentRenderKind.PDF),
            restored.map { attachmentStorage.renderKind(it) },
        )
    }

    @Test
    fun multiAttachmentFailedTurnRestoresEveryFileAndDraft() = runBlocking {
        val files = (1..3).map { i ->
            attachmentStorage.importValidated(
                AttachmentType.PDF, "retry$i.pdf", "application/pdf", 5,
                source = { ByteArrayInputStream("%PDF-".toByteArray()) },
            )
        }
        val started = repository.beginUserTurn(
            null, "Retry all", 200, attachments = files,
        )
        val owner = MultiPendingAttachmentOwnership(
            attachmentStorage,
            context.getSharedPreferences("multi-failed-turn-test", Context.MODE_PRIVATE),
        )
        val recovery = repository.recoverFailedAdvisorTurn(
            started.conversationId,
            claimPendingAttachment = { owner.claimRecovered(listOf(it)) },
            claimPendingAttachments = owner::claimRecovered,
        )
        assertEquals("Retry all", recovery.conversation?.draft)
        assertEquals(files, recovery.pendingAttachments)
        assertEquals(0, messageAttachmentCount(started.conversationId))
        assertTrue(files.all { attachmentStorage.exists(it.localId) })
    }

    @Test
    fun deletingAndExpiringMultiAttachmentTurnsCleansEveryFile() = runBlocking {
        fun make(name: String) = attachmentStorage.importValidated(
            AttachmentType.PDF, name, "application/pdf", 5,
            source = { ByteArrayInputStream("%PDF-".toByteArray()) },
        )
        val deleted = listOf(make("delete1"), make("delete2"), make("delete3"))
        val one = repository.beginUserTurn(null, "delete", 100, attachments = deleted)
        assertTrue(repository.deleteConversation(one.conversationId))
        assertTrue(deleted.none { attachmentStorage.exists(it.localId) })

        val expired = listOf(make("expire1"), make("expire2"), make("expire3"))
        repository.beginUserTurn(null, "expire", 100, attachments = expired)
        repository.cleanupExpiredConversations(CONVERSATION_RETENTION_MILLIS + 101)
        assertTrue(expired.none { attachmentStorage.exists(it.localId) })
    }

    @Test
    fun tooManyOrTooLargeAttachmentGroupsRejectBeforePersistence() = runBlocking {
        val sample = attachmentStorage.importValidated(
            AttachmentType.PDF, "test.pdf", "application/pdf", 5,
            source = { ByteArrayInputStream("%PDF-".toByteArray()) },
        )
        val many = (0..3).map { sample.copy(localId = it.toString().repeat(32)) }
        assertTrue(runCatching {
            repository.beginUserTurn(null, "four", attachments = many)
        }.isFailure)
        val huge = (0..1).map {
            sample.copy(localId = it.toString().repeat(32), byteSize = 16L * 1024 * 1024)
        }
        assertTrue(runCatching {
            repository.beginUserTurn(null, "oversize", attachments = huge)
        }.isFailure)
    }

    @Test
    fun `image and PDF attachment metadata round trip while historical message remains empty`() = runBlocking {
        val image = attachmentStorage.importValidated(
            type = AttachmentType.IMAGE,
            displayName = "label.jpg",
            mimeType = "image/jpeg",
            byteSize = 3,
            width = 40,
            height = 20,
            createdAt = 90,
            source = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        val first = repository.beginUserTurn(null, "image", 100, attachment = image)
        val pdf = attachmentStorage.importValidated(
            type = AttachmentType.PDF,
            displayName = "manual.pdf",
            mimeType = "application/pdf",
            byteSize = 2,
            createdAt = 110,
            source = { ByteArrayInputStream(byteArrayOf(4, 5)) },
        )
        val second = repository.beginUserTurn(null, "pdf", 120, attachment = pdf)
        val historical = repository.beginUserTurn(null, "text only", 130)

        assertEquals(image, repository.load(first.conversationId)?.messages?.single()?.attachment)
        assertEquals(pdf, repository.load(second.conversationId)?.messages?.single()?.attachment)
        assertNull(repository.load(historical.conversationId)?.messages?.single()?.attachment)
    }

    @Test
    fun `persisted image and PDF remain renderable after database recreation`() = runBlocking {
        val image = attachmentStorage.importValidated(
            type = AttachmentType.IMAGE,
            displayName = "history-image.jpg",
            mimeType = "image/jpeg",
            byteSize = 3,
            width = 80,
            height = 60,
            createdAt = 90,
            source = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        val imageTurn = repository.beginUserTurn(
            null,
            "image",
            100,
            attachment = image,
        )
        val pdfBytes = "%PDF-1.7".toByteArray()
        val pdf = attachmentStorage.importValidated(
            type = AttachmentType.PDF,
            displayName = "history-manual.pdf",
            mimeType = "application/pdf",
            byteSize = pdfBytes.size.toLong(),
            createdAt = 110,
            source = { ByteArrayInputStream(pdfBytes) },
        )
        val pdfTurn = repository.beginUserTurn(
            null,
            "pdf",
            120,
            attachment = pdf,
        )

        database.close()
        openDatabase()

        val restoredImage = requireNotNull(
            repository.load(imageTurn.conversationId),
        ).messages.single().attachment
        val restoredPdf = requireNotNull(
            repository.load(pdfTurn.conversationId),
        ).messages.single().attachment

        requireNotNull(restoredImage)
        requireNotNull(restoredPdf)
        assertEquals(
            AttachmentRenderKind.IMAGE,
            attachmentStorage.renderKind(restoredImage),
        )
        assertEquals(
            AttachmentRenderKind.PDF,
            attachmentStorage.renderKind(restoredPdf),
        )
        assertEquals("history-manual.pdf", restoredPdf.displayName)
        assertEquals(pdfBytes.size.toLong(), restoredPdf.byteSize)
        assertEquals("8 B", formatAttachmentByteSize(restoredPdf.byteSize))
    }

    @Test
    fun `attachment only turn uses display name as title and reloads user metadata`() = runBlocking {
        val attachment = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "Installation manual.pdf",
            "application/pdf",
            3,
            createdAt = 95,
            source = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )

        val started = repository.beginUserTurn(
            conversationId = null,
            text = "   ",
            createdAt = 100,
            attachment = attachment,
        )
        val current = requireNotNull(repository.load(started.conversationId))

        assertEquals("Installation manual.pdf", current.title)
        assertEquals("", current.messages.single().text)
        assertEquals("USER", current.messages.single().role)
        assertEquals(attachment, current.messages.single().attachment)
        assertTrue(attachmentStorage.exists(attachment.localId))
    }

    @Test
    fun `assistant trace persists reloads and malformed trace is dropped`() = runBlocking {
        val trace = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        val validTurn = repository.beginUserTurn(
            conversationId = null,
            text = "valid trace",
            createdAt = 100,
        )
        repository.completeAssistantTurn(
            conversationId = validTurn.conversationId,
            text = "answer",
            finalResponseId = "resp_final",
            createdAt = 110,
            advisorTraceId = trace,
        )

        val invalidTurn = repository.beginUserTurn(
            conversationId = null,
            text = "invalid trace",
            createdAt = 200,
        )
        repository.completeAssistantTurn(
            conversationId = invalidTurn.conversationId,
            text = "answer",
            finalResponseId = "resp_final_2",
            createdAt = 210,
            advisorTraceId = "not-a-trace",
        )

        assertEquals(
            trace,
            repository.load(validTurn.conversationId)
                ?.messages
                ?.last()
                ?.advisorTraceId,
        )
        assertNull(
            repository.load(invalidTurn.conversationId)
                ?.messages
                ?.last()
                ?.advisorTraceId,
        )

        database.close()
        openDatabase()

        assertEquals(
            trace,
            repository.load(validTurn.conversationId)
                ?.messages
                ?.last()
                ?.advisorTraceId,
        )
    }

    @Test
    fun `text only turn remains unchanged`() = runBlocking {
        val started = repository.beginUserTurn(null, "  Plain question  ", 101)
        val current = requireNotNull(repository.load(started.conversationId))

        assertEquals("Plain question", current.title)
        assertEquals("Plain question", current.messages.single().text)
        assertNull(current.messages.single().attachment)
    }

    @Test
    fun multiAttachmentRowsRequireUniquePositionAndCascade() = runBlocking {
        val attachment = attachmentStorage.importValidated(
            AttachmentType.PDF, "one.pdf", "application/pdf", 1, createdAt = 1,
            source = { ByteArrayInputStream(byteArrayOf(1)) },
        )
        val started = repository.beginUserTurn(null, "one", 2, attachment = attachment)
        val db = database.openHelper.writableDatabase
        val messageId = repository.load(started.conversationId)!!.messages.single().id
        db.execSQL(
            "INSERT INTO message_attachments (messageId,position,type,displayName,mimeType,localId,byteSize,width,height,createdAt) " +
                "VALUES ($messageId,1,'PDF','two.pdf','application/pdf','00000000000000000000000000000000',1,NULL,NULL,1)",
        )
        db.execSQL(
            "INSERT INTO message_attachments (messageId,position,type,displayName,mimeType,localId,byteSize,width,height,createdAt) " +
                "VALUES ($messageId,2,'PDF','three.pdf','application/pdf','11111111111111111111111111111111',1,NULL,NULL,1)",
        )
        val duplicatePosition = runCatching {
            db.execSQL(
                "INSERT INTO message_attachments (messageId,position,type,displayName,mimeType,localId,byteSize,width,height,createdAt) " +
                    "VALUES ($messageId,2,'PDF','four.pdf','application/pdf','22222222222222222222222222222222',1,NULL,NULL,1)",
            )
        }.isFailure
        assertTrue(duplicatePosition)
        repository.deleteConversation(started.conversationId)
        assertEquals("0", db.query("SELECT COUNT(*) FROM message_attachments").use { it.moveToFirst(); it.getString(0) })
    }

    @Test
    fun `explicit and expiry deletion remove private files`() = runBlocking {
        fun attachment(name: String, time: Long) = attachmentStorage.importValidated(
            AttachmentType.PDF, name, "application/pdf", 1, createdAt = time,
            source = { ByteArrayInputStream(byteArrayOf(1)) },
        )
        val manualAttachment = attachment("manual.pdf", 1)
        val manual = repository.beginUserTurn(null, "manual", 1, attachment = manualAttachment)
        assertTrue(repository.deleteConversation(manual.conversationId))
        assertFalse(attachmentStorage.exists(manualAttachment.localId))

        val expiredAttachment = attachment("expired.pdf", 2)
        repository.beginUserTurn(null, "expired", 2, attachment = expiredAttachment)
        repository.cleanupExpiredConversations(CONVERSATION_RETENTION_MILLIS + 3)
        assertFalse(attachmentStorage.exists(expiredAttachment.localId))
    }

    @Test
    fun `missing file and malformed unsupported metadata fail soft while loading`() = runBlocking {
        val attachment = attachmentStorage.importValidated(
            AttachmentType.PDF, "gone.pdf", "application/pdf", 1, createdAt = 1,
            source = { ByteArrayInputStream(byteArrayOf(1)) },
        )
        val missing = repository.beginUserTurn(null, "missing", 2, attachment = attachment)
        attachmentStorage.delete(attachment.localId)
        assertNull(repository.load(missing.conversationId)?.messages?.single()?.attachment)

        val malformed = repository.beginUserTurn(null, "malformed", 3)
        val messageId = repository.load(malformed.conversationId)!!.messages.single().id
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO message_attachments (messageId,position,type,displayName,mimeType,localId,byteSize,width,height,createdAt) VALUES ($messageId,0,'ARCHIVE','bad.zip','application/zip','00000000000000000000000000000000',1,NULL,NULL,1)",
        )
        assertNull(repository.load(malformed.conversationId)?.messages?.single()?.attachment)
    }

    @Test
    fun `persisted attachment ownership is reported by local id`() = runBlocking {
        val persisted = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "persisted.pdf",
            "application/pdf",
            1,
            createdAt = 8,
            source = { ByteArrayInputStream(byteArrayOf(1)) },
        )
        val unsent = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "unsent.pdf",
            "application/pdf",
            1,
            createdAt = 9,
            source = { ByteArrayInputStream(byteArrayOf(2)) },
        )

        repository.beginUserTurn(
            conversationId = null,
            text = "Persist attachment",
            createdAt = 10,
            attachment = persisted,
        )

        assertTrue(repository.isAttachmentPersisted(persisted.localId))
        assertFalse(repository.isAttachmentPersisted(unsent.localId))
    }

    @Test
    fun `successful user turn transfers pending ownership to Room`() = runBlocking {
        val attachment = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "owned.pdf",
            "application/pdf",
            2,
            createdAt = 11,
            source = { ByteArrayInputStream(byteArrayOf(1, 2)) },
        )
        val ownership = PendingAttachmentOwnership(
            attachmentStorage,
            context.getSharedPreferences("successful-handoff", Context.MODE_PRIVATE),
        )
        assertTrue(ownership.markPending(attachment))

        repository.beginUserTurn(null, "", 12, attachment = attachment)
        assertTrue(repository.isAttachmentPersisted(attachment.localId))
        ownership.handoffToPersisted(attachment.localId)

        assertNull(ownership.ownedLocalId())
        assertTrue(attachmentStorage.exists(attachment.localId))
    }

    @Test
    fun `failed pending marker release after Room persistence keeps durable turn`() = runBlocking {
        val attachment = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "persisted-despite-marker.pdf",
            "application/pdf",
            2,
            createdAt = 15,
            source = { ByteArrayInputStream(byteArrayOf(5, 6)) },
        )
        val preferences = context.getSharedPreferences(
            "persisted-handoff-failure",
            Context.MODE_PRIVATE,
        )
        var commitCount = 0
        val ownership = PendingAttachmentOwnership(
            storage = attachmentStorage,
            preferences = preferences,
            commitEditor = { editor ->
                commitCount += 1
                if (commitCount == 1) {
                    editor.commit()
                } else {
                    false
                }
            },
        )
        assertTrue(ownership.markPending(attachment))

        val turn = repository.beginUserTurn(
            conversationId = null,
            text = "",
            createdAt = 16,
            attachment = attachment,
        )
        val released = ownership.handoffToPersisted(attachment.localId)

        assertFalse(released)
        assertTrue(repository.isAttachmentPersisted(attachment.localId))
        assertTrue(attachmentStorage.exists(attachment.localId))
        assertEquals(attachment.localId, ownership.ownedLocalId())
        assertEquals(
            attachment,
            requireNotNull(repository.load(turn.conversationId))
                .messages
                .single()
                .attachment,
        )
    }

    @Test
    fun `failed begin user turn retains pending ownership`() = runBlocking {
        val attachment = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "still-pending.pdf",
            "application/pdf",
            2,
            createdAt = 13,
            source = { ByteArrayInputStream(byteArrayOf(3, 4)) },
        )
        val ownership = PendingAttachmentOwnership(
            attachmentStorage,
            context.getSharedPreferences("failed-handoff", Context.MODE_PRIVATE),
        )
        assertTrue(ownership.markPending(attachment))

        val result = runCatching {
            repository.beginUserTurn(
                conversationId = Long.MAX_VALUE,
                text = "",
                createdAt = 14,
                attachment = attachment,
            )
        }

        assertTrue(result.isFailure)
        assertEquals(attachment.localId, ownership.ownedLocalId())
        assertFalse(repository.isAttachmentPersisted(attachment.localId))
        assertTrue(attachmentStorage.exists(attachment.localId))
    }

    @Test
    fun `interrupted turn recovery removes attachment metadata and only its private file`() = runBlocking {
        val interruptedAttachment = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "interrupted.pdf",
            "application/pdf",
            3,
            createdAt = 10,
            source = {
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            },
        )
        val unrelatedAttachment = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "unrelated.pdf",
            "application/pdf",
            1,
            createdAt = 11,
            source = {
                ByteArrayInputStream(byteArrayOf(9))
            },
        )
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "Recover this draft",
            createdAt = 20,
            attachment = interruptedAttachment,
        )

        assertEquals(1, messageAttachmentCount(started.conversationId))
        assertTrue(attachmentStorage.exists(interruptedAttachment.localId))
        assertTrue(attachmentStorage.exists(unrelatedAttachment.localId))

        val recovered = repository.recoverInterruptedTurn(
            started.conversationId,
        )

        requireNotNull(recovered)
        assertEquals("Recover this draft", recovered.draft)
        assertEquals(0, messageAttachmentCount(started.conversationId))
        assertFalse(attachmentStorage.exists(interruptedAttachment.localId))
        assertTrue(attachmentStorage.exists(unrelatedAttachment.localId))
    }

    @Test
    fun `failed text and attachment turn restores draft and pending attachment`() = runBlocking {
        val attachment = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "retry.pdf",
            "application/pdf",
            3,
            createdAt = 21,
            source = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "Spróbuj ponownie",
            createdAt = 22,
            attachment = attachment,
        )
        val ownership = PendingAttachmentOwnership(
            attachmentStorage,
            context.getSharedPreferences(
                "failed-turn-text-attachment",
                Context.MODE_PRIVATE,
            ),
        )

        val recovery = repository.recoverFailedAdvisorTurn(
            conversationId = started.conversationId,
            claimPendingAttachment = ownership::markPending,
        )

        assertEquals("Spróbuj ponownie", recovery.conversation?.draft)
        assertEquals(attachment, recovery.pendingAttachment)
        assertEquals(0, messageAttachmentCount(started.conversationId))
        assertEquals(attachment.localId, ownership.ownedLocalId())
    }

    @Test
    fun `failed attachment only turn restores pending attachment with blank draft`() = runBlocking {
        val attachment = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "attachment-only.pdf",
            "application/pdf",
            2,
            createdAt = 23,
            source = { ByteArrayInputStream(byteArrayOf(4, 5)) },
        )
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "",
            createdAt = 24,
            attachment = attachment,
        )
        val ownership = PendingAttachmentOwnership(
            attachmentStorage,
            context.getSharedPreferences(
                "failed-turn-attachment-only",
                Context.MODE_PRIVATE,
            ),
        )

        val recovery = repository.recoverFailedAdvisorTurn(
            conversationId = started.conversationId,
            claimPendingAttachment = ownership::markPending,
        )

        assertEquals("", recovery.conversation?.draft)
        assertEquals(attachment, recovery.pendingAttachment)
        assertEquals(0, messageAttachmentCount(started.conversationId))
    }

    @Test
    fun `failed attachment turn preserves private file for retry`() = runBlocking {
        val bytes = byteArrayOf(7, 8, 9)
        val attachment = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "preserved.pdf",
            "application/pdf",
            bytes.size.toLong(),
            createdAt = 25,
            source = { ByteArrayInputStream(bytes) },
        )
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "retry",
            createdAt = 26,
            attachment = attachment,
        )
        val ownership = PendingAttachmentOwnership(
            attachmentStorage,
            context.getSharedPreferences(
                "failed-turn-file-preserved",
                Context.MODE_PRIVATE,
            ),
        )

        repository.recoverFailedAdvisorTurn(
            conversationId = started.conversationId,
            claimPendingAttachment = ownership::markPending,
        )

        assertTrue(attachmentStorage.exists(attachment.localId))
        assertEquals(
            bytes.toList(),
            attachmentStorage.open(attachment.localId)
                ?.use { it.readBytes().toList() },
        )
    }

    @Test
    fun `failed text only turn recovery remains unchanged`() = runBlocking {
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "Tekst do ponowienia",
            createdAt = 27,
        )
        var claimCalls = 0

        val recovery = repository.recoverFailedAdvisorTurn(
            conversationId = started.conversationId,
            claimPendingAttachment = {
                claimCalls += 1
                true
            },
        )

        assertEquals("Tekst do ponowienia", recovery.conversation?.draft)
        assertNull(recovery.pendingAttachment)
        assertEquals(0, claimCalls)
        assertEquals(0, messageAttachmentCount(started.conversationId))
    }

    @Test
    fun `attachment local storage accepts the v1 byte limit`() {
        val payload = ByteArray(
            ATTACHMENT_LOCAL_STORAGE_MAX_BYTES.toInt(),
        ) { 7 }

        val attachment = attachmentStorage.importValidated(
            AttachmentType.PDF,
            "boundary.pdf",
            "application/pdf",
            ATTACHMENT_LOCAL_STORAGE_MAX_BYTES,
            createdAt = 30,
            source = { ByteArrayInputStream(payload) },
        )

        assertEquals(
            ATTACHMENT_LOCAL_STORAGE_MAX_BYTES,
            attachment.byteSize,
        )
        assertTrue(attachmentStorage.exists(attachment.localId))
    }

    @Test
    fun `attachment local storage rejects oversized metadata before opening source`() {
        var sourceOpened = false

        val failure = runCatching {
            attachmentStorage.importValidated(
                AttachmentType.PDF,
                "too-large.pdf",
                "application/pdf",
                ATTACHMENT_LOCAL_STORAGE_MAX_BYTES + 1,
                createdAt = 31,
                source = {
                    sourceOpened = true
                    ByteArrayInputStream(byteArrayOf(1))
                },
            )
        }

        assertTrue(failure.isFailure)
        assertFalse(sourceOpened)
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
    fun `assistant product thumbnail survives database recreation`() = runBlocking {
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "Pokaż produkt",
            createdAt = 100L,
        )
        val imageUrl =
            "https://bilder.obi.pl/0834d60c-3719-4acf-8ad4-ce901b2bbe8c/pr08A/image.jpeg"
        val product = snapshot(
            obik = "3496072",
            name = "Dragon Klej uniwersalny Butapren 50 ml",
            stock = 25,
            price = BigDecimal("12.99"),
            url = "https://www.obi.pl/p/3496072/dragon-klej-uniwersalny-butapren-50-ml",
            imageUrl = imageUrl,
            verifiedAt = 150L,
        )

        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Verified answer",
            finalResponseId = "resp_image",
            products = listOf(product),
            createdAt = 200L,
        )

        database.close()
        openDatabase()

        val restored = requireNotNull(
            repository.load(started.conversationId),
        ).messages.last().products.single()

        assertEquals(imageUrl, restored.primaryImageUrl)
        assertEquals(product, restored)
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
    fun `advisor search actions persist reload and cascade with conversation deletion`() = runBlocking {
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "Pokaż czarne trytytki",
            createdAt = 100L,
            storeNumber = "074",
        )
        val actions = listOf(
            PersistedSearchAction(
                query = "czarne trytytki",
                storeNumber = "074",
                reportedTotalCount = 27,
            ),
            PersistedSearchAction(
                query = "opaski kablowe UV",
                storeNumber = "075",
                reportedTotalCount = 14,
            ),
        )
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Znalazłam kilka wariantów.",
            finalResponseId = "resp_search_actions",
            createdAt = 200L,
            searchActions = actions,
        )

        database.close()
        openDatabase()

        val restored = requireNotNull(
            repository.load(started.conversationId),
        )
        assertEquals(
            actions,
            restored.messages.last().searchActions,
        )
        assertEquals(
            2,
            messageSearchActionCount(started.conversationId),
        )

        assertTrue(
            repository.deleteConversation(started.conversationId),
        )
        assertEquals(0, allMessageSearchActionCount())
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
    fun `new conversation captures selected working profile and survives recreation`() = runBlocking {
        val kwant = WorkingProfile(
            providerId = KWANT_PROVIDER_ID,
            branchId = BranchId("205"),
        )
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "KWANT case",
            createdAt = 210L,
            workingProfile = kwant,
        )

        assertEquals(
            kwant,
            requireNotNull(repository.load(started.conversationId))
                .workingProfile,
        )

        database.close()
        openDatabase()

        assertEquals(
            kwant,
            requireNotNull(repository.load(started.conversationId))
                .workingProfile,
        )
    }

    @Test
    fun `existing conversation rejects a different global working profile`() = runBlocking {
        val obi = WorkingProfile(
            providerId = OBI_PROVIDER_ID,
            branchId = BranchId("075"),
        )
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "OBI case",
            createdAt = 220L,
            workingProfile = obi,
        )

        val error = runCatching {
            repository.beginUserTurn(
                conversationId = started.conversationId,
                text = "Must stay OBI",
                createdAt = 230L,
                workingProfile = WorkingProfile(
                    providerId = KWANT_PROVIDER_ID,
                    branchId = BranchId("205"),
                ),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(
            obi,
            requireNotNull(repository.load(started.conversationId))
                .workingProfile,
        )
    }

    @Test
    fun `conversation profile and historical product store stay stable`() = runBlocking {
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

        val restored = requireNotNull(
            repository.load(started.conversationId),
        )
        assertEquals("074", restored.storeNumber)
        assertEquals(
            "074",
            restored.messages.last().products.single().storeNumber,
        )
    }

    @Test
    fun `KWANT provider product identity stock and price persist across recreation`() = runBlocking {
        val profile = WorkingProfile(
            providerId = KWANT_PROVIDER_ID,
            branchId = BranchId("205"),
        )
        val started = repository.beginUserTurn(
            conversationId = null,
            text = "KWANT product",
            createdAt = 500L,
            workingProfile = profile,
        )
        repository.completeAssistantTurn(
            conversationId = started.conversationId,
            text = "Verified",
            finalResponseId = "resp_kwant",
            createdAt = 600L,
            products = listOf(
                VerifiedProductSnapshot(
                    obik = "580",
                    name = "Wyłącznik nadprądowy B16",
                    stock = 140,
                    grossPrice = BigDecimal("14.55"),
                    productUrl = "https://kwant.net.pl/produkt/test-580",
                    verifiedAt = 550L,
                    storeNumber = "205",
                    primaryImageUrl =
                        "https://kwant.net.pl/images/product-580.webp",
                    providerId = "kwant-pl",
                    productId = "580",
                    branchId = "205",
                    articleNumber = "MBN116E/HAG",
                    priceScope = ProviderPriceScope.ONLINE,
                ),
            ),
        )

        database.close()
        openDatabase()

        val restored = requireNotNull(
            repository.load(started.conversationId),
        ).messages.last().products.single()

        assertEquals("kwant-pl", restored.providerId)
        assertEquals("205", restored.branchId)
        assertEquals("580", restored.productId)
        assertEquals("MBN116E/HAG", restored.articleNumber)
        assertEquals(140, restored.stock)
        assertEquals(BigDecimal("14.55"), restored.grossPrice)
        assertEquals(
            "https://kwant.net.pl/produkt/test-580",
            restored.productUrl,
        )
        assertEquals(
            "https://kwant.net.pl/images/product-580.webp",
            restored.primaryImageUrl,
        )
        assertEquals(550L, restored.verifiedAt)
        assertEquals(ProviderPriceScope.ONLINE, restored.priceScope)
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
        imageUrl: String? = null,
        verifiedAt: Long,
        storeNumber: String = "075",
    ) = VerifiedProductSnapshot(
        obik = obik,
        name = name,
        stock = stock,
        grossPrice = price,
        productUrl = url,
        primaryImageUrl = imageUrl,
        verifiedAt = verifiedAt,
        storeNumber = storeNumber,
    )

    private fun allMessageSearchActionCount(): Int {
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM message_search_actions",
        )
        return cursor.use {
            check(it.moveToFirst())
            it.getInt(0)
        }
    }

    private fun messageSearchActionCount(
        conversationId: Long,
    ): Int {
        val cursor = database.openHelper.readableDatabase.query(
            """
            SELECT COUNT(*) FROM message_search_actions
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

    private fun messageAttachmentCount(
        conversationId: Long,
    ): Int {
        val cursor = database.openHelper.readableDatabase.query(
            """
            SELECT COUNT(*) FROM message_attachments
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
        attachmentStorage = AttachmentStorage(context)
        repository = ConversationRepository(
            dao = database.conversationDao(),
            now = { 10_000L },
            attachmentStorage = attachmentStorage,
        )
    }

    private companion object {
        const val DB_NAME = "conversation-repository-test.db"
    }
}
