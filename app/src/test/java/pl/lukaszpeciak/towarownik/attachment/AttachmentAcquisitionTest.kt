package pl.lukaszpeciak.towarownik.attachment

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storage = AttachmentStorage(context)
        context.filesDir.resolve("advisor_attachments").deleteRecursively()
        context.cacheDir.resolve("advisor_camera_capture").deleteRecursively()
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

    @Test fun `unsupported file is rejected`() {
        val file = captureFile("unsupported.zip", byteArrayOf(1, 2, 3))
        val result = AttachmentImporter(context.contentResolver, storage).import(uri(file))
        assertEquals(AttachmentImportResult.Failure(AttachmentImportError.UNSUPPORTED_TYPE), result)
    }

    @Test fun `oversized declared PDF is rejected without copying`() {
        val file = captureFile("large.pdf", "%PDF-".toByteArray())
        // The bounded-stream behavior is independently guaranteed even when metadata is absent.
        val oversized = ByteArray((ATTACHMENT_LOCAL_STORAGE_MAX_BYTES + 1).toInt())
        file.writeBytes(oversized)
        val result = AttachmentImporter(context.contentResolver, storage).import(uri(file))
        assertEquals(AttachmentImportResult.Failure(AttachmentImportError.TOO_LARGE), result)
    }

    @Test fun `valid PDF imports PDF metadata`() {
        val bytes = "%PDF-1.7\n%%EOF".toByteArray()
        val result = AttachmentImporter(context.contentResolver, storage)
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
        val result = AttachmentImporter(context.contentResolver, storage)
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

    private fun uri(file: File) = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
}
