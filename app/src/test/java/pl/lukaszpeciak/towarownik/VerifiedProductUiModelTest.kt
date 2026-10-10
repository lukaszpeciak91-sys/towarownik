package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope

class VerifiedProductUiModelTest {
    @Test
    fun `restored KWANT card keeps online price scope from provider identity`() {
        val ui = VerifiedProductSnapshot(
            obik = "580",
            name = "Wyłącznik",
            stock = 140,
            centralStock = 5918,
            grossPrice = BigDecimal("14.55"),
            productUrl = "https://kwant.net.pl/produkt/test-580",
            verifiedAt = 1L,
            storeNumber = "205",
            providerId = "kwant-pl",
            productId = "580",
            branchId = "205",
            articleNumber = "MBN116E/HAG",
        ).toVerifiedProductUiModel()

        assertEquals(ProviderPriceScope.ONLINE, ui.priceScope)
        assertEquals("kwant-pl", ui.providerId)
        assertEquals("205", ui.branchId)
        assertEquals("580", ui.productId)
        assertEquals("MBN116E/HAG", ui.articleNumber)
        assertEquals(140, ui.stock)
        assertEquals(5918, ui.centralStock)
        assertEquals(BigDecimal("14.55"), ui.grossPrice)
    }

    @Test
    fun `legacy OBI card keeps branch price scope`() {
        val ui = VerifiedProductSnapshot(
            obik = "3496072",
            name = "OBI product",
            stock = 7,
            grossPrice = BigDecimal("12.99"),
            productUrl = "https://www.obi.pl/p/3496072/test",
            verifiedAt = 1L,
            storeNumber = "075",
        ).toVerifiedProductUiModel()

        assertEquals(ProviderPriceScope.BRANCH, ui.priceScope)
        assertEquals("obi-pl", ui.providerId)
        assertEquals("075", ui.branchId)
        assertEquals("3496072", ui.productId)
        assertEquals(null, ui.centralStock)
    }
    @Test
    fun verifiedLocalPrimaryImagePreservedForObiAndKwant() {
        for (provider in listOf("obi-pl", "kwant-pl")) {
            val url = if (provider == "obi-pl") {
                "https://bilder.obi.pl/fixture.jpg"
            } else {
                "https://kwant.net.pl/media/fixture.jpg"
            }
            val snapshot = VerifiedProductSnapshot(
                obik = "3496072",
                name = "Fixture",
                stock = 4,
                grossPrice = null,
                productUrl = if (provider == "obi-pl") {
                    "https://www.obi.pl/p/3496072"
                } else {
                    "https://kwant.net.pl/produkt/3496072"
                },
                verifiedAt = 1L,
                storeNumber = if (provider == "obi-pl") "075" else "205",
                providerId = provider,
                primaryImageUrl = url,
            )
            val ui = snapshot.toVerifiedProductUiModel()
            assertEquals(provider, ui.providerId)
            assertEquals(url, ui.primaryImageUrl)
            assertEquals(snapshot.productUrl, verifiedProductOpenUrl(ui))
            assertEquals(null, snapshot.copy(primaryImageUrl = null)
                .toVerifiedProductUiModel().primaryImageUrl)
        }
    }

}
