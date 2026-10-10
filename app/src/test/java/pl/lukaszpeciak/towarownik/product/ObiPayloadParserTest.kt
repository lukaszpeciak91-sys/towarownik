package pl.lukaszpeciak.towarownik.product

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObiPayloadParserTest {
    private val parser = ObiPayloadParser()

    @Test
    fun `extracts product identity positive stock local price url and ean`() {
        val product = parser.parse(fixture("positive-stock.html"), OBIK, STORE).getOrThrow()

        assertEquals(OBIK, product.obik)
        assertEquals("Wiertarka testowa", product.name)
        assertEquals(12, product.stock)
        assertEquals(BigDecimal("49.99"), product.grossPrice)
        assertEquals("https://www.obi.pl/wiertarki/wiertarka-testowa/p/7313810", product.productUrl)
        assertEquals("5901234567890", product.ean)
        assertEquals(STORE, product.storeNumber)
    }

    @Test
    fun `current live OBI shape resolves shallow ref and selected store article data`() {
        val product = parser.parse(
            fixture("live-3496072-store-075.html"),
            LIVE_OBIK,
            STORE,
        ).getOrThrow()

        assertEquals(LIVE_OBIK, product.obik)
        assertEquals("Dragon Klej uniwersalny Butapren 50 ml", product.name)
        assertEquals(25, product.stock)
        assertEquals(BigDecimal("12.99"), product.grossPrice)
        assertEquals(
            "https://www.obi.pl/p/3496072/dragon-klej-uniwersalny-butapren-50-ml",
            product.productUrl,
        )
        assertEquals("5903649001412", product.ean)
        assertEquals(STORE, product.storeNumber)
    }

    @Test
    fun `confirmed live rich product fields are extracted from decoded Nuxt product`() {
        val product = parser.parse(
            fixture("live-3496072-store-075.html"),
            LIVE_OBIK,
            STORE,
        ).getOrThrow()

        assertEquals("Dragon", product.brand)
        assertEquals(
            "Klej Butapren firmy DRAGON to produkt specjalistyczny, uniwersalnego przeznaczenia. Do łączenia gumy, skóry, tkanin, ceramiki, szkła i drewna.",
            product.shortDescription,
        )
        assertEquals(
            listOf(
                TechnicalFact(
                    "Właściwości",
                    "tworzy wodoodporną, wytrzymałą i elastyczną spoinę",
                ),
                TechnicalFact("Czas wstępnego wiązania", "3 h"),
                TechnicalFact("Zalety", "nie plami łączonych elementów"),
                TechnicalFact("Pojemność", "50 ml"),
                TechnicalFact("rodzaj", "Kleje specjalistyczne"),
                TechnicalFact("Waga", "44 g"),
            ),
            product.technicalFacts,
        )
    }

    @Test
    fun `confirmed live OBI primary image is extracted from structured product data`() {
        val product = parser.parse(
            fixture("live-3496072-store-075.html"),
            LIVE_OBIK,
            STORE,
        ).getOrThrow()

        assertEquals(
            "https://bilder.obi.pl/0834d60c-3719-4acf-8ad4-ce901b2bbe8c/pr08A/image.jpeg",
            product.primaryImageUrl,
        )
        assertEquals("Dragon Klej uniwersalny Butapren 50 ml", product.name)
        assertEquals(25, product.stock)
        assertEquals(BigDecimal("12.99"), product.grossPrice)
    }

    @Test
    fun `multiple structured gallery images select the first product image`() {
        val product = parser.parse(
            fixture("live-6743009-store-075-image.html"),
            "6743009",
            STORE,
        ).getOrThrow()

        assertEquals(
            "https://bilder.obi.pl/464012a8-24d7-4687-8ded-e2a5e57034b7/pr08A/image.jpeg",
            product.primaryImageUrl,
        )
        assertEquals("Bosch Wiertarka Easy Impact 600 W", product.name)
        assertEquals(
            0,
            product.grossPrice?.compareTo(BigDecimal("188.00")),
        )
    }

    @Test
    fun `unrelated page images do not replace the structured product image`() {
        val product = parser.parse(
            fixture("live-7156243-store-075-image.html"),
            "7156243",
            STORE,
        ).getOrThrow()

        assertEquals(
            "https://bilder.obi.pl/f13501fd-9fc2-4e8d-b45f-0ff14ef115e7/pr08A/image.jpeg",
            product.primaryImageUrl,
        )
        assertEquals(
            "Oprawa BATTEN LED 30 cm 9W 850 lm 4000K IP20",
            product.name,
        )
        assertEquals(BigDecimal("23.99"), product.grossPrice)
    }

    @Test
    fun `missing structured product image is accepted as null`() {
        val product = parser.parse(
            optionalImageFixture(imageJson = null),
            LIVE_OBIK,
            STORE,
        ).getOrThrow()

        assertNull(product.primaryImageUrl)
        assertEquals("Synthetic product", product.name)
        assertEquals(4, product.stock)
        assertEquals(BigDecimal("9.99"), product.grossPrice)
    }

    @Test
    fun `malformed structured image data is accepted as null`() {
        val product = parser.parse(
            optionalImageFixture(
                imageJson = """{"unexpected":["not-a-url"]}""",
            ),
            LIVE_OBIK,
            STORE,
        ).getOrThrow()

        assertNull(product.primaryImageUrl)
    }

    @Test
    fun `non https and untrusted image hosts are rejected`() {
        listOf(
            "\"http://bilder.obi.pl/example/image.jpeg\"",
            "\"https://imgix.obi.de/example/image.jpeg\"",
            "\"https://bilder.obi.pl.evil.example/image.jpeg\"",
        ).forEach { imageJson ->
            val product = parser.parse(
                optionalImageFixture(imageJson),
                LIVE_OBIK,
                STORE,
            ).getOrThrow()

            assertNull(product.primaryImageUrl)
        }
    }

    @Test
    fun `missing optional rich product data keeps basic product valid`() {
        val product = parser.parse(
            optionalRichFixture(
                richFields = "",
            ),
            LIVE_OBIK,
            STORE,
        ).getOrThrow()

        assertEquals(LIVE_OBIK, product.obik)
        assertEquals("Synthetic product", product.name)
        assertEquals(4, product.stock)
        assertEquals(BigDecimal("9.99"), product.grossPrice)
        assertNull(product.brand)
        assertNull(product.shortDescription)
        assertTrue(product.technicalFacts.isEmpty())
    }

    @Test
    fun `malformed optional rich subsections fail soft entry by entry`() {
        val rich = """
            ,"brand":"not-an-object"
            ,"productDescription":["not","a","string"]
            ,"productOverview":[
              {"bad":"entry"},
              "Moc [W]: 600",
              "bez separatora"
            ]
            ,"technicalData":{
              "productDetails":[
                {"key":"Prędkość","value":"  3000/min  "},
                {"key":5,"value":"bad"},
                {"key":"Pusty","value":"   "}
              ],
              "dimensionsAndWeight":"bad"
            }
        """.trimIndent().replace("\n", "")

        val product = parser.parse(
            optionalRichFixture(rich),
            LIVE_OBIK,
            STORE,
        ).getOrThrow()

        assertNull(product.brand)
        assertNull(product.shortDescription)
        assertEquals(
            listOf(
                TechnicalFact("Moc [W]", "600"),
                TechnicalFact("Prędkość", "3000/min"),
            ),
            product.technicalFacts,
        )
    }

    @Test
    fun `rich text normalizes markup whitespace deduplicates labels and respects bounds`() {
        val longBrand = "B".repeat(100)
        val longDescription = "D".repeat(700)
        val overview = (1..20).joinToString(",") { index ->
            val label = if (index == 2) "Fakt 1" else "Fakt $index"
            "\"$label: ${"V".repeat(220)}\""
        }
        val rich = """
            ,"brand":{"name":"  $longBrand  "}
            ,"productDescription":"<p> $longDescription </p>"
            ,"productOverview":[$overview]
            ,"technicalData":{
              "productDetails":[{"key":"Fakt 1","value":"duplicate"}],
              "dimensionsAndWeight":[{"key":"Waga","value":"  44   g  "}]
            }
        """.trimIndent().replace("\n", "")

        val product = parser.parse(
            optionalRichFixture(rich),
            LIVE_OBIK,
            STORE,
        ).getOrThrow()

        assertEquals(80, product.brand?.length)
        assertEquals(220, product.shortDescription?.length)
        assertEquals(6, product.technicalFacts.size)
        assertEquals(1, product.technicalFacts.count { it.label == "Fakt 1" })
        assertTrue(product.technicalFacts.all { it.label.length <= 60 })
        assertTrue(product.technicalFacts.all { it.value.length <= 100 })
    }

    @Test
    fun `confirmed rich fields also survive Ref wrapper decoding`() {
        val html = fixture("live-3496072-store-075.html")
            .replace("[\"ShallowRef\",4]", "[\"Ref\",4]")

        val product = parser.parse(
            html,
            LIVE_OBIK,
            STORE,
        ).getOrThrow()

        assertEquals("Dragon", product.brand)
        assertTrue(product.shortDescription?.contains("Butapren") == true)
        assertTrue(product.technicalFacts.isNotEmpty())
    }

    @Test
    fun `current live OBI EAN comes from singleton Nuxt array without JSON-LD fallback`() {
        val product = parser.parse(
            fixture("live-3496072-store-075.html"),
            LIVE_OBIK,
            STORE,
        ).getOrThrow()

        assertEquals("5903649001412", product.ean)
    }

    @Test
    fun `multiple live OBI EAN values are not guessed`() {
        val html = fixture("live-3496072-store-075.html")
            .replace(",[27],{\"product\":24}", ",[27,27],{\"product\":24}")

        val product = parser.parse(html, LIVE_OBIK, STORE).getOrThrow()

        assertNull(product.ean)
    }

    @Test
    fun `non-string live OBI EAN array value is not guessed`() {
        val html = fixture("live-3496072-store-075.html")
            .replace(",[27],{\"product\":24}", ",[13],{\"product\":24}")

        val product = parser.parse(html, LIVE_OBIK, STORE).getOrThrow()

        assertNull(product.ean)
    }

    @Test
    fun `current live OBI shape never substitutes seller or fallback values for local store data`() {
        val product = parser.parse(
            fixture("live-3496072-store-075.html"),
            LIVE_OBIK,
            STORE,
        ).getOrThrow()

        assertEquals(25, product.stock)
        assertTrue(product.stock != 9)
        assertEquals(BigDecimal("12.99"), product.grossPrice)
        assertTrue(product.grossPrice != BigDecimal("11.11"))
        assertTrue(product.grossPrice != BigDecimal("99.99"))
    }

    @Test
    fun `current live OBI shape rejects a different store`() {
        assertTrue(
            parser.parse(
                fixture("live-3496072-store-075.html"),
                LIVE_OBIK,
                "999",
            ).isFailure,
        )
    }

    @Test
    fun `real OBI structure ties identity local stock and gross price to store 075`() {
        val product = parser.parse(fixture("real-7313810-store-075.html"), OBIK, STORE).getOrThrow()

        assertEquals(OBIK, product.obik)
        assertEquals("Produkt OBI 7313810", product.name)
        assertEquals(STORE, product.storeNumber)
        assertEquals(17, product.stock)
        assertEquals(BigDecimal("123.45"), product.grossPrice)
        assertEquals("https://www.obi.pl/p/7313810", product.productUrl)
        assertEquals("5900007313810", product.ean)
        // No trusted per-store inventory-unit field in the verified real fixture.
        assertNull(product.stockUnit)
    }

    @Test
    fun `live OBI store stock without explicit unit does not invent pieces`() {
        val product = parser.parse(
            fixture("live-3496072-store-075.html"), "3496072", STORE,
        ).getOrThrow()
        assertEquals(25, product.stock)
        assertNull(product.stockUnit)
    }

    @Test
    fun `real OBI structure does not use online seller price or shipping cost as local price`() {
        val product = parser.parse(fixture("real-7313810-store-075.html"), OBIK, STORE).getOrThrow()

        assertEquals(BigDecimal("123.45"), product.grossPrice)
        assertTrue(product.grossPrice != BigDecimal("111.11"))
        assertTrue(product.grossPrice != BigDecimal("19.99"))
    }

    @Test
    fun `real OBI structure preserves confirmed zero local stock`() {
        val product = parser.parse(fixture("real-7313810-store-075-zero-stock.html"), OBIK, STORE).getOrThrow()

        assertEquals(0, product.stock)
        assertEquals(BigDecimal("123.45"), product.grossPrice)
    }

    @Test
    fun `real OBI structure rejects a different selected store context`() {
        assertTrue(parser.parse(fixture("real-7313810-store-075.html"), OBIK, "999").isFailure)
    }

    @Test
    fun `preserves confirmed zero stock`() {
        assertEquals(0, parser.parse(fixture("zero-stock.html"), OBIK, STORE).getOrThrow().stock)
    }

    @Test
    fun `keeps missing stock unknown`() {
        assertNull(parser.parse(fixture("missing-stock.html"), OBIK, STORE).getOrThrow().stock)
    }

    @Test
    fun `keeps missing local price unknown`() {
        assertNull(parser.parse(fixture("missing-price.html"), OBIK, STORE).getOrThrow().grossPrice)
    }

    @Test
    fun `uses pricing gross price instead of online price or shipping cost`() {
        val product = parser.parse(fixture("positive-stock.html"), OBIK, STORE).getOrThrow()
        assertEquals(BigDecimal("49.99"), product.grossPrice)
    }

    @Test
    fun `malformed payload fails without fabricated product`() {
        assertTrue(parser.parse(fixture("malformed.html"), OBIK, STORE).isFailure)
    }

    @Test
    fun `different product identity fails`() {
        assertTrue(parser.parse(fixture("positive-stock.html"), "1234567", STORE).isFailure)
    }

    private fun optionalImageFixture(
        imageJson: String?,
    ): String {
        val imageField = imageJson?.let { ",\"image\":$it" }.orEmpty()
        return """
        <!doctype html><html><body>
        <script id="__NUXT_DATA__" type="application/json">
        {
          "skuId":"3496072",
          "productTitle":"Synthetic product",
          "prettyUrl":"/p/3496072/synthetic",
          "articleEanEcms":["5900000000000"],
          "store":{
            "information":{"storeId":"075"},
            "articleData":{
              "stock":4,
              "pricing":{"grossPrice":9.99}
            }
          }
        }
        </script>
        <script type="application/ld+json">
        {
          "@context":"https://schema.org",
          "@type":"Product",
          "sku":"3496072",
          "name":"Synthetic product"
          $imageField
        }
        </script>
        </body></html>
        """.trimIndent()
    }

    private fun optionalRichFixture(
        richFields: String,
    ): String =
        """
        <!doctype html><html><body>
        <script id="__NUXT_DATA__" type="application/json">
        {
          "skuId":"3496072",
          "productTitle":"Synthetic product",
          "prettyUrl":"/p/3496072/synthetic",
          "articleEanEcms":["5900000000000"],
          "store":{
            "information":{"storeId":"075"},
            "articleData":{
              "stock":4,
              "pricing":{"grossPrice":9.99}
            }
          }
          $richFields
        }
        </script>
        </body></html>
        """.trimIndent()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/obi/$name")).readText()

    private companion object {
        const val OBIK = "7313810"
        const val LIVE_OBIK = "3496072"
        const val STORE = "075"
    }
}
