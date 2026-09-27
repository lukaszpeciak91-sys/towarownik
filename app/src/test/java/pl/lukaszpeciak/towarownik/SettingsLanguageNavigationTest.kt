package pl.lukaszpeciak.towarownik

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class SettingsLanguageNavigationTest {
    @Test
    fun `supported app languages are Polish and English only`() {
        assertEquals(
            listOf("pl", "en"),
            supportedAppLanguageTags,
        )
        assertEquals("pl", AppLanguage.POLISH.languageTag)
        assertEquals("en", AppLanguage.ENGLISH.languageTag)
        assertEquals("pl", localeListFor(AppLanguage.POLISH).toLanguageTags())
        assertEquals("en", localeListFor(AppLanguage.ENGLISH).toLanguageTags())
    }

    @Test
    fun `resource locale maps to the visible language label`() {
        assertEquals(
            AppLanguage.POLISH,
            appLanguageForTag("pl"),
        )
        assertEquals(
            AppLanguage.ENGLISH,
            appLanguageForTag("en"),
        )
        assertEquals(
            "Polski",
            strings("values").getValue("language_polish"),
        )
        assertEquals(
            "English",
            strings("values-en").getValue("language_english"),
        )
    }

    @Test
    fun `locale configuration exposes only pl and en`() {
        val locales = xmlElements(
            File(resourceDirectory(), "xml/locales_config.xml"),
            "locale",
        ).map {
            it.getAttributeNS(
                "http://schemas.android.com/apk/res/android",
                "name",
            )
        }

        assertEquals(listOf("pl", "en"), locales)
    }

    @Test
    fun `manifest wires platform locale config and AppCompat persistence`() {
        val manifest = parseXml(
            File(projectRoot(), "app/src/main/AndroidManifest.xml"),
        )
        val application = manifest
            .getElementsByTagName("application")
            .item(0) as Element

        assertEquals(
            "@xml/locales_config",
            application.getAttributeNS(
                "http://schemas.android.com/apk/res/android",
                "localeConfig",
            ),
        )

        val services = manifest.getElementsByTagName("service")
        val localeService = (0 until services.length)
            .map { services.item(it) as Element }
            .single {
                it.getAttributeNS(
                    "http://schemas.android.com/apk/res/android",
                    "name",
                ) == "androidx.appcompat.app.AppLocalesMetadataHolderService"
            }
        assertEquals(
            "false",
            localeService.getAttributeNS(
                "http://schemas.android.com/apk/res/android",
                "enabled",
            ),
        )
        assertEquals(
            "false",
            localeService.getAttributeNS(
                "http://schemas.android.com/apk/res/android",
                "exported",
            ),
        )

        val metadata = localeService
            .getElementsByTagName("meta-data")
            .item(0) as Element
        assertEquals(
            "autoStoreLocales",
            metadata.getAttributeNS(
                "http://schemas.android.com/apk/res/android",
                "name",
            ),
        )
        assertEquals(
            "true",
            metadata.getAttributeNS(
                "http://schemas.android.com/apk/res/android",
                "value",
            ),
        )
    }

    @Test
    fun `Settings and diagnostics use the expected back stack`() {
        assertTrue(AppSurface.entries.contains(AppSurface.SETTINGS))
        assertTrue(AppSurface.entries.contains(AppSurface.DIAGNOSTICS))
        assertEquals(
            AppSurface.ADVISOR,
            backSurface(AppSurface.SETTINGS),
        )
        assertEquals(
            AppSurface.SETTINGS,
            backSurface(AppSurface.DIAGNOSTICS),
        )
    }

    @Test
    fun `opening Settings is surface-only and title long press diagnostics is gone`() {
        val source = File(
            projectRoot(),
            "app/src/main/java/pl/lukaszpeciak/towarownik/MainActivity.kt",
        ).readText()
        val openSettingsBody = source.substringAfter("fun openSettings() {")
            .substringBefore("\n    }")

        assertTrue(openSettingsBody.contains("AppSurface.SETTINGS"))
        assertTrue(openSettingsBody.contains("drawerState.close()"))
        assertFalse(openSettingsBody.contains("advisorCase"))
        assertFalse(openSettingsBody.contains("activeConversationId"))
        assertFalse(openSettingsBody.contains("advisorState"))
        val advisorTopBar = source
            .substringAfter("private fun AdvisorTopBar(")
            .substringBefore("@Composable\nprivate fun AdvisorComposer(")

        assertFalse(source.contains("detectTapGestures"))
        assertFalse(source.contains("pointerInput"))
        assertFalse(advisorTopBar.contains("onOpenDiagnostics"))
    }

    @Test
    fun `future Settings actions remain explicitly unavailable`() {
        assertFalse(REPORT_PROBLEM_AVAILABLE)
        assertFalse(PRIVACY_POLICY_AVAILABLE)
    }

    @Test
    fun `AppCompat host and theme are wired for locale recreation`() {
        val activitySource = File(
            projectRoot(),
            "app/src/main/java/pl/lukaszpeciak/towarownik/MainActivity.kt",
        ).readText()
        val themeSource = File(
            resourceDirectory(),
            "values/themes.xml",
        ).readText()

        assertTrue(activitySource.contains("class MainActivity : AppCompatActivity()"))
        assertTrue(themeSource.contains("Theme.AppCompat.NoActionBar"))
    }

    private fun strings(valuesDirectory: String): Map<String, String> {
        val document = parseXml(
            File(resourceDirectory(), "$valuesDirectory/strings.xml"),
        )
        val children = document.documentElement.childNodes
        return buildMap {
            for (index in 0 until children.length) {
                val node = children.item(index)
                if (node is Element && node.tagName == "string") {
                    put(node.getAttribute("name"), node.textContent)
                }
            }
        }
    }

    private fun xmlElements(
        file: File,
        tagName: String,
    ): List<Element> {
        val nodes = parseXml(file).getElementsByTagName(tagName)
        return (0 until nodes.length).map {
            nodes.item(it) as Element
        }
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
}
