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

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/obi/$name")).readText()

    private companion object {
        const val OBIK = "7313810"
        const val LIVE_OBIK = "3496072"
        const val STORE = "075"
    }
}
