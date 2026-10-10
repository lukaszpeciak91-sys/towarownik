package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.KwantProductLocationsAdapter
import pl.lukaszpeciak.towarownik.product.provider.LocationCoverage
import pl.lukaszpeciak.towarownik.product.provider.LocationCoverageKind
import pl.lukaszpeciak.towarownik.product.provider.LocationFailure
import pl.lukaszpeciak.towarownik.product.provider.LocationStock
import pl.lukaszpeciak.towarownik.product.provider.LocationsHttpFetcher
import pl.lukaszpeciak.towarownik.product.provider.LocationsHttpResult
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsAdapter
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsResult
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsService
import pl.lukaszpeciak.towarownik.product.provider.ProductRef
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct

/** Synthetic facts only. Never live stock. */
class AdvisorLocationsToolTest {
    private val one = product("3496072")
    private val two = product("7313810")

    private fun product(id: String) = VerifiedProductSnapshot(
        obik = id, productId = id, providerId = "obi-pl",
        name = "Fixture $id", stock = 2, grossPrice = null,
        productUrl = "https://www.obi.pl/p/$id", verifiedAt = 100L,
        storeNumber = "075", branchId = "075",
    )

    private class StockAdapter : ProductLocationsAdapter {
        override val providerId = OBI_PROVIDER_ID
        var calls = 0
        var lastRequested = emptyList<BranchId>()
        override suspend fun read(
            ref: ProductRef,
            requested: List<BranchId>,
            trustedProduct: ProviderProduct?,
        ): ProductLocationsResult {
            calls++
            lastRequested = requested
            val list = requested.mapIndexed { i, id ->
                LocationStock(
                    ProviderBranch(id, if (id.value == "075") "Nowy Sącz" else "Kraków"),
                    if (i == 0) 0 else 7,
                )
            }
            return ProductLocationsResult.Available(
                ref, list,
                LocationCoverage(
                    LocationCoverageKind.REQUESTED_SUBSET, requested, requested, 1,
                ), 123456L,
            )
        }
    }

    private fun tool(fake: StockAdapter): AdvisorLocationsTool = AdvisorLocationsTool(
        locationsService = ProductLocationsService(listOf(fake)),
    )

    @Test fun `historical single exact assistant card authorizes product but not new product card`() = runBlocking {
        val fake = StockAdapter()
        val evidence = tool(fake).execute(
            AdvisorLocationArguments("obi-pl", "3496072", listOf("075")),
            "obi-pl",
            "Sprawdź w markecie OBI 075 ten produkt",
            emptyList(), listOf(one),
        )
        assertEquals("verified", evidence.status)
        assertEquals("3496072", evidence.productId)
        assertEquals(listOf("075"), evidence.checkedIds)
        assertEquals(listOf(BranchId("075")), fake.lastRequested)
        assertEquals(0, evidence.locations.single().stock)
        assertEquals(1, fake.calls)
    }

    @Test fun `same-turn exact verified product is trusted`() = runBlocking {
        val fake = StockAdapter()
        val result = tool(fake).execute(
            AdvisorLocationArguments("obi-pl", "3496072", listOf("075")),
            "obi-pl",
            "Czy ten produkt jest dostępny w OBI 075?",
            listOf(one), emptyList(),
        )
        assertEquals("verified", result.status)
        assertEquals(1, fake.calls)
    }

    @Test fun `ambiguous history and hallucinated identity never authorize HTTP`() = runBlocking {
        val fake = StockAdapter()
        val svc = tool(fake)
        val ambiguous = svc.execute(
            AdvisorLocationArguments("obi-pl", "3496072", listOf("075")),
            "obi-pl",
            "Gdzie jeszcze jest ten produkt? OBI 075",
            emptyList(), listOf(one, two),
        )
        assertEquals("rejected", ambiguous.status)
        assertEquals("ambiguous_product", ambiguous.reason)
        val invented = svc.execute(
            AdvisorLocationArguments("obi-pl", "9999999", listOf("075")),
            "obi-pl",
            "Gdzie jeszcze jest 9999999? OBI 075",
            emptyList(), listOf(one),
        )
        assertEquals("rejected", invented.status)
        assertEquals("untrusted_product", invented.reason)
        val cross = svc.execute(
            AdvisorLocationArguments("kwant-pl", "3496072", emptyList()),
            "kwant-pl",
            "Sprawdź inne oddziały",
            emptyList(), listOf(one),
        )
        assertEquals("rejected", cross.status)
        assertEquals(0, fake.calls)
    }

    @Test fun `untrusted model selected market is rejected and broad scope asks clarification`() = runBlocking {
        val fake = StockAdapter()
        val svc = tool(fake)
        val invented = svc.execute(
            AdvisorLocationArguments("obi-pl", "3496072", listOf("003")),
            "obi-pl",
            "Sprawdź w OBI 075",
            emptyList(), listOf(one),
        )
        assertEquals("location_not_authorized", invented.reason)
        val broad = svc.execute(
            AdvisorLocationArguments("obi-pl", "3496072", emptyList()),
            "obi-pl",
            "Gdzie jeszcze jest ten produkt?",
            emptyList(), listOf(one),
        )
        assertEquals("scope_required", broad.reason)
        assertEquals(0, fake.calls)
    }

    @Test fun `OBI ambiguous city does not silently pick one market`() = runBlocking {
        val fake = StockAdapter()
        val evidence = tool(fake).execute(
            AdvisorLocationArguments("obi-pl", "3496072", listOf("Kraków")),
            "obi-pl",
            "Sprawdź w Krakowie",
            emptyList(), listOf(one),
        )
        assertEquals("ambiguous_location", evidence.reason)
        assertEquals(0, fake.calls)
    }

    @Test fun `OBI canonical exact store scope remains bound to user text`() = runBlocking {
        val fake = StockAdapter()
        val evidence = tool(fake).execute(
            AdvisorLocationArguments("obi-pl", "3496072", listOf("003", "075")),
            "obi-pl",
            "Sprawdź ten produkt w OBI 003 i 075",
            emptyList(), listOf(one),
        )
        assertEquals("verified", evidence.status)
        assertEquals(2, evidence.checkedIds.size)
        assertEquals(2, evidence.locations.size)
        assertEquals(1, fake.calls)
        assertTrue(evidence.locations[0].stock!! > 0)
        assertEquals(0, evidence.locations[1].stock)
    }

    @Test fun `KWANT missing extended fails before directory or HTTP and does not leak old central stock`() = runBlocking {
        var directoryCalls = 0
        var httpCalls = 0
        val adapter = KwantProductLocationsAdapter(
            fetcher = LocationsHttpFetcher {
                httpCalls++
                LocationsHttpResult.Success("""{"list":[]}""")
            },
            directory = {
                directoryCalls++
                ProviderBranchResult.Available(listOf(ProviderBranch(BranchId("205"), "Fixture")))
            },
        )
        val tool = AdvisorLocationsTool(
            locationsService = ProductLocationsService(listOf(adapter)),
        )
        val kwant = one.copy(
            providerId = "kwant-pl", productId = "580", obik = "580",
            branchId = "205", centralStock = 999,
        )
        val evidence = tool.execute(
            AdvisorLocationArguments("kwant-pl", "580", emptyList()),
            "kwant-pl",
            "Sprawdź ten produkt w innych oddziałach",
            emptyList(), listOf(kwant),
        )
        assertEquals("unavailable", evidence.status)
        assertEquals("unverified_request_contract", evidence.reason)
        assertEquals(0, directoryCalls)
        assertEquals(0, httpCalls)
        assertNull(evidence.centralStock)
    }
}
