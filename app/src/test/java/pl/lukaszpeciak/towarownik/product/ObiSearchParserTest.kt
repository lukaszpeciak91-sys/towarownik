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
    fun `empty result state wins over unrelated recommendation product link`() {
        assertEquals(
            ObiSearchParseResult.NoResults,
            parser.parse(fixture("search-empty.html")).getOrThrow(),
        )
    }

    @Test
    fun `positive result count caps candidates before later recommendation links`() {
        val html = """
            <html><body>
            <h1>Wyniki dla pufas (1)</h1>
            <a href="/p/3496072/wynik">Prawdziwy wynik</a>
            <section class="recommendations">
              <a href="/p/7014053/rekomendacja">Rekomendacja</a>
            </section>
            </body></html>
        """.trimIndent()

        assertEquals(
            ObiSearchParseResult.Results(
                listOf(ProductSearchCandidate("3496072", "Prawdziwy wynik")),
            ),
            parser.parse(html).getOrThrow(),
        )
    }

    @Test
    fun `empty phrase before recommendation rating prevents false positive result count`() {
        val html = """
            <html><body>
            <h1>Wyniki dla qubrick</h1>
            <p>Nie znaleźliśmy żadnych wyników dla qubrick.</p>
            <a href="/p/7014053/rekomendacja">Rekomendacja</a>
            <span>4.8 (31)</span>
            </body></html>
        """.trimIndent()

        assertEquals(
            ObiSearchParseResult.NoResults,
            parser.parse(html).getOrThrow(),
        )
    }

    @Test
    fun `positive result count without recognizable products is a data failure`() {
        val html = """
            <html><body>
            <h1>Wyniki dla dedra (2)</h1>
            <div>Brak rozpoznawalnych kart produktów</div>
            </body></html>
        """.trimIndent()

        assertTrue(parser.parse(html).isFailure)
    }

    @Test
    fun `product link without positive result count or empty state is ambiguous`() {
        val html = """
            <html><body>
            <a href="/p/7014053/rekomendacja">Rekomendacja</a>
            </body></html>
        """.trimIndent()

        assertTrue(parser.parse(html).isFailure)
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
