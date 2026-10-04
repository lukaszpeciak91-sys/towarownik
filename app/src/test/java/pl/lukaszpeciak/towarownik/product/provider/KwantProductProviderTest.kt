package pl.lukaszpeciak.towarownik.product.provider

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class KwantProductProviderTest {
    @Test
    fun `department cookie is deterministic from public branch metadata`() {
        assertEquals(
            """{"department_stock_id":205,"department_stock_name":"Nowy Sącz","department_stock_postcode":"33-300","department_stock_street":"Tarnowska 149"}""",
            NOWY_SACZ.departmentCookieJson(),
        )
    }

    @Test
    fun `public branch directory resolves Nowy Sacz 205 fixture`() {
        val branches = KwantFrontendParser().parseBranches(BRANCH_HTML)

        assertTrue(branches.contains(NOWY_SACZ))
        assertEquals(BranchId("205"), NOWY_SACZ.branchId)
    }

    @Test
    fun `public provider branches include selectable Nowy Sacz 205 with address`() {
        val provider = KwantProductProvider(frontend = FakeFrontend())

        val result = provider.branches()
            as ProviderBranchResult.Available
        val branch = result.branches.single {
            it.branchId == BranchId("205")
        }

        assertEquals("Nowy Sącz", branch.name)
        assertEquals("33-300 Tarnowska 149", branch.address)
    }

    @Test
    fun `article EAN and text searches preserve stable KWANT product ref`() {
        val frontend = FakeFrontend()
        val provider = KwantProductProvider(frontend = frontend)

        listOf(
            "MBN116E",
            "3250614312762",
            "wyłącznik nadprądowy B16 Hager",
        ).forEach { query ->
            val result = provider.search(query, 5)
                as ProviderSearchResult.Candidates
            val candidate = result.items.first()

            assertEquals(KWANT_PROVIDER_ID, candidate.ref.providerId)
            assertEquals("580", candidate.ref.productId)
            assertEquals(PRODUCT_NAME, candidate.name)
            assertNull(result.reportedTotalCount)
        }

        assertEquals(
            listOf(
                "MBN116E",
                "3250614312762",
                "wyłącznik nadprądowy B16 Hager",
            ),
            frontend.searchQueries,
        )
    }

    @Test
    fun `search then lookup may use cached canonical product URL`() {
        val frontend = FakeFrontend(productHtml = productHtml("140"))
        val provider = KwantProductProvider(frontend = frontend)
        val ref = searchRef(provider)

        val result = provider.lookup(ref, BranchId("205"))
            as ProviderLookupResult.Found

        assertProductFixture(result)
        assertEquals(
            NOWY_SACZ.departmentCookieJson(),
            frontend.lastDepartmentCookie,
        )
        assertEquals(PRODUCT_URL, frontend.lastProductUrl)
        assertNull(frontend.lastProductId)
    }

    @Test
    fun `fresh exact lookup resolves directly by numeric product route without search`() {
        val frontend = FakeFrontend(productHtml = productHtml("140"))
        val provider = KwantProductProvider(frontend = frontend)
        val ref = ProductRef(
            providerId = KWANT_PROVIDER_ID,
            productId = "580",
        )

        val result = provider.lookup(ref, BranchId("205"))
            as ProviderLookupResult.Found

        assertProductFixture(result)
        assertTrue(frontend.searchQueries.isEmpty())
        assertEquals("580", frontend.lastProductId)
        assertNull(frontend.lastProductUrl)
        assertEquals(
            NOWY_SACZ.departmentCookieJson(),
            frontend.lastDepartmentCookie,
        )
    }

    @Test
    fun `lookup scope resolves branch directory once for a candidate batch`() {
        val frontend = FakeFrontend(productHtml = productHtml("140"))
        val provider = KwantProductProvider(frontend = frontend)
        val scope = provider.openLookupScope(BranchId("205"))
            as ProviderLookupScopeResult.Available

        scope.lookup(ProductRef(KWANT_PROVIDER_ID, "580"))
        scope.lookup(ProductRef(KWANT_PROVIDER_ID, "581"))

        assertEquals(1, frontend.branchDirectoryCalls)
    }

    @Test
    fun `zero selected branch stock remains zero`() {
        val product = KwantFrontendParser().parseProduct(
            html = productHtml("0"),
            finalUrl = PRODUCT_URL,
            expectedProductId = "580",
            branch = NOWY_SACZ,
        )

        assertEquals(0, product?.stock)
    }

    @Test
    fun `missing selected branch stock remains unknown`() {
        val product = KwantFrontendParser().parseProduct(
            html = productHtml(null),
            finalUrl = PRODUCT_URL,
            expectedProductId = "580",
            branch = NOWY_SACZ,
        )

        assertNull(product?.stock)
    }

    @Test
    fun `invalid KWANT branch never falls back`() {
        val frontend = FakeFrontend()
        val provider = KwantProductProvider(frontend = frontend)
        val ref = searchRef(provider)

        val result = provider.lookup(ref, BranchId("999999"))

        assertEquals(
            ProviderLookupResult.InvalidBranch(BranchId("999999")),
            result,
        )
        assertNull(frontend.lastDepartmentCookie)
    }

    @Test
    fun `KWANT rejects product ref owned by another provider`() {
        val frontend = FakeFrontend()
        val provider = KwantProductProvider(frontend = frontend)
        val ref = ProductRef(
            providerId = OBI_PROVIDER_ID,
            productId = "580",
        )

        assertEquals(
            ProviderLookupResult.WrongProvider(ref),
            provider.lookup(ref, BranchId("205")),
        )
        assertTrue(frontend.searchQueries.isEmpty())
    }

    @Test
    fun `KWANT URL policy requires exact https host and product route`() {
        assertTrue(
            KwantUrlPolicy.isTrustedProductUrl(PRODUCT_URL),
        )
        assertTrue(
            !KwantUrlPolicy.isTrustedProductUrl(
                "https://kwant.net.pl.evil.example/produkt/test-580",
            ),
        )
        assertTrue(
            !KwantUrlPolicy.isTrustedProductUrl(
                "http://kwant.net.pl/produkt/test-580",
            ),
        )
        assertTrue(
            !KwantUrlPolicy.isTrustedProductUrl(
                "https://kwant.net.pl/kategorie/test-580",
            ),
        )
    }

    @Test
    fun `registry resolves both production providers`() {
        val registry = ProductProviderRegistry.production()

        assertEquals(
            OBI_PROVIDER_ID,
            registry.resolve(OBI_PROVIDER_ID).providerId,
        )
        assertEquals(
            KWANT_PROVIDER_ID,
            registry.resolve(KWANT_PROVIDER_ID).providerId,
        )
    }

    @Test
    fun `explicit registry resolves OBI and KWANT instances`() {
        val obi = ObiProductProvider()
        val kwant = KwantProductProvider(frontend = FakeFrontend())
        val registry = ProductProviderRegistry(listOf(obi, kwant))

        assertSame(obi, registry.resolve(OBI_PROVIDER_ID))
        assertSame(kwant, registry.resolve(KWANT_PROVIDER_ID))
    }

    private fun assertProductFixture(result: ProviderLookupResult.Found) {
        assertEquals(BranchId("205"), result.product.branchId)
        assertEquals(140, result.product.stock)
        assertEquals(BigDecimal("14.55"), result.product.grossPrice)
        assertEquals(ProviderPriceScope.ONLINE, result.product.priceScope)
        assertEquals("MBN116E/HAG", result.product.articleNumber)
        assertEquals("3250614312762", result.product.ean)
        assertEquals("HAGER", result.product.brand)
    }

    private fun searchRef(provider: KwantProductProvider): ProductRef =
        (
            provider.search("MBN116E", 5)
                as ProviderSearchResult.Candidates
        ).items.first().ref

    private class FakeFrontend(
        private val productHtml: String = productHtml("140"),
    ) : KwantFrontendClient {
        val searchQueries = mutableListOf<String>()
        var lastProductId: String? = null
        var lastProductUrl: String? = null
        var lastDepartmentCookie: String? = null
        var branchDirectoryCalls: Int = 0

        override fun fetchBranchDirectory(): KwantFrontendResult {
            branchDirectoryCalls += 1
            return KwantFrontendResult.Success(
                html = BRANCH_HTML,
                finalUrl = "https://kwant.net.pl/lista-hurtowni-elektrycznych",
            )
        }

        override fun fetchSearch(query: String): KwantFrontendResult {
            check(query != "580") {
                "Numeric internal product ID search is not a supported KWANT contract"
            }
            searchQueries += query
            return KwantFrontendResult.Success(
                html = SEARCH_HTML,
                finalUrl =
                    "https://kwant.net.pl/wyniki-wyszukiwania?phrase=$query",
            )
        }

        override fun fetchProductById(
            productId: String,
            departmentCookieJson: String,
        ): KwantFrontendResult {
            lastProductId = productId
            lastDepartmentCookie = departmentCookieJson
            return KwantFrontendResult.Success(
                html = productHtml,
                finalUrl = PRODUCT_URL,
            )
        }

        override fun fetchProductByUrl(
            productUrl: String,
            departmentCookieJson: String,
        ): KwantFrontendResult {
            lastProductUrl = productUrl
            lastDepartmentCookie = departmentCookieJson
            return KwantFrontendResult.Success(
                html = productHtml,
                finalUrl = PRODUCT_URL,
            )
        }
    }

    private companion object {
        const val PRODUCT_NAME =
            "Wyłącznik nadprądowy B16 A 1P 6kA MBN116E HAGER"
        const val PRODUCT_URL =
            "https://kwant.net.pl/produkt/wylacznik-nadpradowy-b16-a-1p-6ka-mbn116e-hager-580"

        val NOWY_SACZ = KwantBranchMetadata(
            departmentStockId = 205,
            departmentStockName = "Nowy Sącz",
            departmentStockPostcode = "33-300",
            departmentStockStreet = "Tarnowska 149",
        )

        val BRANCH_HTML = nextData(
            """
            {
              "departments": {
                "list": [
                  {
                    "department_id": 216,
                    "name": "Białystok",
                    "postcode": "15-001",
                    "street": "Testowa 1"
                  },
                  {
                    "department_id": 205,
                    "name": "Nowy Sącz",
                    "postcode": "33-300",
                    "street": "Tarnowska 149"
                  }
                ]
              }
            }
            """,
        )

        val SEARCH_HTML = nextData(
            """
            {
              "categoriesFacetsProducts": [
                {
                  "categoryId": 530,
                  "list": [
                    {
                      "id": 580,
                      "slug": "wylacznik-nadpradowy-b16-a-1p-6ka-mbn116e-hager-580",
                      "code": "MBN116E/HAG",
                      "name": "$PRODUCT_NAME"
                    },
                    {
                      "id": 677,
                      "slug": "rozlacznik-sbn490-hager-677",
                      "code": "SBN490/HAG",
                      "name": "Rozłącznik SBN490 HAGER"
                    }
                  ]
                }
              ]
            }
            """,
        )

        fun productHtml(stock: String?): String {
            val stockMarkup = stock?.let {
                """<p>Nowy Sącz: <span>$it szt.</span></p>"""
            }.orEmpty()
            return nextData(
                """
                {
                  "product": {
                    "id": 580,
                    "name": "$PRODUCT_NAME",
                    "code": "MBN116E/HAG",
                    "ean": "3250614312762",
                    "gross_price": 14.55,
                    "producer": {"name": "HAGER"},
                    "description": "<p>Wyłącznik instalacyjny.</p>",
                    "main_image": {
                      "href": "https://cdn.kwant.net.pl/userdata/gfx/mbn116e.jpg"
                    },
                    "attributes": [
                      {"name": "Prąd znamionowy [A]", "value": "16A"}
                    ]
                  }
                }
                """,
            ) + stockMarkup
        }

        fun nextData(pageProps: String): String =
            """
            <html><body>
            <script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":$pageProps}}
            </script>
            </body></html>
            """.trimIndent()
    }
}
