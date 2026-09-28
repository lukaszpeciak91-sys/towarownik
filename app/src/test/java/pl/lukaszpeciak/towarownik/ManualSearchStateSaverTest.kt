package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER

class ManualSearchStateSaverTest {
    @Test
    fun `product store 074 round trips with verified metadata`() {
        val original = ManualSearchUiState.Product(
            VerifiedProductUiModel(
                name = "Product 074",
                obik = "1234567",
                grossPrice = BigDecimal("29.99"),
                stock = 3,
                productUrl = "https://www.obi.pl/p/1234567/trusted",
                verifiedAt = 1_700_000_000_000L,
                storeNumber = "074",
            ),
        )

        val restored = decodeManualSearchState(
            encodeManualSearchState(original),
        ) as ManualSearchUiState.Product

        assertEquals("074", restored.item.storeNumber)
        assertEquals(3, restored.item.stock)
        assertEquals(
            BigDecimal("29.99"),
            restored.item.grossPrice,
        )
        assertEquals(
            "https://www.obi.pl/p/1234567/trusted",
            restored.item.productUrl,
        )
        assertEquals(
            1_700_000_000_000L,
            restored.item.verifiedAt,
        )
    }

    @Test
    fun `product store 075 preserves zero stock null price and url`() {
        val original = ManualSearchUiState.Product(
            VerifiedProductUiModel(
                name = "Product 075",
                obik = "7654321",
                grossPrice = null,
                stock = 0,
                productUrl = "https://www.obi.pl/p/7654321/trusted",
                verifiedAt = null,
                storeNumber = "075",
            ),
        )

        val restored = decodeManualSearchState(
            encodeManualSearchState(original),
        ) as ManualSearchUiState.Product

        assertEquals("075", restored.item.storeNumber)
        assertEquals(0, restored.item.stock)
        assertNull(restored.item.grossPrice)
        assertEquals(
            "https://www.obi.pl/p/7654321/trusted",
            restored.item.productUrl,
        )
        assertNull(restored.item.verifiedAt)
    }

    @Test
    fun `product preserves null stock and null price`() {
        val original = ManualSearchUiState.Product(
            VerifiedProductUiModel(
                name = "Unknown store facts",
                obik = "1111111",
                grossPrice = null,
                stock = null,
                productUrl = "https://www.obi.pl/p/1111111/trusted",
                storeNumber = "074",
            ),
        )

        val restored = decodeManualSearchState(
            encodeManualSearchState(original),
        ) as ManualSearchUiState.Product

        assertEquals("074", restored.item.storeNumber)
        assertNull(restored.item.stock)
        assertNull(restored.item.grossPrice)
    }

    @Test
    fun `legacy product without store restores existing default safely`() {
        val restored = decodeManualSearchState(
            """
            {
              "type":"product",
              "name":"Legacy",
              "obik":"2222222",
              "price":null,
              "stock":0,
              "url":"https://www.obi.pl/p/2222222/trusted"
            }
            """.trimIndent(),
        ) as ManualSearchUiState.Product

        assertEquals(
            DEFAULT_OBI_STORE_NUMBER,
            restored.item.storeNumber,
        )
        assertEquals(0, restored.item.stock)
        assertNull(restored.item.grossPrice)
        assertEquals(
            "https://www.obi.pl/p/2222222/trusted",
            restored.item.productUrl,
        )
    }
}
