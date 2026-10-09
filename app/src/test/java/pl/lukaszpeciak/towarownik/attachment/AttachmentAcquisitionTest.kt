package pl.lukaszpeciak.towarownik.attachment

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentAcquisitionTest {
    private lateinit var context: Context
    private lateinit var storage: AttachmentStorage
    private lateinit var importer: AttachmentImporter

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = AttachmentStorage(context)
        Robolectric.setupContentProvider(
            TestAttachmentProvider::class.java,
            TEST_AUTHORITY,
        )
        importer = AttachmentImporter(context.contentResolver, storage)
        context.filesDir.resolve("advisor_attachments").deleteRecursively()
        context.cacheDir.resolve("advisor_camera_capture").deleteRecursively()
        pendingPreferences().edit().clear().commit()
    }

    @Test
    fun multiComposerAddsReplacesAndRemovesWithoutDeletingSiblings() {
        val owner = MultiPendingAttachmentOwnership(storage, pendingPreferences())
        val first = storedPdf("first.pdf")
        assertTrue(owner.publishSelection(listOf(first), emptyList()))
        val second = storedPdf("second.pdf")
        assertTrue(owner.publishSelection(listOf(first, second), emptyList()))
        val third = storedPdf("third.pdf")
        assertTrue(owner.publishSelection(listOf(first, second, third), emptyList()))
        assertNull(appendOrReplaceAttachment(listOf(first, second, third),
            storedPdf("fourth.pdf")))
        val replacement = storedPdf("replacement.pdf")
        assertEquals(
            listOf(replacement, second, third),
            appendOrReplaceAttachment(listOf(first, second, third), replacement, first.localId),
        )
        assertTrue(owner.publishSelection(listOf(replacement, second, third), listOf(first)))
        assertFalse(storage.exists(first.localId))
        assertTrue(storage.exists(second.localId))
        assertTrue(storage.exists(third.localId))
        assertTrue(owner.publishSelection(listOf(replacement, third), listOf(second)))
        assertFalse(storage.exists(second.localId))
        assertTrue(storage.exists(replacement.localId))
        assertTrue(owner.clearPending(listOf(replacement, third)))
        assertFalse(storage.exists(replacement.localId))
        assertFalse(storage.exists(third.localId))
    }

    @Test
    fun multiComposerRejectsTwentyFourMiBOverflowBeforePublishing() {
        val first = storedPdf("first.pdf").copy(byteSize = 16L * 1024 * 1024)
        val second = storedPdf("second.pdf").copy(byteSize = 8L * 1024 * 1024)
        val third = storedPdf("third.pdf")
        assertEquals(listOf(first, second),
            appendOrReplaceAttachment(listOf(first), second))
        assertNull(appendOrReplaceAttachment(listOf(first, second), third))
        assertTrue(canSendAdvisorComposer(
            enabled = true, importInProgress = false, text = "", attachments = listOf(first, second),
        ))
        assertFalse(canSendAdvisorComposer(
            enabled = true, importInProgress = false, text = "",
            attachments = listOf(first, second, third),
        ))
        assertTrue(canSendAdvisorComposer(
            enabled = true, importInProgress = false, text = "text", attachments = emptyList(),
        ))
    }

    @Test
    fun multiOwnershipStartupPreservesRestoredPendingAndCleansStaleCandidate() = runBlocking {
        val owner = MultiPendingAttachmentOwnership(storage, pendingPreferences())
        val first = storedPdf("safe-first.pdf")
        val second = storedPdf("safe-second.pdf")
        assertTrue(owner.publishSelection(listOf(first, second), emptyList()))

        val bytes = "%PDF-stale".toByteArray()
        val stale = storage.importValidated(
            AttachmentType.PDF, "stale.pdf", "application/pdf", bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
            beforePublish = owner::stageImportedCandidate,
        )
        val guard = AttachmentImportGuard()
        val staleToken = guard.begin()
        guard.invalidate()
        assertFalse(guard.isCurrent(staleToken))
        val afterRestart = MultiPendingAttachmentOwnership(storage, pendingPreferences())
        val restored = afterRestart.reconcileAfterStartup(
            restored = listOf(first, second),
            isPersisted = { false },
        )
        assertEquals(listOf(first, second), restored)
        assertTrue(storage.exists(first.localId))
        assertTrue(storage.exists(second.localId))
        assertFalse(storage.exists(stale.localId))
    }

    @Test
    fun multiOwnershipDoesNotDeletePersistedFileDuringStartupReconciliation() = runBlocking {
        val owner = MultiPendingAttachmentOwnership(storage, pendingPreferences())
        val persisted = storedPdf("room-owns-me.pdf")
        assertTrue(owner.publishSelection(listOf(persisted), emptyList()))
        val startup = MultiPendingAttachmentOwnership(storage, pendingPreferences())
        val restored = startup.reconcileAfterStartup(
            restored = listOf(persisted),
            isPersisted = { id -> id == persisted.localId },
        )
        assertTrue(restored.isEmpty())
        assertTrue(storage.exists(persisted.localId))
    }

    @Test
    fun `replacement ownership is staged before durable publish and then switched`() {
        val previous = storedPdf("previous.pdf")
        val ownership = PendingAttachmentOwnership(
            storage,
            pendingPreferences(),
        )
        assertTrue(ownership.markPending(previous))

        val bytes = "%PDF-new".toByteArray()
        var stagedBeforePublish = false
        val next = storage.importValidated(
            type = AttachmentType.PDF,
            displayName = "next.pdf",
            mimeType = "application/pdf",
            byteSize = bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
            beforePublish = { attachment ->
                stagedBeforePublish = !storage.exists(attachment.localId) &&
                    ownership.stageImportedCandidate(attachment)
                stagedBeforePublish
            },
        )

        assertTrue(stagedBeforePublish)
        assertEquals(previous.localId, ownership.ownedLocalId())
        assertEquals(next.localId, ownership.stagedLocalId())
        assertTrue(storage.exists(previous.localId))
        assertTrue(storage.exists(next.localId))

        assertTrue(
            ownership.activateImportedCandidate(
                attachment = next,
                previous = previous,
            ),
        )

        assertEquals(next.localId, ownership.ownedLocalId())
        assertNull(ownership.stagedLocalId())
        assertFalse(storage.exists(previous.localId))
        assertTrue(storage.exists(next.localId))
    }

    @Test
    fun `failed ownership commit never publishes replacement or changes active pending`() {
        val previous = storedPdf("previous-failed.pdf")
        val preferences = pendingPreferences()
        val setupOwnership = PendingAttachmentOwnership(storage, preferences)
        assertTrue(setupOwnership.markPending(previous))

        val failingOwnership = PendingAttachmentOwnership(
            storage = storage,
            preferences = preferences,
            commitEditor = { false },
        )
        val bytes = "%PDF-failed".toByteArray()

        val result = runCatching {
            storage.importValidated(
                type = AttachmentType.PDF,
                displayName = "replacement-failed.pdf",
                mimeType = "application/pdf",
                byteSize = bytes.size.toLong(),
                source = { ByteArrayInputStream(bytes) },
                beforePublish = failingOwnership::stageImportedCandidate,
            )
        }

        assertTrue(result.isFailure)
        assertEquals(previous.localId, failingOwnership.ownedLocalId())
        assertTrue(storage.exists(previous.localId))
        assertEquals(
            listOf(previous.localId),
            context.filesDir.resolve("advisor_attachments")
                .listFiles()
                .orEmpty()
                .filter(File::isFile)
                .map(File::getName)
                .sorted(),
        )
    }

    @Test
    fun `failed activation commit keeps previous pending and removes new durable file`() {
        val previous = storedPdf("previous-activation.pdf")
        val preferences = pendingPreferences()
        val setupOwnership = PendingAttachmentOwnership(storage, preferences)
        assertTrue(setupOwnership.markPending(previous))

        var commitCount = 0
        val ownership = PendingAttachmentOwnership(
            storage = storage,
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
        val bytes = "%PDF-activation".toByteArray()
        val next = storage.importValidated(
            type = AttachmentType.PDF,
            displayName = "next-activation.pdf",
            mimeType = "application/pdf",
            byteSize = bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
            beforePublish = ownership::stageImportedCandidate,
        )
        var active = previous

        if (
            ownership.activateImportedCandidate(
                attachment = next,
                previous = previous,
            )
        ) {
            active = next
        }

        assertEquals(previous, active)
        assertEquals(previous.localId, ownership.ownedLocalId())
        assertTrue(storage.exists(previous.localId))
        assertFalse(storage.exists(next.localId))
    }

    @Test
    fun `successful replacement never leaves ownership pointing at deleted previous file`() {
        val previous = storedPdf("previous-marker.pdf")
        val ownership = PendingAttachmentOwnership(
            storage,
            pendingPreferences(),
        )
        assertTrue(ownership.markPending(previous))

        val bytes = "%PDF-next".toByteArray()
        val next = storage.importValidated(
            type = AttachmentType.PDF,
            displayName = "next-marker.pdf",
            mimeType = "application/pdf",
            byteSize = bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
            beforePublish = ownership::stageImportedCandidate,
        )

        assertTrue(
            ownership.activateImportedCandidate(
                attachment = next,
                previous = previous,
            ),
        )

        assertFalse(storage.exists(previous.localId))
        assertEquals(next.localId, ownership.ownedLocalId())
        assertTrue(storage.exists(next.localId))
    }

    @Test fun `replacement deletes previous pending private file`() {
        val first = storedPdf("first.pdf")
        val second = storedPdf("second.pdf")
        val pending = PendingAttachmentState(storage, first)

        pending.replace(second)

        assertFalse(storage.exists(first.localId))
        assertTrue(storage.exists(second.localId))
        assertEquals(second, pending.attachment)
    }

    @Test fun `remove deletes pending private file and clears state`() {
        val attachment = storedPdf("remove.pdf")
        val pending = PendingAttachmentState(storage, attachment)
        pending.remove()
        assertFalse(storage.exists(attachment.localId))
        assertNull(pending.attachment)
    }

    @Test fun `cancel represented by no result leaves pending state unchanged`() {
        val attachment = storedPdf("keep.pdf")
        val pending = PendingAttachmentState(storage, attachment)
        assertEquals(attachment, pending.attachment)
        assertTrue(storage.exists(attachment.localId))
    }

    @Test
    fun `cold start deletes exactly orphaned owned pending file`() = runBlocking {
        val attachment = storedPdf("orphan.pdf")
        val firstOwner = PendingAttachmentOwnership(
            storage,
            pendingPreferences(),
        )
        firstOwner.markPending(attachment)

        val coldStartOwner = PendingAttachmentOwnership(
            storage,
            pendingPreferences(),
        )
        val restored = coldStartOwner.reconcileAfterStartup(
            restored = null,
            isPersisted = { false },
        )

        assertNull(restored)
        assertFalse(storage.exists(attachment.localId))
        assertNull(coldStartOwner.ownedLocalId())
    }

    @Test
    fun `pending startup cleanup never removes persisted attachment file`() = runBlocking {
        val attachment = storedPdf("sent.pdf")
        val owner = PendingAttachmentOwnership(
            storage,
            pendingPreferences(),
        )
        owner.markPending(attachment)

        val restored = owner.reconcileAfterStartup(
            restored = null,
            isPersisted = { localId ->
                localId == attachment.localId
            },
        )

        assertNull(restored)
        assertTrue(storage.exists(attachment.localId))
        assertNull(owner.ownedLocalId())
    }

    @Test
    fun `normal restored pending attachment remains usable`() = runBlocking {
        val attachment = storedPdf("restored.pdf")
        val firstOwner = PendingAttachmentOwnership(
            storage,
            pendingPreferences(),
        )
        firstOwner.markPending(attachment)

        val recreatedOwner = PendingAttachmentOwnership(
            storage,
            pendingPreferences(),
        )
        val restored = recreatedOwner.reconcileAfterStartup(
            restored = attachment,
            isPersisted = { false },
        )

        assertEquals(attachment, restored)
        assertTrue(storage.exists(attachment.localId))
        assertEquals(
            attachment.localId,
            recreatedOwner.ownedLocalId(),
        )
    }

    @Test fun `unsupported file is rejected`() {
        val file = captureFile("unsupported.zip", byteArrayOf(1, 2, 3))
        val result = importer.import(uri(file))
        assertEquals(AttachmentImportResult.Failure(AttachmentImportError.UNSUPPORTED_TYPE), result)
    }

    @Test fun `oversized declared PDF is rejected without copying`() {
        val file = captureFile("large.pdf", "%PDF-".toByteArray())
        // The bounded-stream behavior is independently guaranteed even when metadata is absent.
        val oversized = ByteArray((ATTACHMENT_LOCAL_STORAGE_MAX_BYTES + 1).toInt())
        file.writeBytes(oversized)
        val result = importer.import(uri(file))
        assertEquals(AttachmentImportResult.Failure(AttachmentImportError.TOO_LARGE), result)
    }

    @Test fun `valid PDF imports PDF metadata`() {
        val bytes = "%PDF-1.7\n%%EOF".toByteArray()
        val result = importer
            .import(uri(captureFile("manual.pdf", bytes))) as AttachmentImportResult.Success
        assertEquals(AttachmentType.PDF, result.attachment.type)
        assertEquals("application/pdf", result.attachment.mimeType)
        assertEquals(bytes.size.toLong(), result.attachment.byteSize)
        assertTrue(storage.exists(result.attachment.localId))
    }

    @Test fun `valid large image is normalized to bounded dimensions and metadata`() {
        val bitmap = Bitmap.createBitmap(4200, 1200, Bitmap.Config.RGB_565)
        val source = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        bitmap.recycle()
        val result = importer
            .import(uri(captureFile("label.jpg", source))) as AttachmentImportResult.Success
        assertEquals(AttachmentType.IMAGE, result.attachment.type)
        assertTrue(maxOf(result.attachment.width!!, result.attachment.height!!) <= ATTACHMENT_IMAGE_MAX_DIMENSION)
        assertTrue(result.attachment.byteSize in 1..ATTACHMENT_LOCAL_STORAGE_MAX_BYTES)
    }

    @Test fun `resize sample helper is conservative and deterministic`() {
        assertEquals(1, imageSampleSize(4096, 1000, ATTACHMENT_IMAGE_MAX_DIMENSION))
        assertEquals(8, imageSampleSize(17000, 1000, ATTACHMENT_IMAGE_MAX_DIMENSION))
        assertEquals(2, imageSampleSize(8000, 8000, ATTACHMENT_IMAGE_MAX_DIMENSION))
    }

    @Test
    fun `persisted attachment render kind fails soft when private file is missing or truncated`() {
        val image = storage.importValidated(
            type = AttachmentType.IMAGE,
            displayName = "render.jpg",
            mimeType = "image/jpeg",
            byteSize = 3,
            width = 40,
            height = 20,
            source = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) },
        )
        val pdf = storedPdf("render.pdf")

        assertEquals(AttachmentRenderKind.IMAGE, storage.renderKind(image))
        assertEquals(AttachmentRenderKind.PDF, storage.renderKind(pdf))

        assertTrue(storage.delete(image.localId))
        assertEquals(
            AttachmentRenderKind.UNAVAILABLE,
            storage.renderKind(image),
        )

        context.filesDir.resolve("advisor_attachments/${pdf.localId}")
            .writeBytes(byteArrayOf(1))
        assertEquals(
            AttachmentRenderKind.UNAVAILABLE,
            storage.renderKind(pdf),
        )
    }

    @Test
    fun `import guard ignores stale result after newer selection`() {
        val guard = AttachmentImportGuard()
        val first = guard.begin()
        val second = guard.begin()

        assertFalse(guard.isCurrent(first))
        assertTrue(guard.isCurrent(second))
    }

    @Test
    fun `conversation switch or remove invalidates in flight import`() {
        val guard = AttachmentImportGuard()
        val token = guard.begin()

        guard.invalidate()

        assertFalse(guard.isCurrent(token))
    }

    @Test
    fun `stale imported candidate is removed without replacing active pending`() {
        val active = storedPdf("active.pdf")
        val ownership = PendingAttachmentOwnership(storage, pendingPreferences())
        assertTrue(ownership.markPending(active))

        val bytes = "%PDF-stale".toByteArray()
        val stale = storage.importValidated(
            type = AttachmentType.PDF,
            displayName = "stale.pdf",
            mimeType = "application/pdf",
            byteSize = bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
            beforePublish = ownership::stageImportedCandidate,
        )

        ownership.discardImportedCandidate(stale)

        assertEquals(active.localId, ownership.ownedLocalId())
        assertNull(ownership.stagedLocalId())
        assertTrue(storage.exists(active.localId))
        assertFalse(storage.exists(stale.localId))
    }

    @Test
    fun `import loading disables send without changing text only eligibility`() {
        assertFalse(
            canSendAdvisorComposer(
                enabled = true,
                importInProgress = true,
                text = "question",
                attachment = null,
            ),
        )
        assertTrue(
            canSendAdvisorComposer(
                enabled = true,
                importInProgress = false,
                text = "question",
                attachment = null,
            ),
        )
    }
    @Test fun `composer accepts text or attachment and rejects an empty turn`() {
        assertTrue(canSubmitAdvisorComposer("question", null))
        assertFalse(canSubmitAdvisorComposer("", null))
        val attachment = storedPdf("pending.pdf")
        assertTrue(canSubmitAdvisorComposer("question", attachment))
        assertTrue(canSubmitAdvisorComposer("", attachment))
        assertFalse(advisorSubmissionUsesTextTransport(attachment))
    }

    @Test fun `persisted handoff clears pending ownership without deleting file`() {
        val attachment = storedPdf("sent.pdf")
        val ownership = PendingAttachmentOwnership(storage, pendingPreferences())
        assertTrue(ownership.markPending(attachment))

        ownership.handoffToPersisted(attachment.localId)

        assertNull(ownership.ownedLocalId())
        assertTrue(storage.exists(attachment.localId))
    }

    private fun pendingPreferences() =
        context.getSharedPreferences(
            "attachment-pending-test",
            Context.MODE_PRIVATE,
        )

    private fun storedPdf(name: String): AdvisorAttachment {
        val bytes = "%PDF-".toByteArray()
        return storage.importValidated(
            AttachmentType.PDF, name, "application/pdf", bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
        )
    }

    private fun captureFile(name: String, bytes: ByteArray): File =
        File(context.cacheDir, "advisor_camera_capture/$name").apply {
            parentFile!!.mkdirs(); writeBytes(bytes)
        }

    private fun uri(file: File): Uri =
        Uri.parse("content://$TEST_AUTHORITY/${file.name}")

    private class TestAttachmentProvider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun getType(uri: Uri): String =
            when (uri.lastPathSegment?.substringAfterLast('.', "")) {
                "pdf" -> "application/pdf"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                else -> "application/zip"
            }

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val file = file(uri)
            val columns = projection ?: arrayOf(
                android.provider.OpenableColumns.DISPLAY_NAME,
                android.provider.OpenableColumns.SIZE,
            )
            return MatrixCursor(columns).apply {
                addRow(
                    columns.map { column ->
                        when (column) {
                            android.provider.OpenableColumns.DISPLAY_NAME ->
                                file.name
                            android.provider.OpenableColumns.SIZE ->
                                file.length()
                            else -> null
                        }
                    },
                )
            }
        }

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor =
            ParcelFileDescriptor.open(
                file(uri),
                ParcelFileDescriptor.MODE_READ_ONLY,
            )

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        private fun file(uri: Uri): File =
            File(
                requireNotNull(context).cacheDir,
                "advisor_camera_capture/${requireNotNull(uri.lastPathSegment)}",
            )
    }

    private companion object {
        const val TEST_AUTHORITY =
            "pl.lukaszpeciak.towarownik.test.attachments"
    }
}
