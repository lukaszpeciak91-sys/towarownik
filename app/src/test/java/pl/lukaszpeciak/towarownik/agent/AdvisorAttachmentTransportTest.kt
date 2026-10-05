package pl.lukaszpeciak.towarownik.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lukaszpeciak.towarownik.attachment.AttachmentStorage
import pl.lukaszpeciak.towarownik.attachment.AttachmentType

@RunWith(RobolectricTestRunner::class)
class AdvisorAttachmentTransportTest {
    @Test
    fun `attachment start streams private bytes as protocol v4 multipart`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = AttachmentStorage(context)
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        val attachment = storage.importValidated(
            type = AttachmentType.PDF,
            displayName = "spec.pdf",
            mimeType = "application/pdf",
            byteSize = bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
        )
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"type":"answer","responseId":"resp_1","text":"ok","productRefs":[]}""",
            ))
            val client = AdvisorProxyClient(
                appToken = "token",
                baseUrl = server.url("/"),
                attachmentStorage = storage,
            )
            client.start("", "obi-pl", "075", attachment)
            val request = server.takeRequest()
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data;"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"protocolVersion\":4"))
            assertTrue(body.contains("%PDF-"))
            assertEquals("/v1/agent/start", request.path)
        }
    }
}
