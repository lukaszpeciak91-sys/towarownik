package pl.lukaszpeciak.towarownik.product.provider

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.ManualProductSearchResult
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchCandidate
import pl.lukaszpeciak.towarownik.product.ProductSearchResult

class ProductProviderTest {
    @Test
    fun `product ref preserves provider ownership`() {
        val ref = ProductRef(
            providerId = ProviderId("kwant-example"),
            productId = "ABC-123",
        )

        assertEquals("kwant-example", ref.providerId.value)
        assertEquals("ABC-123", ref.productId)
    }

    @Test
    fun `default working profile represents existing OBI store`() {
        assertEquals("obi-pl", DEFAULT_WORKING_PROFILE.providerId.value)
        assertEquals("075", DEFAULT_WORKING_PROFILE.branchId.value)
    }

    @Test
    fun `OBI provider exposes existing supported branches including 075`() {
        val branches = ObiProductProvider().branches()
            as ProviderBranchResult.Available

        assertTrue(
            branches.branches.any {
                it.branchId == BranchId("075")
            },
        )
    }

    @Test
    fun `OBI provider delegates existing OBIK EAN and text searches unchanged`() {
        val seenQueries = mutableListOf<String>()
        val provider = ObiProductProvider(
            boundedSearch = { query ->
                seenQueries += query
                ProductSearchResult.Candidates(
                    items = listOf(
                        ProductSearchCandidate(
                            obik = "3496072",
                            name = "Synthetic",
                        ),
                    ),
                    reportedTotalCount = 1,
                )
            },
            manualSearch = {
                error("manual search must not be used")
            },
            lookupObik = { _, _ ->
                error("lookup must not be used")
            },
        )

        listOf(
            "3496072",
            "5901234123457",
            "wiertarka udarowa",
        ).forEach { query ->
            val result = provider.search(
                query = query,
                maxResults = 5,
            )
            assertTrue(result is ProviderSearchResult.Candidates)
            val candidate =
                (result as ProviderSearchResult.Candidates)
                    .items
                    .single()
            assertEquals(OBI_PROVIDER_ID, candidate.ref.providerId)
            assertEquals("3496072", candidate.ref.productId)
        }

        assertEquals(
            listOf(
                "3496072",
                "5901234123457",
                "wiertarka udarowa",
            ),
            seenQueries,
        )
    }

    @Test
    fun `OBI provider delegates expanded search to existing manual search path`() {
        var manualQuery: String? = null
        val provider = ObiProductProvider(
            boundedSearch = {
                error("bounded search must not be used")
            },
            manualSearch = { query ->
                manualQuery = query
                ManualProductSearchResult.Candidates(
                    items = (1..8).map { index ->
                        ProductSearchCandidate(
                            obik = (3_000_000 + index).toString(),
                            name = "Product $index",
                        )
                    },
                    reportedTotalCount = 27,
                )
            },
            lookupObik = { _, _ ->
                error("lookup must not be used")
            },
        )

        val result = provider.search(
            query = "czarne trytytki",
            maxResults = 8,
        ) as ProviderSearchResult.Candidates

        assertEquals("czarne trytytki", manualQuery)
        assertEquals(8, result.items.size)
        assertEquals(27, result.reportedTotalCount)
        assertTrue(
            result.items.all {
                it.ref.providerId == OBI_PROVIDER_ID
            },
        )
    }

    @Test
    fun `OBI provider passes branch id to exact lookup unchanged`() {
        var seenObik: String? = null
        var seenStore: String? = null
        val provider = ObiProductProvider(
            boundedSearch = {
                error("search must not be used")
            },
            manualSearch = {
                error("manual search must not be used")
            },
            lookupObik = { obik, storeNumber ->
                seenObik = obik
                seenStore = storeNumber
                ProductLookupResult.Found(
                    LocalProduct(
                        obik = obik,
                        name = "Synthetic",
                        stock = 4,
                        grossPrice = BigDecimal("19.99"),
                        productUrl =
                            "https://www.obi.pl/p/$obik/synthetic",
                        ean = "5901234123457",
                        storeNumber = storeNumber,
                    ),
                )
            },
        )
        val ref = ProductRef(
            providerId = OBI_PROVIDER_ID,
            productId = "3496072",
        )

        val result = provider.lookup(
            ref = ref,
            branchId = BranchId("074"),
        ) as ProviderLookupResult.Found

        assertEquals("3496072", seenObik)
        assertEquals("074", seenStore)
        assertEquals(ref, result.product.ref)
        assertEquals(BranchId("074"), result.product.branchId)
        assertEquals(4, result.product.stock)
        assertEquals(BigDecimal("19.99"), result.product.grossPrice)
    }

    @Test
    fun `OBI provider rejects product ref owned by another provider before lookup`() {
        var lookupCalls = 0
        val provider = ObiProductProvider(
            boundedSearch = {
                error("search must not be used")
            },
            manualSearch = {
                error("manual search must not be used")
            },
            lookupObik = { _, _ ->
                lookupCalls += 1
                error("must not be called")
            },
        )
        val ref = ProductRef(
            providerId = ProviderId("other-provider"),
            productId = "3496072",
        )

        assertEquals(
            ProviderLookupResult.WrongProvider(ref),
            provider.lookup(
                ref = ref,
                branchId = BranchId("075"),
            ),
        )
        assertEquals(0, lookupCalls)
    }

    @Test
    fun `provider registry resolves OBI and unknown provider never falls back`() {
        val obi = ObiProductProvider(
            boundedSearch = {
                ProductSearchResult.NotFound
            },
            manualSearch = {
                ManualProductSearchResult.NotFound
            },
            lookupObik = { obik, _ ->
                ProductLookupResult.InvalidObik(obik)
            },
        )
        val registry = ProductProviderRegistry(
            listOf(obi),
        )

        assertSame(
            obi,
            registry.resolve(OBI_PROVIDER_ID),
        )

        val unknown = ProviderId("kwant-not-installed")
        val failure = assertThrows(
            UnknownProductProviderException::class.java,
        ) {
            registry.resolve(unknown)
        }
        assertEquals(unknown, failure.providerId)
    }

    @Test
    fun `production registry still resolves existing OBI provider`() {
        val resolved =
            ProductProviderRegistry.production()
                .resolve(OBI_PROVIDER_ID)

        assertEquals(OBI_PROVIDER_ID, resolved.providerId)
    }
}
