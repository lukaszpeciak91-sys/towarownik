package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import okhttp3.HttpUrl
import pl.lukaszpeciak.towarownik.product.OBI_STORES
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.LocationFailure
import pl.lukaszpeciak.towarownik.product.provider.LocationsHttpFetcher
import pl.lukaszpeciak.towarownik.product.provider.LocationsHttpResult
import pl.lukaszpeciak.towarownik.product.provider.ObiProductLocationsAdapter
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsAdapter
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsResult
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsService
import pl.lukaszpeciak.towarownik.product.provider.ProductRef
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct

/** All inventory in this suite is synthetic and controlled; no live OBI calls. */
class AdvisorLocationsNationwideTest {
    private val snapshot = VerifiedProductSnapshot(
        obik = "3496072", name = "Fixture drill", stock = 5,
        grossPrice = null, productUrl = "https://www.obi.pl/p/3496072",
        verifiedAt = 1700L, storeNumber = "075",
    )
    private class FakeInventory(
        private val failAtRequest: Int? = null,
        private val cancelAtRequest: Int? = null,
    ) {
        val serviceReads = mutableListOf<List<BranchId>>()
        val httpBatches = mutableListOf<List<String>>()
        val wire = LocationsHttpFetcher { url: HttpUrl ->
            val ids = url.queryParameter("storeIds")!!.split(",")
            httpBatches += ids
            when (httpBatches.size) {
                cancelAtRequest -> throw CancellationException("synthetic cancellation")
                failAtRequest -> LocationsHttpResult.Failure(LocationFailure.TRANSPORT)
                else -> LocationsHttpResult.Success(
                    ids.joinToString(prefix = "[", postfix = "]") { id ->
                        val stock = if (id == "003") 0 else 2
                        """{"storeId":"$id","availableQuantity":$stock}"""
                    },
                )
            }
        }
        private val adapter = ObiProductLocationsAdapter(fetcher = wire, now = { 123456L })
        val counter = object : ProductLocationsAdapter {
            override val providerId = OBI_PROVIDER_ID
            override suspend fun read(
                ref: ProductRef,
                requested: List<BranchId>,
                trustedProduct: ProviderProduct?,
            ): ProductLocationsResult {
                serviceReads += requested
                return adapter.read(ref, requested, trustedProduct)
            }
        }
        val tool = AdvisorLocationsTool(ProductLocationsService(listOf(counter)))

        suspend fun run(
            message: String,
            hints: List<String> = emptyList(),
            current: String = "075",
        ): AdvisorLocationEvidence = tool.execute(
            AdvisorLocationArguments("obi-pl", "3496072", hints),
            "obi-pl", current, message, emptyList(), listOf(snapshot),
        )
    }

    @Test fun `61 other markets use exactly four bounded reads and seven HTTP batches`() = runBlocking {
        val fake = FakeInventory()
        val result = fake.run("Gdzie jeszcze jest ten produkt?")
        assertEquals("verified", result.status)
        assertEquals("all_other_locations", result.coverage)
        assertEquals(61, result.checkedIds.size)
        assertEquals(61, result.returnedIds.size)
        assertTrue(result.missingIds.isEmpty())
        assertEquals(61, result.locations.size)
        assertEquals(4, fake.serviceReads.size)
        assertEquals(listOf(20, 20, 20, 1), fake.serviceReads.map { it.size })
        assertEquals(7, fake.httpBatches.size)
        assertEquals(listOf(10, 10, 10, 10, 10, 10, 1), fake.httpBatches.map { it.size })
        assertTrue(fake.httpBatches.flatten().distinct().size == 61)
        assertFalse(result.checkedIds.contains("075"))
        assertFalse(fake.httpBatches.flatten().contains("075"))
        assertEquals(123456L, result.verifiedAtMillis)
        assertTrue(result.locations.none { it.stock == null })
    }

    @Test fun `single failed HTTP batch still completes scope with ten unknowns`() = runBlocking {
        val fake = FakeInventory(failAtRequest = 3)
        val result = fake.run("Sprawdź inne markety")
        assertEquals("verified", result.status)
        assertEquals("partial", result.coverage)
        assertEquals(61, result.checkedIds.size)
        assertEquals(51, result.returnedIds.size)
        assertEquals(10, result.missingIds.size)
        assertEquals(61, result.locations.size)
        assertEquals(result.missingIds.toSet(),
            result.locations.filter { it.stock == null }.map { it.branchId }.toSet())
        assertEquals(4, fake.serviceReads.size)
        assertEquals(7, fake.httpBatches.size)
    }

    @Test fun `all service batches fail honestly without fabricating zero`() = runBlocking {
        val fake = FakeInventory(failAtRequest = 1)
        // One failed batch only; the rest may still prove stock.
        val first = fake.run("Gdzie jeszcze jest?")
        assertEquals("partial", first.coverage)
        assertEquals(10, first.missingIds.size)
        assertEquals(61, first.locations.size)
        assertTrue(first.locations.filter { it.branchId in first.missingIds }
            .all { it.stock == null })
    }

    @Test fun `Krakow city scope expands to all four canonical markets`() = runBlocking {
        val fake = FakeInventory()
        val result = fake.run("Sprawdź w Krakowie", hints = listOf("Kraków"))
        assertEquals("verified", result.status)
        assertEquals("requested_subset", result.coverage)
        assertEquals(setOf("019", "072", "003", "059"), result.checkedIds.toSet())
        assertEquals(1, fake.serviceReads.size)
        assertEquals(1, fake.httpBatches.size)
        assertEquals(0, result.locations.single { it.branchId == "003" }.stock)
        assertTrue(result.locations.first().stock!! > 0) // presentation order
    }

    @Test fun `Tarnow cannot be invented from canonical OBI catalog`() = runBlocking {
        val fake = FakeInventory()
        val result = fake.run(
            "Sprawdź Kraków i Tarnów",
            hints = listOf("Kraków", "Tarnów"),
        )
        assertEquals("rejected", result.status)
        assertEquals("location_not_authorized", result.reason)
        assertEquals(0, fake.serviceReads.size)
        assertEquals(0, fake.httpBatches.size)
    }

    @Test fun `explicit 075 checks only selected market`() = runBlocking {
        val fake = FakeInventory()
        val result = fake.run("Sprawdź market OBI 075", hints = listOf("075"))
        assertEquals(listOf("075"), result.checkedIds)
        assertEquals(listOf(listOf(BranchId("075"))), fake.serviceReads)
        assertEquals(1, fake.httpBatches.size)
    }

    @Test fun `model cannot narrow broad all-other request by invented IDs`() = runBlocking {
        val fake = FakeInventory()
        val result = fake.run(
            "Gdzie jeszcze jest ten produkt?",
            hints = listOf("003", "999"),
        )
        assertEquals("all_other_locations", result.coverage)
        assertEquals(61, result.checkedIds.size)
        assertEquals(7, fake.httpBatches.size)
    }

    @Test fun `cancellation propagates without retry or further HTTP`() = runBlocking {
        val fake = FakeInventory(cancelAtRequest = 3)
        var propagated = false
        try {
            fake.run("Sprawdź inne markety")
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
        assertEquals(3, fake.httpBatches.size)
        assertEquals(2, fake.serviceReads.size)
    }
}
