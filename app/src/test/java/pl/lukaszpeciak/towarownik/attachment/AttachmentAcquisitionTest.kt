package pl.lukaszpeciak.towarownik.attachment

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.test.mock.MockContentResolver
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
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentAcquisitionTest {
    private lateinit var context: Context
    private lateinit var storage: AttachmentStorage
    private lateinit var resolver: MockContentResolver
    private lateinit var importer: AttachmentImporter

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = AttachmentStorage(context)
        resolver = MockContentResolver().apply {
            addProvider(
                TEST_AUTHORITY,
                TestAttachmentProvider(
                    File(context.cacheDir, "advisor_camera_capture"),
                ),
            )
        }
        importer = AttachmentImporter(resolver, storage)
        context.filesDir.resolve("advisor_attachments").deleteRecursively()
        context.cacheDir.resolve("advisor_camera_capture").deleteRecursively()
        pendingPreferences().edit().clear().commit()
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
        assertEquals(4, imageSampleSize(17000, 1000, ATTACHMENT_IMAGE_MAX_DIMENSION))
    }

    @Test fun `text only eligibility stays unchanged and attachment never degrades`() {
        assertTrue(canSubmitAdvisorComposer("question", null))
        assertFalse(canSubmitAdvisorComposer("", null))
        val attachment = storedPdf("pending.pdf")
        assertFalse(canSubmitAdvisorComposer("question", attachment))
        assertFalse(canSubmitAdvisorComposer("", attachment))
        assertFalse(advisorSubmissionUsesTextTransport(attachment))
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

    private class TestAttachmentProvider(
        private val root: File,
    ) : ContentProvider() {
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
            File(root, requireNotNull(uri.lastPathSegment))
    }

    private companion object {
        const val TEST_AUTHORITY =
            "pl.lukaszpeciak.towarownik.test.attachments"
    }
}
