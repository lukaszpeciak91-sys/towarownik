package pl.lukaszpeciak.towarownik

import java.io.File
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.w3c.dom.Element

class LocalizationResourcesTest {
    @Test
    fun `Polish and English resource files expose the same string keys`() {
        assertEquals(
            strings("values").keys,
            strings("values-en").keys,
        )
    }

    @Test
    fun `core UI strings are available in Polish and English`() {
        val polish = strings("values")
        val english = strings("values-en")

        assertEquals("Taksula", polish.getValue("app_name"))
        assertEquals("Taksula", english.getValue("app_name"))
        assertEquals("Doradca", polish.getValue("advisor_title"))
        assertEquals("Advisor", english.getValue("advisor_title"))
        assertEquals(
            "Wyszukiwarka OBI",
            polish.getValue("manual_search_title"),
        )
        assertEquals(
            "OBI search",
            english.getValue("manual_search_title"),
        )
        assertEquals("Zgłoś", polish.getValue("report_action"))
        assertEquals("Report", english.getValue("report_action"))
        assertEquals(
            "Taksula — diagnostyka OBI",
            polish.getValue("diagnostics_share_subject"),
        )
        assertEquals(
            "Taksula — OBI diagnostics",
            english.getValue("diagnostics_share_subject"),
        )
        assertEquals(
            "Taksula — zgłoszenie problemu",
            polish.getValue("report_share_subject_general"),
        )
        assertEquals(
            "Taksula — problem report",
            english.getValue("report_share_subject_general"),
        )
        assertEquals(
            "Błędna / zmyślona odpowiedź",
            polish.getValue("report_category_incorrect_fabricated"),
        )
        assertEquals(
            "Incorrect / fabricated answer",
            english.getValue("report_category_incorrect_fabricated"),
        )
    }

    @Test
    fun `public UI strings no longer expose the Towarownik working name`() {
        val values = strings("values").values + strings("values-en").values

        assertFalse(
            values.any { value ->
                value.contains("Towarownik", ignoreCase = true)
            },
        )
    }

    @Test
    fun `stock resources preserve null zero and positive semantics in both locales`() {
        val polish = strings("values")
        val english = strings("values-en")

        assertEquals(
            "Stan: brak danych",
            polish.getValue("product_stock_unknown"),
        )
        assertEquals(
            "Stock: no data",
            english.getValue("product_stock_unknown"),
        )
        assertEquals(
            "Stan: 0 szt. — brak na stanie",
            polish.getValue("product_stock_zero"),
        )
        assertEquals(
            "Stock: 0 — unavailable",
            english.getValue("product_stock_zero"),
        )
        assertEquals(
            "Stan: 7 szt.",
            format(polish.getValue("product_stock_count"), 7),
        )
        assertEquals(
            "Stock: 7",
            format(english.getValue("product_stock_count"), 7),
        )
    }

    @Test
    fun `price resources preserve unknown and present semantics in both locales`() {
        val polish = strings("values")
        val english = strings("values-en")
        val price = BigDecimal("14.99").toPlainString()

        assertEquals(
            "Cena: brak danych",
            polish.getValue("product_price_unknown"),
        )
        assertEquals(
            "Price: no data",
            english.getValue("product_price_unknown"),
        )
        assertEquals(
            "Cena: 14.99 zł",
            format(polish.getValue("product_price"), price),
        )
        assertEquals(
            "Price: 14.99 zł",
            format(english.getValue("product_price"), price),
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
            format(strings("values").getValue("product_verified_at"), value),
        )
        assertEquals(
            "Checked 27.09, 10:24",
            format(strings("values-en").getValue("product_verified_at"), value),
        )
    }

    private fun strings(valuesDirectory: String): Map<String, String> {
        val file = File(
            findResourceDirectory(),
            "$valuesDirectory/strings.xml",
        )
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val children = document.documentElement.childNodes
        return buildMap {
            for (index in 0 until children.length) {
                val node = children.item(index)
                if (node is Element && node.tagName == "string") {
                    put(
                        node.getAttribute("name"),
                        node.textContent,
                    )
                }
            }
        }
    }

    private fun findResourceDirectory(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")),
        )
        return listOf(
            File(workingDirectory, "app/src/main/res"),
            File(workingDirectory, "src/main/res"),
        ).firstOrNull(File::isDirectory)
            ?: error("Android resource directory not found")
    }

    private fun format(
        template: String,
        vararg arguments: Any,
    ): String =
        String.format(
            Locale.ROOT,
            template,
            *arguments,
        )
}
