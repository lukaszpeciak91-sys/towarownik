package pl.lukaszpeciak.towarownik

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
    fun `conversation inclusion defaults to off`() {
        assertFalse(REPORT_INCLUDE_CONVERSATION_DEFAULT)
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
            assertTrue(file.name.startsWith("towarownik-report-"))
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
    fun `FileProvider exposes only dedicated report cache path`() {
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
            "${applicationId}.fileprovider",
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
        val cachePaths = paths.getElementsByTagName("cache-path")
        assertEquals(1, cachePaths.length)
        val cachePath = cachePaths.item(0) as Element
        assertEquals("problem_reports", cachePath.getAttribute("name"))
        assertEquals("reports/", cachePath.getAttribute("path"))
        assertEquals(1, paths.documentElement.childNodes
            .let { nodes ->
                (0 until nodes.length).count {
                    nodes.item(it) is Element
                }
            })
    }

    @Test
    fun `reporting works without diagnostics and never auto enables them`() {
        val recorder = ObiDiagnosticRecorder()

        assertFalse(recorder.isEnabled())
        assertNull(existingSafeObiDiagnostics(recorder))
        assertFalse(recorder.isEnabled())
    }

    @Test
    fun `existing sanitized diagnostics are included only when already available`() {
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

        val report = existingSafeObiDiagnostics(recorder)

        assertNotNull(report)
        assertTrue(report!!.contains("Towarownik OBI diagnostics"))
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
