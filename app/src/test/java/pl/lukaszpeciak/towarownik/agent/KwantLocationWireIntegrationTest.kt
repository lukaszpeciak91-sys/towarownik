package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.KwantProductLocationsAdapter
import pl.lukaszpeciak.towarownik.product.provider.LocationFailure
import pl.lukaszpeciak.towarownik.product.provider.LocationsHttpFetcher
import pl.lukaszpeciak.towarownik.product.provider.LocationsHttpResult
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsService
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult

/** Real Android authorization -> adapter -> proxy JSON serialization. */
class KwantLocationWireIntegrationTest {
    private val branches = listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz"),
        ProviderBranch(BranchId("128"), "Zamość"),
    )
    private val snapshot = VerifiedProductSnapshot(
        obik = "580", productId = "580", providerId = "kwant-pl",
        name = "Verified synthetic KWANT product",
        storeNumber = "205", branchId = "205",
        stock = 3, grossPrice = null,
        productUrl = "https://kwant.net.pl/produkt/580", verifiedAt = 123L,
    )

    @Test fun unavailableAndMalformedResultsSerializeCanonicalNullRows() = runBlocking {
        val samples = listOf(
            "http_failure" to LocationsHttpResult.Failure(LocationFailure.TRANSPORT),
            "timeout" to LocationsHttpResult.Failure(LocationFailure.TRANSPORT),
            "malformed" to LocationsHttpResult.Success("""{"list":"invalid"}"""),
            "no_trusted_rows" to LocationsHttpResult.Success("""{"list":[]}"""),
            "null_stock" to LocationsHttpResult.Success(
                """{"list":[{"department_id":128,"stock":null}]}""",
            ),
        )
        for ((name, upstream) in samples) {
            // This adapter/wire fixture retains explicit network intent; singleton exact reads are tested separately.
            val fixtureBranches = branches.filter { it.branchId.value == "128" }
            var inventoryCalls = 0
            val adapter = KwantProductLocationsAdapter(
                directory = { ProviderBranchResult.Available(fixtureBranches) },
                fetcher = LocationsHttpFetcher {
                    inventoryCalls++
                    upstream
                },
            )
            val tool = AdvisorLocationsTool(
                locationsService = ProductLocationsService(listOf(adapter)),
                kwantBranches = { ProviderBranchResult.Available(fixtureBranches) },
            )
            val evidence = tool.execute(
                AdvisorLocationArguments("kwant-pl", "580", listOf("Zamość")),
                "kwant-pl", "128",
                "Sprawdź stan produktu 580 we wszystkich oddziałach",
                emptyList(), listOf(snapshot),
            )
            assertEquals(name, "unavailable", evidence.status)
            assertEquals(name, listOf("128"), evidence.checkedIds)
            assertEquals(name, listOf("128"), evidence.missingIds)
            assertEquals(name, emptyList<String>(), evidence.returnedIds)
            assertEquals(name, listOf(AdvisorLocationEntry("128", "Zamość", null)),
                evidence.locations)
            assertEquals(name, null, evidence.verifiedAtMillis)
            assertEquals(name, null, evidence.centralStock)
            assertEquals(name, 1, inventoryCalls)

            MockWebServer().use { proxy ->
                proxy.enqueue(MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"type":"answer","responseId":"resp_final","text":"OK","productRefs":[]}"""))
                val client = AdvisorProxyClient(
                    appToken = "test-token", baseUrl = proxy.url("/"),
                )
                val response = client.continueTurn(
                    responseId = "resp_prior", callId = "call_loc",
                    providerId = "kwant-pl", branchId = "205",
                    continuation = AdvisorToolContinuation.Locations(evidence),
                )
                assertTrue(name, response is AdvisorProxyCallResult.Success)
                val body = Json.parseToJsonElement(proxy.takeRequest().body.readUtf8())
                    .jsonObject
                assertEquals(name, "find_product_locations", body["tool"]!!.jsonPrimitive.content)
                val result = body["result"]!!.jsonObject
                val checked = result["checkedIds"] as JsonArray
                val missing = result["missingIds"] as JsonArray
                val returned = result["returnedIds"] as JsonArray
                val locations = result["locations"] as JsonArray
                assertEquals(name, 1, checked.size)
                assertEquals(name, 1, missing.size)
                assertEquals(name, 0, returned.size)
                assertEquals(name, 1, locations.size)
                val location = locations.first().jsonObject
                assertEquals(name, "128", location["branchId"]!!.jsonPrimitive.content)
                assertEquals(name, "Zamość", location["name"]!!.jsonPrimitive.content)
                assertEquals(name, JsonNull, location["stock"])
                assertEquals(name, JsonNull, result["verifiedAtMillis"])
                assertEquals(name, JsonNull, result["centralStock"])
            }
        }
    }

    @Test fun unavailableDirectorySerializesEmptyConsistentScope() = runBlocking {
        val tool = AdvisorLocationsTool(
            kwantBranches = { ProviderBranchResult.Unavailable(
                pl.lukaszpeciak.towarownik.product.provider.ProductProviderFailure.NETWORK,
                "Synthetic directory failure",
            ) },
        )
        val evidence = tool.execute(
            AdvisorLocationArguments("kwant-pl", "580", emptyList()),
            "kwant-pl", "205", "Sprawdź stan produktu 580 w Zamościu",
            emptyList(), listOf(snapshot),
        )
        assertEquals("unavailable", evidence.status)
        assertEquals("directory_unavailable", evidence.reason)
        assertTrue(evidence.checkedIds.isEmpty())
        assertTrue(evidence.missingIds.isEmpty())
        assertTrue(evidence.locations.isEmpty())
    }
}
