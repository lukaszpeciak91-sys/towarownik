package pl.lukaszpeciak.towarownik

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorUserMessageTextTest {
    @Test fun preservesExactSkuQuantityLinesAndPolishPunctuation() {
        val quote = """
w ofercie wpisałem poniższe czy się zgadza?
60MGP-1zp — 50 szt.
60MGPB-1zp — 8 szt.
60MWP-1 — 6 szt.
60MWP-2 — 2 szt.
60MWP-3 — 6 szt.
60MWP-6 — 1 szt.
60MWP-4 — 1 szt.
60MRTO-3 — 11 szt.
60MRTO-2 — 18 szt.
60MRTO-1 — 13 szt.""".trimIndent()
        assertEquals(11, quote.lines().size)
        assertEquals(quote, canonicalAdvisorUserMessage(quote))
        assertEquals("60MRTO-1 — 13 szt.", canonicalAdvisorUserMessage(quote).lines().last())
    }

    @Test fun canonicalLfRetainsBlankLinesTabsIndentationAndTrailingLine() {
        val expected = "  • EAN 0001234567890\t× 2\n\n\t- 30×70 cm\n  Zażółć gęślą jaźń\n"
        for (input in listOf(expected, expected.replace("\n", "\r\n"), expected.replace("\n", "\r"))) {
            assertEquals(expected, canonicalAdvisorUserMessage(input))
        }
        assertEquals(expected, canonicalAdvisorUserMessage(expected))
    }

    @Test fun invalidNonLayoutIsoControlsSanitizedWithoutTrimmingUserRows() {
        val original = "  A\tB\nC\u0000\u0001D\u007fE\r\nŻ\t00001234\n"
        val expected = "  A\tB\nC  D E\nŻ\t00001234\n"
        assertEquals(expected, canonicalAdvisorUserMessage(original))
        assertTrue(canonicalAdvisorUserMessage("  \r\n\t ").isBlank())
    }

    @Test fun singleLineContentIsUnchanged() {
        for (value in listOf("MBN116E/HAG", "Sprawdź MBN116E u nas", "  OBIK 3496072  ")) {
            assertEquals(value, canonicalAdvisorUserMessage(value))
        }
    }
}
