package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.ProductLookupFailure
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchCandidate
import pl.lukaszpeciak.towarownik.product.ProductSearchResult
import pl.lukaszpeciak.towarownik.product.TechnicalFact

class FindObiProductsToolTest {
    @Test
    fun `exact seven digit OBIK bypasses text search`() = runBlocking {
        var searches = 0
        val lookedUp = mutableListOf<String>()
        val tool = tool(
            search = {
                searches += 1
                error("exact OBIK must not use search")
            },
            lookup = { obik, _ ->
                lookedUp += obik
                ProductLookupResult.Found(product(obik = obik))
            },
        )

        val result = tool.execute(
            arguments(query = "1234567", limit = 1),
        ) as AdvisorToolExecutionResult.Success

        assertEquals(0, searches)
        assertEquals(listOf("1234567"), lookedUp)
        assertEquals("1234567", result.result.products.single().obik)
    }

    @Test
    fun `OBIK label decoration still bypasses text search`() = runBlocking {
        listOf(
            "6165468",
            "OBIK 6165468",
            "OBIK: 6165468",
        ).forEach { query ->
            var searches = 0
            val lookedUp = mutableListOf<String>()
            val tool = tool(
                search = {
                    searches += 1
                    error("labeled exact OBIK must not use search")
                },
                lookup = { obik, _ ->
                    lookedUp += obik
                    ProductLookupResult.Found(product(obik = obik))
                },
            )

            val result = tool.execute(
                arguments(query = query, limit = 1),
            ) as AdvisorToolExecutionResult.Success

            assertEquals(query, 0, searches)
            assertEquals(query, listOf("6165468"), lookedUp)
            assertEquals(
                query,
                "6165468",
                result.result.products.single().obik,
            )
        }
    }

