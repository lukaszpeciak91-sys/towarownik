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
        const val STORE = "075"
    }
}
