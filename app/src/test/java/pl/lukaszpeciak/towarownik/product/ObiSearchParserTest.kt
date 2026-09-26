package pl.lukaszpeciak.towarownik.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObiSearchParserTest {
    private val parser = ObiSearchParser()

    @Test
    fun `search results preserve OBI order deduplicate products and stop at five`() {
        val result = parser.parse(fixture("search-qbrick-results.html")).getOrThrow()

        assertTrue(result is ObiSearchParseResult.Results)
        val items = (result as ObiSearchParseResult.Results).items
        assertEquals(5, items.size)
        assertEquals(
            listOf("7013998", "7014053", "6647598", "1234567", "2345678"),
            items.map { it.obik },
        )
        assertEquals(
            "Qbrick System Skrzynka narzędziowa Qbrick PRO Box 130",
            items.first().name,
        )
    }

    @Test
    fun `recognized product links win over embedded zero-result phrase`() {
        val result = parser.parse(fixture("search-results-with-hidden-empty-state.html")).getOrThrow()

        assertEquals(
            ObiSearchParseResult.Results(
                listOf(
                    ProductSearchCandidate("3496072", "Dragon Klej uniwersalny Butapren 50 ml"),
                    ProductSearchCandidate("1234567", "Drugi produkt"),
                ),
            ),
            result,
        )
    }

    @Test
    fun `canonical product redirect becomes one reliable candidate`() {
        val result = parser.parse(fixture("search-single-product.html")).getOrThrow()

        assertEquals(
            ObiSearchParseResult.Results(
                listOf(ProductSearchCandidate("7013998", null)),
            ),
            result,
        )
    }

    @Test
    fun `explicit zero-result search maps to no results`() {
        assertEquals(
            ObiSearchParseResult.NoResults,
            parser.parse(fixture("search-empty.html")).getOrThrow(),
        )
    }

    @Test
    fun `unrecognized search structure is a parser failure not empty results`() {
        assertTrue(parser.parse(fixture("search-malformed.html")).isFailure)
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/obi/$name")).readText()
}
