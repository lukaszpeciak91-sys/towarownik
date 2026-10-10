package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.LocationCoverage
import pl.lukaszpeciak.towarownik.product.provider.LocationCoverageKind
import pl.lukaszpeciak.towarownik.product.provider.LocationStock
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsAdapter
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsResult
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsService
import pl.lukaszpeciak.towarownik.product.provider.ProductRef
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct

/**
 * The real Android proxy response parser consumes the Worker-compatible JSON
 * tool envelope before the Android-only canonical location authorization.
 * Synthetic responses and inventory only; never calls live OBI/Worker.
 */
class AdvisorLocationHintsWireCompatibilityTest {
    @Test
    fun `model locations JSON travels through proxy parser without changing authorized Krakow scope`() =
        runBlocking {
            for (hints in listOf(
                """["Krakowie"]""",
                """["Kraków"]""",
                """["OBI 003"]""",
                """[]""",
            )) {
                MockWebServer().use { server ->
                    server.enqueue(
                        MockResponse()
                            .setHeader("Content-Type", "application/json")
                            .setBody(
                                """
                                {
                                  "type": "tool_request",
                                  "responseId": "resp_locations",
                                  "tool": {
                                    "name": "find_product_locations",
                                    "callId": "call_locations",
                                    "arguments": {
                                      "providerId": "obi-pl",
                                      "productId": "3496072",
                                      "locations": $hints
                                    }
                                  },
                                  "webSearchCalls": 0
                                }
                                """.trimIndent(),
                            ),
                    )
                    val proxy = AdvisorProxyClient(
                        appToken = "test-token",
                        baseUrl = server.url("/"),
                    )
                    val response = proxy.start(
                        message = "Sprawdź w Krakowie",
                        providerId = "obi-pl",
                        branchId = "075",
                    ) as AdvisorProxyCallResult.Success
                    val request = server.takeRequest()
                    assertEquals("1", request.getHeader("X-Taksula-Locations-Capability"))
                    assertEquals("/v1/agent/start", request.path)
                    val location = response.result as AdvisorProxyResult.LocationToolRequest
                    assertEquals("call_locations", location.callId)

                    var reads = 0
                    val adapter = object : ProductLocationsAdapter {
                        override val providerId = OBI_PROVIDER_ID
                        override suspend fun read(
                            ref: ProductRef,
                            requested: List<BranchId>,
                            trustedProduct: ProviderProduct?,
                        ): ProductLocationsResult {
                            reads++
                            val locations = requested.map { id ->
                                LocationStock(ProviderBranch(id, "Fixture"), 2)
                            }
                            return ProductLocationsResult.Available(
                                ref,
                                locations,
                                LocationCoverage(
                                    LocationCoverageKind.REQUESTED_SUBSET,
                                    requested, requested, 1,
                                ),
                                123456L,
                            )
                        }
                    }
                    val tool = AdvisorLocationsTool(
                        locationsService = ProductLocationsService(listOf(adapter)),
                    )
                    val trusted = VerifiedProductSnapshot(
                        obik = "3496072",
                        name = "Fixture",
                        stock = 1,
                        grossPrice = null,
                        productUrl = "https://www.obi.pl/p/3496072",
                        verifiedAt = 100L,
                        storeNumber = "075",
                    )
                    val result = tool.execute(
                        arguments = location.arguments,
                        providerId = "obi-pl",
                        currentBranchId = "075",
                        userText = "Sprawdź w Krakowie",
                        currentVerified = emptyList(),
                        historicalVerified = listOf(trusted),
                    )
                    assertEquals(hints, "verified", result.status)
                    assertEquals(hints, "requested_subset", result.coverage)
                    assertEquals(hints, setOf("019", "072", "003", "059"),
                        result.checkedIds.toSet())
                    assertEquals(hints, 1, reads)
                    assertTrue(result.locations.all { it.stock == 2 })
                }
            }
        }
}