    @Test
    fun `seven digit number inside arbitrary prose does not use OBIK fast path`() = runBlocking {
        var searches = 0
        val lookedUp = mutableListOf<String>()
        val tool = tool(
            search = { query ->
                searches += 1
                assertEquals("Sprawdź produkt OBIK 6165468 proszę", query)
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate(
                            obik = "7654321",
                            name = "Search candidate",
                        ),
                    ),
                )
            },
            lookup = { obik, _ ->
                lookedUp += obik
                ProductLookupResult.Found(product(obik = obik))
            },
        )

        tool.execute(
            arguments(
                query = "Sprawdź produkt OBIK 6165468 proszę",
                limit = 1,
            ),
        )

        assertEquals(1, searches)
        assertEquals(listOf("7654321"), lookedUp)
    }

    @Test
    fun `exact OBIK lookup not found stays not found`() = runBlocking {
        val tool = tool(
            search = { error("exact OBIK must not use search") },
            lookup = { _, _ ->
                ProductLookupResult.Unavailable(
                    ProductLookupFailure.NOT_FOUND,
                    "synthetic",
                )
            },
        )

        val result = tool.execute(
            arguments(query = "1234567", limit = 1),
        ) as AdvisorToolExecutionResult.Success

        assertEquals(
            AdvisorQueryResultStatus.NOT_FOUND,
            result.result.results.single().status,
        )
    }

    @Test
    fun `exact OBIK network and data failures stay unavailable`() = runBlocking {
        listOf(
            ProductLookupFailure.NETWORK,
            ProductLookupFailure.DATA,
        ).forEach { failure ->
            val tool = tool(
                search = { error("exact OBIK must not use search") },
                lookup = { _, _ ->
                    ProductLookupResult.Unavailable(failure, "synthetic")
                },
            )

            val result = tool.execute(
                arguments(query = "1234567", limit = 1),
            ) as AdvisorToolExecutionResult.Success

            assertEquals(
                failure.name,
                AdvisorQueryResultStatus.UNAVAILABLE,
                result.result.results.single().status,
            )
        }
    }

    @Test
    fun `not found becomes verified empty products result`() = runBlocking {
        val tool = tool(
            search = { ProductSearchResult.NotFound },
            lookup = { _, _ -> error("lookup must not run") },
        )

        val result = tool.execute(arguments())

        assertEquals(
            AdvisorToolExecutionResult.Success(
                result = AdvisorVerifiedToolResult(
                    query = "klej",
                    products = emptyList(),
                ),
                snapshots = emptyList(),
            ),
            result,
        )
    }

    @Test
    fun `search unavailable is represented honestly inside its group`() = runBlocking {
        val tool = tool(
            search = {
                ProductSearchResult.Unavailable(
                    ProductLookupFailure.NETWORK,
                    "synthetic",
                )
            },
            lookup = { _, _ -> error("lookup must not run") },
        )

        val result = tool.execute(arguments()) as
            AdvisorToolExecutionResult.Success

        assertEquals(
            AdvisorQueryResultStatus.UNAVAILABLE,
            result.result.results.single().status,
        )
        assertTrue(result.result.results.single().products.isEmpty())
        assertTrue(result.snapshots.isEmpty())
    }

    @Test
    fun `candidate list invokes exact OBIK lookup and exact name is authoritative`() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate("1234567", "Candidate name"),
                    ),
                )
            },
            lookup = { obik, _ ->
                lookedUp += obik
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        name = "Exact product name",
                        stock = 4,
                        price = BigDecimal("19.99"),
                    ),
                )
            },
        )

        val result = tool.execute(arguments()) as AdvisorToolExecutionResult.Success

        assertEquals(listOf("1234567"), lookedUp)
        assertEquals("Exact product name", result.result.products.single().name)
    }

    @Test
    fun `rich verified facts propagate from exact LocalProduct into model context`() = runBlocking {
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate("1234567", "Candidate"),
                    ),
                )
            },
            lookup = { obik, _ ->
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        name = "Exact product",
                        brand = "Bosch",
                        shortDescription =
                            "Verified product-page description.",
                        technicalFacts = listOf(
                            TechnicalFact("Moc", "600 W"),
                            TechnicalFact(
                                "Maks. prędkość",
                                "3000 obr./min",
                            ),
                        ),
                    ),
                )
            },
        )

        val result = tool.execute(arguments()) as
            AdvisorToolExecutionResult.Success
        val verified = result.result.products.single()

        assertEquals("Bosch", verified.brand)
        assertEquals(
            "Verified product-page description.",
            verified.shortDescription,
        )
        assertEquals(
            listOf(
                AdvisorTechnicalFact("Moc", "600 W"),
                AdvisorTechnicalFact(
                    "Maks. prędkość",
                    "3000 obr./min",
                ),
            ),
            verified.technicalFacts,
        )
    }

    @Test
    fun `tool respects requested limit and searches sequential candidates`() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate("1234567", null),
                        ProductSearchCandidate("2345678", null),
                        ProductSearchCandidate("3456789", null),
                    ),
                )
            },
            lookup = { obik, _ ->
                lookedUp += obik
                ProductLookupResult.Found(
                    product(obik = obik),
                )
            },
        )

        val result = tool.execute(arguments(limit = 2)) as AdvisorToolExecutionResult.Success

        assertEquals(listOf("1234567", "2345678"), lookedUp)
        assertEquals(2, result.result.products.size)
    }

    @Test
    fun `advisor tool remains capped at five even when more candidates are supplied`() = runBlocking {
        val lookedUp = mutableListOf<String>()
        val candidates = (1..25).map { index ->
            ProductSearchCandidate(
                obik = (5_000_000 + index).toString(),
                name = "Synthetic $index",
            )
        }
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(candidates)
            },
            lookup = { obik, _ ->
                lookedUp += obik
                ProductLookupResult.Found(product(obik = obik))
            },
        )

        val result = tool.execute(arguments(limit = 5)) as AdvisorToolExecutionResult.Success

        assertEquals(5, lookedUp.size)
        assertEquals(5, result.result.products.size)
    }

    @Test
    fun `broad multi result search exposes local search more action when OBI reports more`() = runBlocking {
        val candidates = (1..5).map { index ->
            ProductSearchCandidate(
                obik = (6_100_000 + index).toString(),
                name = "Synthetic $index",
            )
        }
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    items = candidates,
                    reportedTotalCount = 27,
                )
            },
            lookup = { obik, storeNumber ->
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        storeNumber = storeNumber,
                    ),
                )
            },
        )

        val result = tool.execute(
            arguments(
                query = "czarne trytytki",
                storeNumber = "074",
                limit = 3,
            ),
        ) as AdvisorToolExecutionResult.Success

        assertEquals(
            listOf(
                AdvisorSearchAction(
                    query = "czarne trytytki",
                    storeNumber = "074",
                    reportedTotalCount = 27,
                ),
            ),
            result.searchActions,
        )
        assertEquals(3, result.result.products.size)
    }

    @Test
    fun `search more action is omitted when bounded results cover reported total`() = runBlocking {
        val candidates = listOf(
            ProductSearchCandidate("6100001", "One"),
            ProductSearchCandidate("6100002", "Two"),
        )
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    items = candidates,
                    reportedTotalCount = 2,
                )
            },
            lookup = { obik, storeNumber ->
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        storeNumber = storeNumber,
                    ),
                )
            },
        )

        val result = tool.execute(
            arguments(
                query = "czarne trytytki",
                limit = 2,
            ),
        ) as AdvisorToolExecutionResult.Success

        assertTrue(result.searchActions.isEmpty())
    }

    @Test
    fun `single result lookup intent never creates search more action`() = runBlocking {
        val candidates = (1..5).map { index ->
            ProductSearchCandidate(
                obik = (6_200_000 + index).toString(),
                name = "Synthetic $index",
            )
        }
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    items = candidates,
                    reportedTotalCount = 27,
                )
            },
            lookup = { obik, storeNumber ->
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        storeNumber = storeNumber,
                    ),
                )
            },
        )

        val result = tool.execute(
            arguments(
                query = "konkretny produkt",
                limit = 1,
            ),
        ) as AdvisorToolExecutionResult.Success

        assertTrue(result.searchActions.isEmpty())
        assertEquals(1, result.result.products.size)
    }

    @Test
    fun `stock zero remains zero and unknown stock and price remain null`() = runBlocking {
        val products = mapOf(
            "1234567" to product(
                obik = "1234567",
                stock = 0,
                price = BigDecimal("0.99"),
            ),
            "2345678" to product(
                obik = "2345678",
                stock = null,
                price = null,
            ),
        )
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    products.keys.map { ProductSearchCandidate(it, null) },
                )
            },
            lookup = { obik, _ ->
                ProductLookupResult.Found(checkNotNull(products[obik]))
            },
        )

        val result = tool.execute(arguments(limit = 2)) as AdvisorToolExecutionResult.Success

        assertEquals(0, result.result.products[0].stock)
        assertEquals(null, result.result.products[1].stock)
        assertEquals(null, result.result.products[1].price)
    }

    @Test
    fun `failed exact lookup never creates fake product`() = runBlocking {
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(ProductSearchCandidate("1234567", "Candidate")),
                )
            },
            lookup = { _, _ ->
                ProductLookupResult.Unavailable(
                    ProductLookupFailure.DATA,
                    "synthetic",
                )
            },
        )

        val result = tool.execute(arguments()) as
            AdvisorToolExecutionResult.Success

        assertEquals(
            AdvisorQueryResultStatus.UNAVAILABLE,
            result.result.results.single().status,
        )
        assertTrue(result.result.products.isEmpty())
        assertTrue(result.snapshots.isEmpty())
    }

    @Test
    fun `successful products plus failed candidates return only verified successes`() = runBlocking {
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate("1234567", "First"),
                        ProductSearchCandidate("2345678", "Second"),
                    ),
                )
            },
            lookup = { obik, _ ->
                if (obik == "1234567") {
                    ProductLookupResult.Found(
                        product(obik = obik, name = "Verified"),
                    )
                } else {
                    ProductLookupResult.Unavailable(
                        ProductLookupFailure.NETWORK,
                        "synthetic",
                    )
                }
            },
        )

        val result = tool.execute(arguments(limit = 2)) as AdvisorToolExecutionResult.Success

        assertEquals(1, result.result.products.size)
        assertEquals("1234567", result.result.products.single().obik)
        assertEquals("Verified", result.result.products.single().name)
    }

    @Test
    fun `all exact lookup failures remain unavailable not verified empty`() = runBlocking {
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate("1234567", null),
                        ProductSearchCandidate("2345678", null),
                    ),
                )
            },
            lookup = { _, _ ->
                ProductLookupResult.Unavailable(
                    ProductLookupFailure.NETWORK,
                    "synthetic",
                )
            },
        )

        val result = tool.execute(arguments(limit = 2)) as
            AdvisorToolExecutionResult.Success

        assertEquals(
            AdvisorQueryResultStatus.UNAVAILABLE,
            result.result.results.single().status,
        )
        assertTrue(result.result.products.isEmpty())
    }

    @Test
    fun `washbasin kit batch verifies several categories within one five lookup budget`() = runBlocking {
        var exactLookups = 0
        val candidatesByQuery = mapOf(
            "silikon sanitarny" to listOf(
                ProductSearchCandidate("1000001", "Silicone A"),
                ProductSearchCandidate("1000002", "Silicone B"),
            ),
            "pistolet do kartuszy" to listOf(
                ProductSearchCandidate("1000003", "Gun"),
            ),
            "narzędzie do wygładzania" to listOf(
                ProductSearchCandidate("1000004", "Finishing tool"),
            ),
        )
        val tool = tool(
            search = { query ->
                ProductSearchResult.Candidates(
                    checkNotNull(candidatesByQuery[query]),
                )
            },
            lookup = { obik, storeNumber ->
                exactLookups += 1
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        name = "Verified $obik",
                        storeNumber = storeNumber,
                    ),
                )
            },
        )

        val result = tool.execute(
            AdvisorToolArguments(
                storeNumber = "075",
                queries = listOf(
                    AdvisorToolQuery("silikon sanitarny", 2),
                    AdvisorToolQuery("pistolet do kartuszy", 1),
                    AdvisorToolQuery("narzędzie do wygładzania", 1),
                ),
            ),
        ) as AdvisorToolExecutionResult.Success

        assertEquals(4, exactLookups)
        assertTrue(exactLookups <= MAX_TOOL_PRODUCTS)
        assertEquals(3, result.result.results.size)
        assertTrue(
            result.result.results.all {
                it.status == AdvisorQueryResultStatus.VERIFIED
            },
        )
        assertEquals(
            listOf("1000001", "1000002", "1000003", "1000004"),
            result.snapshots.map { it.obik },
        )
    }

    @Test
    fun `one failed batch group does not discard verified groups`() = runBlocking {
        val tool = tool(
            search = { query ->
                when (query) {
                    "silikon" -> ProductSearchResult.Candidates(
                        listOf(ProductSearchCandidate("1000001", null)),
                    )
                    "pistolet" -> ProductSearchResult.Unavailable(
                        ProductLookupFailure.NETWORK,
                        "synthetic",
                    )
                    else -> ProductSearchResult.NotFound
                }
            },
            lookup = { obik, storeNumber ->
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        storeNumber = storeNumber,
                    ),
                )
            },
        )

        val result = tool.execute(
            batchArguments(
                AdvisorToolQuery("silikon", 1),
                AdvisorToolQuery("pistolet", 1),
                AdvisorToolQuery("wygładzanie", 1),
            ),
        ) as AdvisorToolExecutionResult.Success

        assertEquals(
            listOf(
                AdvisorQueryResultStatus.VERIFIED,
                AdvisorQueryResultStatus.UNAVAILABLE,
                AdvisorQueryResultStatus.NOT_FOUND,
            ),
            result.result.results.map { it.status },
        )
        assertEquals(listOf("1000001"), result.snapshots.map { it.obik })
    }

    @Test
    fun `five query groups with limit one are accepted`() = runBlocking {
        var searches = 0
        val tool = tool(
            search = {
                searches += 1
                ProductSearchResult.NotFound
            },
            lookup = { _, _ -> error("lookup must not run") },
        )

        val result = tool.execute(
            AdvisorToolArguments(
                storeNumber = "075",
                queries = (1..5).map {
                    AdvisorToolQuery("query $it", 1)
                },
            ),
        )

        assertTrue(result is AdvisorToolExecutionResult.Success)
        assertEquals(5, searches)
    }

    @Test
    fun `batch rejects total requested limits above five`() = runBlocking {
        val tool = tool(
            search = { error("search must not run") },
            lookup = { _, _ -> error("lookup must not run") },
        )

        assertEquals(
            AdvisorToolExecutionResult.Failure,
            tool.execute(
                batchArguments(
                    AdvisorToolQuery("one", 3),
                    AdvisorToolQuery("two", 3),
                ),
            ),
        )
    }

    @Test
    fun `batch rejects more than five query groups`() = runBlocking {
        val tool = tool(
            search = { error("search must not run") },
            lookup = { _, _ -> error("lookup must not run") },
        )

        assertEquals(
            AdvisorToolExecutionResult.Failure,
            tool.execute(
                AdvisorToolArguments(
                    storeNumber = "075",
                    queries = (1..6).map {
                        AdvisorToolQuery("query $it", 1)
                    },
                ),
            ),
        )
    }

    @Test
    fun `batch rejects blank and oversized query`() = runBlocking {
        val tool = tool(
            search = { error("search must not run") },
            lookup = { _, _ -> error("lookup must not run") },
        )

        assertEquals(
            AdvisorToolExecutionResult.Failure,
            tool.execute(batchArguments(AdvisorToolQuery("   ", 1))),
        )
        assertEquals(
            AdvisorToolExecutionResult.Failure,
            tool.execute(
                batchArguments(
                    AdvisorToolQuery(
                        "x".repeat(MAX_TOOL_QUERY_CHARS + 1),
                        1,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `exact lookup also retains trusted local snapshot with product url`() = runBlocking {
        val trustedUrl = "https://www.obi.pl/p/1234567/trusted-canonical"
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(ProductSearchCandidate("1234567", "Candidate")),
                )
            },
            lookup = { _, _ ->
                ProductLookupResult.Found(
                    product(
                        obik = "1234567",
                        name = "Verified exact",
                        stock = 2,
                        price = BigDecimal("12.30"),
                        productUrl = trustedUrl,
                    ),
                )
            },
        )

        val result = tool.execute(arguments()) as AdvisorToolExecutionResult.Success
        val snapshot = result.snapshots.single()

        assertEquals("1234567", snapshot.obik)
        assertEquals("Verified exact", snapshot.name)
        assertEquals(2, snapshot.stock)
        assertEquals(BigDecimal("12.30"), snapshot.grossPrice)
        assertEquals(trustedUrl, snapshot.productUrl)
        assertEquals(1_234_567L, snapshot.verifiedAt)
        assertTrue(
            snapshot.toString().contains("shortDescription").not(),
        )
        assertTrue(
            snapshot.toString().contains("technicalFacts").not(),
        )
    }

    @Test
    fun `trusted image stays local in snapshot and out of advisor product context`() = runBlocking {
        val imageUrl =
            "https://bilder.obi.pl/example/pr08A/image.jpeg"
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate(
                            "1234567",
                            "Candidate",
                        ),
                    ),
                )
            },
            lookup = { _, _ ->
                ProductLookupResult.Found(
                    product(
                        obik = "1234567",
                        name = "Verified",
                        primaryImageUrl = imageUrl,
                    ),
                )
            },
        )

        val result = tool.execute(arguments()) as
            AdvisorToolExecutionResult.Success

        assertEquals(
            imageUrl,
            result.snapshots.single().primaryImageUrl,
        )
        assertEquals(
            AdvisorVerifiedProduct(
                obik = "1234567",
                name = "Verified",
                stock = 3,
                price = BigDecimal("10.00"),
            ),
            result.result.products.single(),
        )
        assertTrue(
            result.result.products.single()
                .toString()
                .contains("bilder.obi.pl")
                .not(),
        )
    }

    @Test
    fun `alternate store propagates to every exact lookup and compact result`() = runBlocking {
        val stores = mutableListOf<String>()
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(ProductSearchCandidate("3496072", "Candidate")),
                )
            },
            lookup = { obik, storeNumber ->
                stores += storeNumber
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        stock = 13,
                        storeNumber = storeNumber,
                    ),
                )
            },
        )

        val result = tool.execute(
            arguments(storeNumber = "074"),
        ) as AdvisorToolExecutionResult.Success

        assertEquals(listOf("074"), stores)
        assertEquals("074", result.result.storeNumber)
        assertEquals("074", result.snapshots.single().storeNumber)
    }

    @Test
    fun `same OBIK across stores keeps store facts independent and product facts stable`() = runBlocking {
        val commonFacts = listOf(
            TechnicalFact("Moc", "600 W"),
        )
        fun storeTool(stock: Int, price: String) = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(
                        ProductSearchCandidate(
                            "3496072",
                            "Candidate",
                        ),
                    ),
                )
            },
            lookup = { obik, storeNumber ->
                ProductLookupResult.Found(
                    product(
                        obik = obik,
                        name = "Same product",
                        stock = stock,
                        price = BigDecimal(price),
                        storeNumber = storeNumber,
                        brand = "Brand",
                        shortDescription = "Same verified description.",
                        technicalFacts = commonFacts,
                    ),
                )
            },
        )

        val in074 = storeTool(2, "10.00").execute(
            arguments(storeNumber = "074"),
        ) as AdvisorToolExecutionResult.Success
        val in075 = storeTool(7, "12.00").execute(
            arguments(storeNumber = "075"),
        ) as AdvisorToolExecutionResult.Success

        assertEquals("074", in074.result.storeNumber)
        assertEquals("075", in075.result.storeNumber)
        assertEquals(2, in074.result.products.single().stock)
        assertEquals(7, in075.result.products.single().stock)
        assertEquals(
            BigDecimal("10.00"),
            in074.result.products.single().price,
        )
        assertEquals(
            BigDecimal("12.00"),
            in075.result.products.single().price,
        )
        assertEquals(
            in074.result.products.single().brand,
            in075.result.products.single().brand,
        )
        assertEquals(
            in074.result.products.single().technicalFacts,
            in075.result.products.single().technicalFacts,
        )
        assertEquals("074", in074.snapshots.single().storeNumber)
        assertEquals("075", in075.snapshots.single().storeNumber)
    }

    @Test
    fun `unsupported store fails before search or lookup`() = runBlocking {
        var searched = false
        var lookedUp = false
        val tool = tool(
            search = {
                searched = true
                ProductSearchResult.NotFound
            },
            lookup = { _, _ ->
                lookedUp = true
                error("must not run")
            },
        )

        val result = tool.execute(
            arguments(storeNumber = "999"),
        )

        assertEquals(
            AdvisorToolExecutionResult.UnsupportedStore,
            result,
        )
        assertEquals(false, searched)
        assertEquals(false, lookedUp)
    }

    @Test
    fun `compact result exposes only verified product fields`() = runBlocking {
        val tool = tool(
            search = {
                ProductSearchResult.Candidates(
                    listOf(ProductSearchCandidate("1234567", "Candidate")),
                )
            },
            lookup = { _, _ ->
                ProductLookupResult.Found(
                    product(
                        obik = "1234567",
                        name = "Verified",
                        stock = 7,
                        price = BigDecimal("12.34"),
                    ),
                )
            },
        )

        val result = tool.execute(arguments()) as AdvisorToolExecutionResult.Success
        val verified = result.result.products.single()

        assertEquals("1234567", verified.obik)
        assertEquals("Verified", verified.name)
        assertEquals(7, verified.stock)
        assertEquals(BigDecimal("12.34"), verified.price)
        assertEquals(null, verified.brand)
        assertEquals(null, verified.shortDescription)
        assertTrue(verified.technicalFacts.isEmpty())
        assertTrue(verified.toString().contains("productUrl").not())
        assertTrue(verified.toString().contains("verifiedAt").not())
    }

    private fun tool(
        search: (String) -> ProductSearchResult,
        lookup: (String, String) -> ProductLookupResult,
    ): FindObiProductsTool =
        FindObiProductsTool(
            searchProducts = search,
            lookupObik = lookup,
            ioDispatcher = Dispatchers.Unconfined,
            now = { 1_234_567L },
        )

    private fun arguments(
        query: String = "klej",
        storeNumber: String = "075",
        limit: Int = 5,
    ) = AdvisorToolArguments(
        query = query,
        storeNumber = storeNumber,
        limit = limit,
    )

    private fun batchArguments(
        vararg queries: AdvisorToolQuery,
        storeNumber: String = "075",
    ) = AdvisorToolArguments(
        storeNumber = storeNumber,
        queries = queries.toList(),
    )

    private fun product(
        obik: String,
        name: String = "Synthetic exact product",
        stock: Int? = 3,
        price: BigDecimal? = BigDecimal("10.00"),
        productUrl: String = "https://example.invalid/p/$obik",
        storeNumber: String = "075",
        brand: String? = null,
        shortDescription: String? = null,
        technicalFacts: List<TechnicalFact> = emptyList(),
        primaryImageUrl: String? = null,
    ) = LocalProduct(
        obik = obik,
        name = name,
        stock = stock,
        grossPrice = price,
        productUrl = productUrl,
        ean = null,
        storeNumber = storeNumber,
        brand = brand,
        shortDescription = shortDescription,
        technicalFacts = technicalFacts,
        primaryImageUrl = primaryImageUrl,
    )
}
