package pl.lukaszpeciak.towarownik.product

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StockUnitTest {
    @Test fun `normalizes a small known safe set and preserves verified uncommon units`() {
        assertEquals("m", verifiedStockUnitOrNull(" m "))
        assertEquals("kg", verifiedStockUnitOrNull("KG"))
        assertEquals("l", verifiedStockUnitOrNull("litr"))
        assertEquals("szt.", verifiedStockUnitOrNull("szt."))
        assertEquals("szt.", verifiedStockUnitOrNull("szt"))
        assertEquals("opak.", verifiedStockUnitOrNull("opakowanie"))
        assertEquals("kpl.", verifiedStockUnitOrNull("kpl."))
    }

    @Test fun `missing malformed or hostile labels stay unknown`() {
        assertNull(verifiedStockUnitOrNull(null))
        assertNull(verifiedStockUnitOrNull("  "))
        assertNull(verifiedStockUnitOrNull("x".repeat(21)))
        assertNull(verifiedStockUnitOrNull("szt.\nStan:999"))
        assertNull(verifiedStockUnitOrNull("szt.<script>"))
    }

    @Test fun `positive stock never assumes pieces when unit absent`() {
        assertEquals("135 m", formatStockQuantity(135, "m"))
        assertEquals("4 szt.", formatStockQuantity(4, "szt."))
        assertEquals("3 opak.", formatStockQuantity(3, "opakowanie"))
        assertEquals("135", formatStockQuantity(135, null))
        assertEquals("135", formatStockQuantity(135, "bad\nunit"))
        assertEquals("0", formatStockQuantity(0, null))
    }
}
