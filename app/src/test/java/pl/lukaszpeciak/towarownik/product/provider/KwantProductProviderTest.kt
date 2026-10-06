package pl.lukaszpeciak.towarownik.product.provider

import java.math.BigDecimal
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
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
    fun `HTTP search uses real KWANT frontend search API contract`() {
        var requestedUrl: String? = null
        var requestedMethod: String? = null
        var requestedBody: String? = null
        var requestedContentType: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestedUrl = request.url.toString()
                requestedMethod = request.method
                requestedContentType = request.body?.contentType()?.toString()
                requestedBody = Buffer().also { buffer ->
                    request.body?.writeTo(buffer)
                }.readUtf8()
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        SEARCH_DATA.toResponseBody(
                            "application/json".toMediaType(),
                        ),
                    )
                    .build()
            }
            .build()

        val result = KwantHttpFrontendClient(client)
            .fetchSearch("MBN116E") as KwantFrontendResult.Success

        assertEquals(SEARCH_DATA, result.html)
        assertEquals(
            "https://services.kwant.net.pl/api/front/search-engine/page",
            requestedUrl,
        )
        assertEquals("POST", requestedMethod)
        assertTrue(
            requestedContentType?.startsWith("application/json") == true,
        )
        assertEquals(
            """{"q":"MBN116E","page":1,"limit":12,"tags":"not-logged-in,desktop"}""",
            requestedBody,
        )
    }

    @Test
    fun `HTTP selected branch stock uses current product depstock contract`() {
        var requestedUrl: String? = null
        var requestedMethod: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestedUrl = request.url.toString()
                requestedMethod = request.method
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        CURRENT_PRODUCT_DATA.toResponseBody(
                            "application/json".toMediaType(),
                        ),
                    )
                    .build()
            }
            .build()

        val result = KwantHttpFrontendClient(client)
            .fetchCurrentProductStock(
                productId = "580",
                departmentStockId = 205,
            ) as KwantFrontendResult.Success

        assertEquals(CURRENT_PRODUCT_DATA, result.html)
        assertEquals(
            "https://services.kwant.net.pl/api/front/products/580/current" +
                "?depstock=205",
            requestedUrl,
        )
        assertEquals("GET", requestedMethod)
    }

    @Test
    fun `empty or malformed KWANT hits fail closed as not found`() {
        listOf(
            """{"hits":[]}""",
            """{"hits":[{"id":580,"code":"MBN116E/HAG"}]}""",
            """{"hits":[{"slug":"product-580","code":"MBN116E/HAG"}]}""",
            """{"unexpected":[]}""",
            """not-json""",
        ).forEach { payload ->
            val provider = KwantProductProvider(
                frontend = FakeFrontend(searchData = payload),
            )

            assertEquals(
                ProviderSearchResult.NotFound,
                provider.search("MBN116E", 5),
            )
        }
    }

    @Test
    fun `KWANT not found never falls back to OBI`() {
        val frontend = FakeFrontend(searchData = """{"hits":[]}""")
        val provider = KwantProductProvider(frontend = frontend)

        assertEquals(
            ProviderSearchResult.NotFound,
            provider.search("MBN116E", 5),
        )
        assertEquals(listOf("MBN116E"), frontend.searchQueries)
        assertNull(frontend.lastProductId)
        assertNull(frontend.lastProductUrl)
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
        val frontend = FakeFrontend(productHtml = productHtml())
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
        assertEquals("580", frontend.lastCurrentProductId)
        assertEquals(205, frontend.lastCurrentDepartmentStockId)
    }

    @Test
    fun `fresh exact lookup resolves directly by numeric product route without search`() {
        val frontend = FakeFrontend(productHtml = productHtml())
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
        assertEquals("580", frontend.lastCurrentProductId)
        assertEquals(205, frontend.lastCurrentDepartmentStockId)
        assertEquals(
            NOWY_SACZ.departmentCookieJson(),
            frontend.lastDepartmentCookie,
        )
    }

    @Test
    fun `lookup scope resolves branch directory once for a candidate batch`() {
        val frontend = FakeFrontend(productHtml = productHtml())
        val provider = KwantProductProvider(frontend = frontend)
        val scope = provider.openLookupScope(BranchId("205"))
            as ProviderLookupScopeResult.Available

        scope.lookup(ProductRef(KWANT_PROVIDER_ID, "580"))
        scope.lookup(ProductRef(KWANT_PROVIDER_ID, "581"))

        assertEquals(1, frontend.branchDirectoryCalls)
    }

    @Test
    fun `selected branch stock parses from real current product shape`() {
        val stock = KwantFrontendParser().parseSelectedBranchStock(
            payload = currentProductData(stock = 362),
            expectedProductId = "580",
            expectedDepartmentStockId = 205,
        )

        assertEquals(362, stock)
    }

    @Test
    fun `zero selected branch stock remains zero`() {
        val stock = KwantFrontendParser().parseSelectedBranchStock(
            payload = currentProductData(stock = 0),
            expectedProductId = "580",
            expectedDepartmentStockId = 205,
        )

        assertEquals(0, stock)
    }

    @Test
    fun `missing or mismatched selected branch stock remains unknown`() {
        val parser = KwantFrontendParser()

        assertNull(
            parser.parseSelectedBranchStock(
                payload = currentProductData(stock = null),
                expectedProductId = "580",
                expectedDepartmentStockId = 205,
            ),
        )
        assertNull(
            parser.parseSelectedBranchStock(
                payload = currentProductData(
                    stock = 362,
                    departmentStockId = 216,
                ),
                expectedProductId = "580",
                expectedDepartmentStockId = 205,
            ),
        )
        assertNull(
            parser.parseSelectedBranchStock(
                payload = currentProductData(
                    stock = 362,
                    productId = 577,
                ),
                expectedProductId = "580",
                expectedDepartmentStockId = 205,
            ),
        )
    }

    @Test
    fun `selected and central stock stay distinct`() {
        val parser = KwantFrontendParser()
        val product = parser.parseProduct(
            productHtml(), PRODUCT_URL, "580", NOWY_SACZ,
        )
        val selectedStock = parser.parseSelectedBranchStock(
            payload = currentProductData(stock = 362),
            expectedProductId = "580",
            expectedDepartmentStockId = 205,
        )

        assertNull(product?.stock)
        assertEquals(10113, product?.centralStock)
        assertEquals(362, selectedStock)
        assertTrue(selectedStock != product?.centralStock)
    }

    @Test
    fun `missing central stock remains unknown independently`() {
        val product = KwantFrontendParser().parseProduct(
            productHtml(centralStock = null),
            PRODUCT_URL,
            "580",
            NOWY_SACZ,
        )

        assertNull(product?.stock)
        assertNull(product?.centralStock)
    }

    @Test
    fun `recommended product stock cannot contaminate main product`() {
        val parser = KwantFrontendParser()
        val selected = parser.parseSelectedBranchStock(
            payload = currentProductData(
                stock = 362,
                recommendationStock = 139,
            ),
            expectedProductId = "580",
            expectedDepartmentStockId = 205,
        )
        val missingMain = parser.parseSelectedBranchStock(
            payload = currentProductData(
                stock = null,
                recommendationStock = 139,
            ),
            expectedProductId = "580",
            expectedDepartmentStockId = 205,
        )

        assertEquals(362, selected)
        assertNull(missingMain)
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
    fun `KWANT URL policy requires exact trusted hosts and routes`() {
        assertTrue(
            KwantUrlPolicy.isTrustedProductUrl(PRODUCT_URL),
        )
        assertTrue(
            KwantUrlPolicy.isTrustedSearchServiceUrl(
                "https://services.kwant.net.pl/api/front/search-engine/page",
            ),
        )
        assertTrue(
            KwantUrlPolicy.isTrustedCurrentProductUrl(
                "https://services.kwant.net.pl/api/front/products/580/current" +
                    "?depstock=205",
            ),
        )
        assertTrue(
            !KwantUrlPolicy.isTrustedCurrentProductUrl(
                "https://services.kwant.net.pl/api/front/products/580/current",
            ),
        )
        assertTrue(
            !KwantUrlPolicy.isTrustedCurrentProductUrl(
                "https://services.kwant.net.pl/api/front/products/580/current" +
                    "?depstock=205&other=1",
            ),
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
        assertTrue(
            !KwantUrlPolicy.isTrustedSearchServiceUrl(
                "https://services.kwant.net.pl.evil.example/api/front/search-engine/page",
            ),
        )
        assertTrue(
            !KwantUrlPolicy.isTrustedSearchServiceUrl(
                "https://services.kwant.net.pl/api/front/products/prices/580",
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
        assertEquals(KWANT_PROVIDER_ID, result.product.ref.providerId)
        assertEquals("580", result.product.ref.productId)
        assertEquals(BranchId("205"), result.product.branchId)
        assertEquals(362, result.product.stock)
        assertEquals(10113, result.product.centralStock)
        assertEquals(BigDecimal("14.55"), result.product.grossPrice)
        assertEquals(ProviderPriceScope.ONLINE, result.product.priceScope)
        assertEquals("MBN116E/HAG", result.product.articleNumber)
        assertEquals("3250614312762", result.product.ean)
        assertEquals(PRODUCT_URL, result.product.productUrl)
        assertEquals("HAGER", result.product.brand)
    }

    private fun searchRef(provider: KwantProductProvider): ProductRef =
        (
            provider.search("MBN116E", 5)
                as ProviderSearchResult.Candidates
        ).items.first().ref

    private class FakeFrontend(
        private val productHtml: String = productHtml(),
        private val searchData: String = SEARCH_DATA,
        private val currentProductData: String = CURRENT_PRODUCT_DATA,
    ) : KwantFrontendClient {
        val searchQueries = mutableListOf<String>()
        var lastProductId: String? = null
        var lastProductUrl: String? = null
        var lastDepartmentCookie: String? = null
        var lastCurrentProductId: String? = null
        var lastCurrentDepartmentStockId: Int? = null
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
                html = searchData,
                finalUrl =
                    "https://services.kwant.net.pl/api/front/" +
                        "search-engine/page",
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

        override fun fetchCurrentProductStock(
            productId: String,
            departmentStockId: Int,
        ): KwantFrontendResult {
            lastCurrentProductId = productId
            lastCurrentDepartmentStockId = departmentStockId
            return KwantFrontendResult.Success(
                html = currentProductData,
                finalUrl =
                    "https://services.kwant.net.pl/api/front/products/" +
                        productId + "/current?depstock=" + departmentStockId,
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

        val SEARCH_DATA =
            """
            {
              "found": 2,
              "out_of": 706,
              "page": 1,
              "search_time_ms": 4,
              "hits": [
                {
                  "id": 580,
                  "stock_id": 580,
                  "stockId": 580,
                  "slug": "wylacznik-nadpradowy-b16-a-1p-6ka-mbn116e-hager-580",
                  "code": "MBN116E/HAG",
                  "ean": "3250614312762",
                  "name": "$PRODUCT_NAME"
                },
                {
                  "id": 677,
                  "slug": "rozlacznik-sbn490-hager-677",
                  "code": "SBN490/HAG",
                  "ean": "5900000000000",
                  "name": "Rozłącznik SBN490 HAGER"
                }
              ],
              "facets": {},
              "categoryTree": []
            }
            """.trimIndent()

        val CURRENT_PRODUCT_DATA = currentProductData(stock = 362)

        fun currentProductData(
            stock: Int?,
            productId: Int = 580,
            departmentStockId: Int = 205,
            recommendationStock: Int? = null,
        ): String {
            val departmentStock = stock?.let {
                ""","department_stock":{"department_id":$departmentStockId,"name":"Nowy Sącz","stock":$it,"stock_num":$it}"""
            }.orEmpty()
            val recommendation = recommendationStock?.let {
                ""","related":[{"product_id":577,"department_stock":{"department_id":205,"name":"Nowy Sącz","stock":$it,"stock_num":$it}}]"""
            }.orEmpty()
            return """
                {
                  "product_id": $productId,
                  "stock": 10113,
                  "gross_price": 14.55,
                  "unit": "szt."
                  $departmentStock
                  $recommendation
                }
            """.trimIndent()
        }

        fun productHtml(
            centralStock: String? = "10113",
        ): String {
            val centralStockField = centralStock
                ?.let { ""","stock":$it""" }
                .orEmpty()
            return nextData(
                """
                {
                  "product": {
                    "id": 580,
                    "name": "$PRODUCT_NAME",
                    "code": "MBN116E/HAG",
                    "ean": "3250614312762",
                    "gross_price": 14.55$centralStockField,
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
            ) +
                """<button data-testid="add-to-cart-button-580"></button>"""
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
