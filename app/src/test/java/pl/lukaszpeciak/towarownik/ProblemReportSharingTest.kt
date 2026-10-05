package pl.lukaszpeciak.towarownik

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticInputType
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticOperationType
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticRecorder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProblemReportSharingTest {
    @Test
    fun `conversation and diagnostics inclusion default to off`() {
        assertFalse(REPORT_INCLUDE_CONVERSATION_DEFAULT)
        assertFalse(REPORT_INCLUDE_OBI_DIAGNOSTICS_DEFAULT)
    }

    @Test
    fun `report origin returns to the correct surface`() {
        assertEquals(
            AppSurface.ADVISOR,
            reportBackSurface(ProblemReportOrigin.ADVISOR),
        )
        assertEquals(
            AppSurface.SETTINGS,
            reportBackSurface(ProblemReportOrigin.SETTINGS),
        )
    }

    @Test
    fun `opening a report only records report navigation state`() {
        val source = File(
            projectRoot(),
            "app/src/main/java/pl/lukaszpeciak/towarownik/MainActivity.kt",
        ).readText()
        val general = source
            .substringAfter("fun openGeneralReport() {")
            .substringBefore("\n    }")
        val assistant = source
            .substringAfter("fun openAssistantReport(messageId: Long) {")
            .substringBefore("\n    }")

        listOf(general, assistant).forEach { body ->
            assertFalse(body.contains("advisorCase ="))
            assertFalse(body.contains("updateDraft"))
            assertFalse(body.contains("conversationRepository."))
            assertFalse(body.contains("advisorController."))
        }
        assertTrue(general.contains("ProblemReportType.GENERAL"))
        assertTrue(assistant.contains("ProblemReportType.ASSISTANT_RESPONSE"))
    }

    @Test
    fun `report file is created only under dedicated cache reports directory`() {
        val cache = Files.createTempDirectory("towarownik-cache").toFile()
        try {
            File(cache, "reports").mkdirs()
            File(cache, "reports/old.txt").writeText("old")

            val file = ProblemReportFileStore(cache).write(
                reportText = "UTF-8: zażółć",
                createdAtMillis = 0L,
            )

            assertEquals("reports", file.parentFile?.name)
            assertTrue(file.name.startsWith("taksula-report-"))
            assertTrue(file.name.endsWith(".txt"))
            assertEquals("UTF-8: zażółć", file.readText())
            assertFalse(File(cache, "reports/old.txt").exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun `share intent attaches txt recipient subject body and read permission`() {
        val uri = Uri.parse(
            "content://pl.lukaszpeciak.towarownik.fileprovider/problem_reports/report.txt",
        )
        val intent = buildProblemReportSendIntent(
            reportUri = uri,
            subject = "Synthetic subject",
            body = "Synthetic body",
        )

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertArrayEquals(
            arrayOf(REPORT_RECIPIENT_EMAIL),
            intent.getStringArrayExtra(Intent.EXTRA_EMAIL),
        )
        assertEquals(
            "Synthetic subject",
            intent.getStringExtra(Intent.EXTRA_SUBJECT),
        )
        assertEquals(
            "Synthetic body",
            intent.getStringExtra(Intent.EXTRA_TEXT),
        )
        assertEquals(uri, intent.extras?.get(Intent.EXTRA_STREAM))
        assertTrue(
            intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
        )
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun `share launch failure deletes fresh report file and returns controlled error`() {
        val cache = Files.createTempDirectory("towarownik-share-failure").toFile()
        try {
            val file = File(cache, "report.txt").apply {
                writeText("report")
            }
            val payload = ProblemReportSharePayload(
                intent = Intent(Intent.ACTION_SEND),
                reportFile = file,
            )

            val error = launchProblemReportShare(
                payload = payload,
                chooserTitle = "Share report",
                startActivity = {
                    throw ActivityNotFoundException("synthetic")
                },
            )

            assertEquals(ProblemReportUiError.SHARE_UNAVAILABLE, error)
            assertFalse(file.exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun `successful share launch keeps report file for receiving app`() {
        val cache = Files.createTempDirectory("towarownik-share-success").toFile()
        try {
            val file = File(cache, "report.txt").apply {
                writeText("report")
            }
            val payload = ProblemReportSharePayload(
                intent = Intent(Intent.ACTION_SEND),
                reportFile = file,
            )
            var launched: Intent? = null

            val error = launchProblemReportShare(
                payload = payload,
                chooserTitle = "Share report",
                startActivity = { launched = it },
            )

            assertNull(error)
            assertNotNull(launched)
            assertTrue(file.exists())
        } finally {
            cache.deleteRecursively()
        }
    }

    @Test
    fun `FileProvider exposes only dedicated report and attachment paths`() {
        val manifest = parseXml(
            File(projectRoot(), "app/src/main/AndroidManifest.xml"),
        )
        val providers = manifest.getElementsByTagName("provider")
        val provider = (0 until providers.length)
            .map { providers.item(it) as Element }
            .single {
                it.getAttributeNS(
                    ANDROID_NS,
                    "name",
                ) == "androidx.core.content.FileProvider"
            }

        assertEquals(
            "\${applicationId}.fileprovider",
            provider.getAttributeNS(ANDROID_NS, "authorities"),
        )
        assertEquals(
            "false",
            provider.getAttributeNS(ANDROID_NS, "exported"),
        )
        assertEquals(
            "true",
            provider.getAttributeNS(ANDROID_NS, "grantUriPermissions"),
        )

        val paths = parseXml(
            File(resourceDirectory(), "xml/report_file_paths.xml"),
        )
        val elements = paths.documentElement.childNodes
            .let { nodes ->
                (0 until nodes.length)
                    .mapNotNull { nodes.item(it) as? Element }
            }
        assertEquals(3, elements.size)
        assertEquals(
            setOf(
                Triple("cache-path", "problem_reports", "reports/"),
                Triple(
                    "cache-path",
                    "advisor_camera_capture",
                    "advisor_camera_capture/",
                ),
                Triple(
                    "files-path",
                    "advisor_attachments",
                    "advisor_attachments/",
                ),
            ),
            elements.map {
                Triple(
                    it.tagName,
                    it.getAttribute("name"),
                    it.getAttribute("path"),
                )
            }.toSet(),
        )
    }

    @Test
    fun `reporting works without diagnostics and never auto enables them`() {
        val recorder = ObiDiagnosticRecorder()

        assertFalse(recorder.isEnabled())
        assertFalse(hasExistingSafeObiDiagnostics(recorder))
        assertNull(
            existingSafeObiDiagnostics(
                recorder = recorder,
                include = true,
            ),
        )
        assertFalse(recorder.isEnabled())
    }

    @Test
    fun `existing sanitized diagnostics require explicit opt in`() {
        val recorder = ObiDiagnosticRecorder()
        recorder.setEnabled(true)
        val operation = recorder.startOperation(
            operation = ObiDiagnosticOperationType.PRODUCT_LOOKUP,
            inputType = ObiDiagnosticInputType.OBIK,
            identifier = "1234567",
            requestedUrl = "https://www.obi.pl/p/1234567",
            requestMethod = "GET",
        )
        recorder.finish(operation)

        assertTrue(hasExistingSafeObiDiagnostics(recorder))
        assertNull(
            existingSafeObiDiagnostics(
                recorder = recorder,
                include = false,
            ),
        )

        val report = existingSafeObiDiagnostics(
            recorder = recorder,
            include = true,
        )

        assertNotNull(report)
        assertTrue(report!!.contains("Taksula OBI diagnostics"))
        assertTrue(recorder.isEnabled())
    }

    @Test
    fun `reporting source has no live probe or network trigger`() {
        val source = File(
            projectRoot(),
            "app/src/main/java/pl/lukaszpeciak/towarownik/ProblemReporting.kt",
        ).readText()

        assertFalse(source.contains("ObiLiveProbeRunner"))
        assertFalse(source.contains("ProductLookupRepository"))
        assertFalse(source.contains("ProductSearchRepository"))
        assertFalse(source.contains("AdvisorProxy"))
    }

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(file)

    private fun resourceDirectory(): File =
        File(projectRoot(), "app/src/main/res")

    private fun projectRoot(): File {
        var current = File(
            requireNotNull(System.getProperty("user.dir")),
        )
        repeat(3) {
            if (File(current, "app/src/main").isDirectory) {
                return current
            }
            current = current.parentFile ?: return@repeat
        }
        error("Project root not found")
    }

    private companion object {
        const val ANDROID_NS =
            "http://schemas.android.com/apk/res/android"
    }
}
