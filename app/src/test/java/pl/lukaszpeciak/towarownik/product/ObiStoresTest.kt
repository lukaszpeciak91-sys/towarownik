package pl.lukaszpeciak.towarownik.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObiStoresTest {
    @Test
    fun `default store remains 075`() {
        assertEquals("075", DEFAULT_OBI_STORE_NUMBER)
        assertTrue(isSupportedObiStoreNumber(DEFAULT_OBI_STORE_NUMBER))
    }

    @Test
    fun `confirmed stores are accepted and arbitrary values are rejected`() {
        listOf("072", "074", "075", "078").forEach {
            assertTrue(isSupportedObiStoreNumber(it))
        }

        listOf("999", "000", "75", "0075", "abc").forEach {
            assertFalse(isSupportedObiStoreNumber(it))
        }
    }

    @Test
    fun `store token matching uses exact digit boundaries`() {
        assertEquals(
            setOf("074", "075"),
            supportedObiStoreTokens("Porównaj 074 i 075"),
        )
        assertEquals(
            emptySet<String>(),
            supportedObiStoreTokens("Sprawdź 1074 i 999"),
        )
        assertEquals(
            setOf("078"),
            supportedObiStoreTokens("078, proszę"),
        )
    }

    @Test
    fun `allowlist contains only unique exact three digit values`() {
        assertEquals(62, SUPPORTED_OBI_STORE_NUMBERS.size)
        assertEquals(
            SUPPORTED_OBI_STORE_NUMBERS.size,
            SUPPORTED_OBI_STORE_NUMBERS.distinct().size,
        )
        assertTrue(
            SUPPORTED_OBI_STORE_NUMBERS.all {
                Regex("""\d{3}""").matches(it)
            },
        )
    }
}
