package pl.lukaszpeciak.towarownik.agent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.*
import pl.lukaszpeciak.towarownik.product.provider.*

/** All identities, quantities and responses are controlled fixtures, never live stock. */
class SingleBranchExactLookupTest {
    private val kwantBranches = listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz"),
        ProviderBranch(BranchId("204"), "Tarnów"),
        ProviderBranch(BranchId("206"), "Mielec"),
    )
    private val obiBranches = OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }
    private fun card(provider: String = "kwant-pl", id: String = "580", article: String = "MBN116E/HAG") =
        VerifiedProductSnapshot(obik = id, productId = id, providerId = provider, name = "Synthetic $article",
            stock = 999, centralStock = 888, grossPrice = null, productUrl = "https://offline.invalid/$id",
            verifiedAt = 1, storeNumber = if (provider == "obi-pl") "075" else "205",
            branchId = if (provider == "obi-pl") "075" else "205", articleNumber = article)

    private inner class Fixture(val provider: String = "kwant-pl", val directory: List<ProviderBranch> = if (provider == "obi-pl") obiBranches else kwantBranches) {
        val profile = if (provider == "obi-pl") "075" else "205"
        val snapshot = if (provider == "obi-pl") card(provider, "3496072", "OBIK") else card()
        val exact = mutableListOf<Pair<ProductRef, BranchId>>()
        val inventory = mutableListOf<List<BranchId>>()
        var stock: Int? = 17
        var lookup: ((ProductRef, BranchId) -> ProviderLookupResult)? = null
        val productProvider = object : ProductProvider {
            override val providerId = ProviderId(provider)
            override fun branches() = ProviderBranchResult.Available(directory)
            override fun search(query: String, maxResults: Int): ProviderSearchResult = error("Singleton must not search")
            override fun lookup(ref: ProductRef, branchId: BranchId): ProviderLookupResult {
                exact += ref to branchId
                return lookup?.invoke(ref, branchId) ?: ProviderLookupResult.Found(ProviderProduct(
                    ref, branchId, "Fresh synthetic", stock, centralStock = 23, grossPrice = null,
                    priceScope = null, productUrl = "https://offline.invalid/fresh", ean = null, stockUnit = "m"))
            }
        }
        private val adapter = object : ProductLocationsAdapter {
            override val providerId = ProviderId(provider)
            override suspend fun read(ref: ProductRef, requested: List<BranchId>, trustedProduct: ProviderProduct?): ProductLocationsResult {
                inventory += requested
                val ids = requested.ifEmpty { directory.map { it.branchId } }
                return ProductLocationsResult.Available(ref, ids.map { id -> LocationStock(directory.single { it.branchId == id }, 7) },
                    LocationCoverage(if (requested.isEmpty()) LocationCoverageKind.ALL_PUBLIC_LOCATIONS else LocationCoverageKind.REQUESTED_SUBSET, ids, ids, 1), 900)
            }
        }
        fun tool(registry: ProductProviderRegistry = ProductProviderRegistry(listOf(productProvider))) = AdvisorLocationsTool(
            locationsService = ProductLocationsService(listOf(adapter)),
            obiBranches = { ProviderBranchResult.Available(directory) }, kwantBranches = { ProviderBranchResult.Available(directory) },
            providers = registry, now = { 1234 })
        fun authority(text: String, modelId: String = snapshot.effectiveProductId,
            cards: List<VerifiedProductSnapshot> = listOf(snapshot), hints: List<String> = emptyList()): AdvisorInventoryAuthorization =
            AdvisorInventoryAuthorizer.resolve(AdvisorLocationArguments(provider, modelId, hints), provider, profile,
                text, emptyList(), cards, directory, emptyList())
        suspend fun read(text: String, modelId: String = snapshot.effectiveProductId, cards: List<VerifiedProductSnapshot> = listOf(snapshot), hints: List<String> = emptyList()): AdvisorLocationEvidence =
            tool().execute(authority(text, modelId, cards, hints) as AdvisorInventoryAuthorization.Inventory)
    }

    @Test fun singletonUsesExactProviderInsteadOfInventory() = runBlocking {
        val f = Fixture()
        val e = f.read("Sprawdź stan MBN116E w Nowym Sączu")
        println("SINGLETON_ROUTE exact=${f.exact} inventory=${f.inventory} stock=${e.locations.single().stock}")
        assertEquals("single branch must bypass network inventory", 0, f.inventory.size)
        assertEquals(listOf(ProductRef(KWANT_PROVIDER_ID, "580") to BranchId("205")), f.exact)
        assertEquals(17, e.locations.single().stock)
    }
    @Test fun sameProductMovesToTarnowAndBackWithoutChangingProfile() = runBlocking {
        val f = Fixture()
        for ((text, id) in listOf("Sprawdź stan MBN116E w Tarnowie" to "204", "Sprawdź stan MBN116E u nas" to "205")) {
            val e = f.read(text, hints = listOf("206"))
            assertEquals(listOf(id), e.checkedIds); assertEquals("580", e.productId)
            assertEquals(id, f.exact.last().second.value); assertEquals("205", f.profile)
        }
        assertTrue(f.inventory.isEmpty())
    }
    @Test fun explicitNewVerifiedProductOverridesOldCard() = runBlocking {
        val f = Fixture(); val b = card(id = "7027", article = "MBN316E/HAG")
        val e = f.read("Sprawdź stan MBN316E w Mielcu", "7027", listOf(f.snapshot, b))
        assertEquals("7027", e.productId)
        assertEquals(listOf(ProductRef(KWANT_PROVIDER_ID, "7027") to BranchId("206")), f.exact)
        assertTrue(f.inventory.isEmpty())
    }
    @Test fun obiExactIdentityUsesRequestedMarket075And003() = runBlocking {
        val f = Fixture("obi-pl")
        for (id in listOf("075", "003")) {
            val e = f.read("Sprawdź stan produktu 3496072 w OBI $id")
            assertEquals(listOf(id), e.checkedIds); assertEquals("3496072", e.productId)
            assertEquals(ProductRef(OBI_PROVIDER_ID, "3496072") to BranchId(id), f.exact.last())
        }
        assertTrue(f.inventory.isEmpty())
    }
    @Test fun positiveAndZeroHaveFreshTimeCoverageAndOnlyFreshCentralStock() = runBlocking {
        for (provider in listOf("obi-pl", "kwant-pl")) for (stock in listOf(17, 0)) {
            val f = Fixture(provider); f.stock = stock
            val e = f.read(if (provider == "obi-pl") "Sprawdź stan produktu 3496072 w OBI 075" else "Sprawdź stan MBN116E w Tarnowie")
            assertEquals("verified", e.status); assertNull(e.reason); assertEquals("requested_subset", e.coverage)
            assertEquals(stock, e.locations.single().stock); assertEquals(1234L, e.verifiedAtMillis)
            assertEquals(e.checkedIds, e.returnedIds); assertTrue(e.missingIds.isEmpty())
            assertEquals(e.checkedIds, e.authorizedBranchIds)
            assertEquals(if (provider == "kwant-pl") 23 else null, e.centralStock)
            assertEquals("m", e.stockUnit)
        }
    }
    @Test fun nullAndUnavailableNeverReuseHistoricalOrCentralStock() = runBlocking {
        val f = Fixture()
        val cases = listOf<(ProductRef, BranchId) -> ProviderLookupResult>(
            { ref, branch -> ProviderLookupResult.Found(ProviderProduct(ref, branch, "Fresh", null, centralStock = 777, grossPrice = null, priceScope = null, productUrl = "https://offline.invalid", ean = null)) },
            { _, _ -> ProviderLookupResult.Unavailable(ProductProviderFailure.NETWORK, "secret transport") },
            { _, _ -> throw IOException("secret exception") },
        )
        for (lookup in cases) {
            f.lookup = lookup
            val e = f.read("Sprawdź stan MBN116E w Tarnowie")
            assertEquals("unavailable", e.status); assertEquals("unknown", e.coverage)
            assertEquals(listOf("204"), e.checkedIds); assertEquals(e.checkedIds, e.missingIds)
            assertTrue(e.returnedIds.isEmpty()); assertNull(e.locations.single().stock)
            assertNull(e.verifiedAtMillis); assertNull(e.centralStock)
            assertFalse(renderAdvisorLocationDetails(e).contains("999"))
            assertTrue(renderAdvisorLocationDetails(e).contains("Nie udało się potwierdzić stanu."))
        }
        assertTrue(f.inventory.isEmpty())
    }
    @Test fun returnedProductProviderBranchAndNegativeStockFailClosed() = runBlocking {
        val f = Fixture()
        for ((p, id, branch, stock) in listOf(
            listOf("kwant-pl", "581", "204", 5), listOf("obi-pl", "580", "204", 5),
            listOf("kwant-pl", "580", "205", 5), listOf("kwant-pl", "580", "204", -1))) {
            f.lookup = { _, _ -> ProviderLookupResult.Found(ProviderProduct(ProductRef(ProviderId(p as String), id as String), BranchId(branch as String), "wrong", stock as Int,
                grossPrice = null, priceScope = null, productUrl = "https://offline.invalid", ean = null)) }
            val e = f.read("Sprawdź stan MBN116E w Tarnowie")
            assertEquals("unavailable", e.status); assertEquals("580", e.productId)
            assertEquals(listOf("204"), e.checkedIds); assertNull(e.locations.single().stock)
            assertNull(e.verifiedAtMillis); assertNull(e.centralStock)
        }
        assertTrue(f.inventory.isEmpty())
    }
    @Test fun twoBranchesAndFullNetworkKeepInventoryWithoutExactFanout() = runBlocking {
        val f = Fixture()
        val two = f.read("Sprawdź stan MBN116E w Tarnowie i Mielcu")
        assertEquals(setOf("204", "206"), two.checkedIds.toSet())
        val all = f.read("Sprawdź stan MBN116E we wszystkich oddziałach")
        assertEquals(setOf("204", "205", "206"), all.checkedIds.toSet())
        assertEquals(2, f.inventory.size); assertTrue(f.inventory.last().isEmpty()); assertTrue(f.exact.isEmpty())
    }
    @Test fun explicitNetworkWithOneDirectoryEntryStillUsesInventory() = runBlocking {
        val f = Fixture(directory = listOf(kwantBranches.first()))
        f.read("Sprawdź stan MBN116E we wszystkich oddziałach")
        assertEquals(1, f.inventory.size); assertTrue(f.exact.isEmpty())
    }
    @Test fun ambiguousHistoryUnknownBranchAndWrongModelIdentityCannotLookup() = runBlocking {
        val f = Fixture(); val b = card(id = "7027", article = "MBN316E/HAG")
        for (a in listOf(f.authority("Sprawdź stan w Tarnowie", cards = listOf(f.snapshot, b)),
            f.authority("Sprawdź stan MBN116E w oddziale 999"), f.authority("Sprawdź stan MBN116E w Tarnowie", modelId = "7027"),
            f.authority("Sprawdź stan MBN316E w Tarnowie"))) {
            assertFalse(a is AdvisorInventoryAuthorization.Inventory)
        }
        assertTrue(f.exact.isEmpty()); assertTrue(f.inventory.isEmpty())
    }
    @Test fun cancellationIsNotConvertedToUnknown() = runBlocking {
        val f = Fixture(); f.lookup = { _, _ -> throw CancellationException("cancel") }
        try { f.read("Sprawdź stan MBN116E w Tarnowie"); fail("cancel swallowed") } catch (_: CancellationException) { }
        assertTrue(f.inventory.isEmpty())
    }
    @Test fun kwantUsesActualProductAndCurrentHttpPathsWithoutSearchOrDepartments() = runBlocking {
        val f = Fixture(); val urls = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); urls += request.url.toString()
            val body = when {
                request.url.encodedPath == "/lista-hurtowni-elektrycznych" -> next("""{"departments":{"list":[{"department_id":204,"name":"Tarnów","postcode":"33-100","street":"Testowa 1"}]}}""")
                request.url.encodedPath == "/produkt/580" -> next("""{"product":{"id":580,"name":"Synthetic MBN116E","code":"MBN116E/HAG","stock":888}}""")
                request.url.encodedPath == "/api/front/products/580/current" -> {
                    assertEquals("204", request.url.queryParameter("depstock"))
                    """{"product_id":580,"department_stock":{"department_id":204,"stock":31},"unit":"szt."}"""
                }
                else -> error("Unexpected provider HTTP ${request.url}")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic").body(body.toResponseBody()).build()
        }.build()
        val tool = f.tool(ProductProviderRegistry(listOf(KwantProductProvider(KwantHttpFrontendClient(http)))))
        val e = tool.execute(f.authority("Sprawdź stan MBN116E w Tarnowie") as AdvisorInventoryAuthorization.Inventory)
        assertEquals(31, e.locations.single().stock); assertTrue(f.inventory.isEmpty())
        assertEquals(3, urls.size); assertTrue(urls.last().endsWith("/580/current?depstock=204"))
    }
    @Test fun obiUsesActualMarketSpecificRepositoryPath() = runBlocking {
        val f = Fixture("obi-pl"); val markets = mutableListOf<String>()
        val html = javaClass.getResource("/obi/positive-stock.html")!!.readText().replace("7313810", "3496072")
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val market = request.url.queryParameter("storeNumber")!!; markets += market
            assertEquals("/api/disc/store/change", request.url.encodedPath)
            assertEquals("/p/3496072", request.url.queryParameter("redirectUrl"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body(html.replace("075", market).toResponseBody()).build()
        }.build()
        val repository = ProductLookupRepository(httpClient = ObiHttpClient(client = http))
        val tool = f.tool(ProductProviderRegistry(listOf(ObiProductProvider(lookupObik = repository::lookupObik))))
        for (market in listOf("075", "003")) {
            val e = tool.execute(f.authority("Sprawdź stan produktu 3496072 w OBI $market") as AdvisorInventoryAuthorization.Inventory)
            assertEquals("verified", e.status); assertEquals(listOf(market), e.checkedIds)
        }
        assertEquals(listOf("075", "003"), markets); assertTrue(f.inventory.isEmpty())
    }
    private fun next(props: String) = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":$props}}</script>"""
}
