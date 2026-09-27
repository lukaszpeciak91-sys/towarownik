package pl.lukaszpeciak.towarownik

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalizationResourcesTest {
    private val baseContext: Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun `Polish and English resource files expose the same string keys`() {
        val resDir = findResourceDirectory()
        val polish = stringNames(File(resDir, "values/strings.xml"))
        val english = stringNames(File(resDir, "values-en/strings.xml"))

        assertEquals(polish, english)
    }

    @Test
    fun `core UI strings resolve in Polish and English`() {
        val polish = localizedContext("pl")
        val english = localizedContext("en")

        assertEquals("Towarownik", polish.getString(R.string.app_name))
        assertEquals("Towarownik", english.getString(R.string.app_name))
        assertEquals("Doradca", polish.getString(R.string.advisor_title))
        assertEquals("Advisor", english.getString(R.string.advisor_title))
        assertEquals(
            "Wyszukiwarka OBI",
            polish.getString(R.string.manual_search_title),
        )
        assertEquals(
            "OBI search",
            english.getString(R.string.manual_search_title),
        )
    }

    @Test
    fun `stock presentation preserves null zero and positive semantics in both locales`() {
        val polish = localizedContext("pl")
        val english = localizedContext("en")

        assertEquals(
            "Stan Nowy Sącz: brak danych",
            polish.getString(store075StockStringRes(null)),
        )
        assertEquals(
            "Nowy Sącz stock: no data",
            english.getString(store075StockStringRes(null)),
        )
        assertEquals(
            "Stan Nowy Sącz: 0 szt. — brak na stanie",
            polish.getString(store075StockStringRes(0)),
        )
        assertEquals(
            "Nowy Sącz stock: 0 — unavailable",
            english.getString(store075StockStringRes(0)),
        )
        assertEquals(
            "Stan Nowy Sącz: 7 szt.",
            polish.getString(store075StockStringRes(7), 7),
        )
        assertEquals(
            "Nowy Sącz stock: 7",
            english.getString(store075StockStringRes(7), 7),
        )
    }

    @Test
    fun `price presentation preserves unknown and present semantics in both locales`() {
        val polish = localizedContext("pl")
        val english = localizedContext("en")
        val price = BigDecimal("14.99")

        assertEquals(
            "Cena Nowy Sącz: brak danych",
            polish.getString(store075PriceStringRes(null)),
        )
        assertEquals(
            "Nowy Sącz price: no data",
            english.getString(store075PriceStringRes(null)),
        )
        assertEquals(
            "Cena Nowy Sącz: 14.99 zł",
            polish.getString(
                store075PriceStringRes(price),
                price.toPlainString(),
            ),
        )
        assertEquals(
            "Nowy Sącz price: 14.99 zł",
            english.getString(
                store075PriceStringRes(price),
                price.toPlainString(),
            ),
        )
    }

    @Test
    fun `verification timestamp keeps factual value and localizes its label`() {
        val zone = ZoneId.of("Europe/Warsaw")
        val verifiedAt = ZonedDateTime.of(
            2026,
            9,
            27,
            10,
            24,
            0,
            0,
            zone,
        ).toInstant().toEpochMilli()
        val value = formatVerifiedProductTimestampValue(
            verifiedAt = verifiedAt,
            zoneId = zone,
        )

        assertEquals("27.09, 10:24", value)
        assertEquals(
            "Sprawdzono 27.09, 10:24",
            localizedContext("pl").getString(
                R.string.product_verified_at,
                value,
            ),
        )
        assertEquals(
            "Checked 27.09, 10:24",
            localizedContext("en").getString(
                R.string.product_verified_at,
                value,
            ),
        )
    }

    private fun localizedContext(languageTag: String): Context {
        val configuration = Configuration(baseContext.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageTag))
        return baseContext.createConfigurationContext(configuration)
    }

    private fun findResourceDirectory(): File {
        val workingDirectory = File(System.getProperty("user.dir"))
        return listOf(
            File(workingDirectory, "app/src/main/res"),
            File(workingDirectory, "src/main/res"),
        ).firstOrNull(File::isDirectory)
            ?: error("Android resource directory not found")
    }

    private fun stringNames(file: File): Set<String> {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val children = document.documentElement.childNodes
        return buildSet {
            for (index in 0 until children.length) {
                val node = children.item(index)
                if (node is Element && node.tagName == "string") {
                    add(node.getAttribute("name"))
                }
            }
        }
    }
}
