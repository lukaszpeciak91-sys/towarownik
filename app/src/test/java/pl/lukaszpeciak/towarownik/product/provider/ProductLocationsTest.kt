package pl.lukaszpeciak.towarownik.product.provider

import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.OBI_STORES

/** All quantities below are synthetic, controlled offline fixtures, NOT live OBI/KWANT stock. */
class ProductLocationsTest {
    private val obiRef = ProductRef(OBI_PROVIDER_ID, "3496072")
    private val kwantRef = ProductRef(KWANT_PROVIDER_ID, "580")
    private val ids = OBI_STORES.map { BranchId(it.storeNumber) }
    private val kwantBranches = (201..221).map {
        ProviderBranch(BranchId(it.toString()), "Fixture branch $it")
    }
    private val directory = ProviderBranchResult.Available(kwantBranches)
    private val branchMap = kwantBranches.associateBy { it.branchId }

    private fun obiPayload(ids: List<String>, zeros: Set<String> = emptySet()): String =
        JsonArray(ids.map { id ->
            buildJsonObject {
                put("storeId", id)
                put("availableQuantity", if (id in zeros) 0 else 7)
            }
        }).toString()

    private fun kwantPayload(
        branchIds: List<Int> = (201..221).toList(),
        zeroIds: Set<Int> = setOf(205),
    ): String = JsonObject(
        mapOf(
            "list" to JsonArray(branchIds.map {
                buildJsonObject {
                    put("department_id", it)
                    put("stock", if (it in zeroIds) 0 else 5)
                }
            }),
            // Observed field with UNKNOWN business meaning; the parser ignores it.
            "total_stock" to JsonPrimitive(999999),
            "unit" to JsonPrimitive("szt."),
        ),
    ).toString()

    private fun verifiedKwant(
        fetcher: LocationsHttpFetcher,
        now: () -> Long = { 1234L },
    ) = KwantProductLocationsAdapter(
        fetcher = fetcher,
        directory = { directory },
        // Synthetic URL fixture; NOT the researched actual value of "extended".
        verifiedExtendedValue = "fixtureOnly",
        now = now,
    )

    @Test fun `OBI ten verified IDs preserve shuffled stock and zero`() = runBlocking {
        val wanted = ids.take(10)
        var requests = 0
        val adapter = ObiProductLocationsAdapter(
            fetcher = LocationsHttpFetcher { url ->
                requests++
                assertTrue(trustedLocationsEndpoint(url))
                assertEquals("/api/pdp/v1/stock/3496072", url.encodedPath)
                val requested = url.queryParameter("storeIds")!!.split(",")
                assertEquals(wanted.map { it.value }, requested)
                LocationsHttpResult.Success(
                    obiPayload(requested.reversed(), setOf(wanted[0].value)),
                )
            },
            now = { 777 },
        )
        val result = adapter.read(obiRef, wanted) as ProductLocationsResult.Available
        assertEquals(1, requests)
        assertEquals(LocationCoverageKind.REQUESTED_SUBSET, result.coverage.kind)
        assertEquals(10, result.coverage.requestedIds.size)
        assertEquals(wanted, result.coverage.returnedIds)
        assertEquals(wanted, result.locations.map { it.branch.branchId })
        assertTrue(result.coverage.missingIds.isEmpty())
        assertEquals(0, result.locations.single { it.branch.branchId == wanted[0] }.stock)
        assertEquals(777L, result.verifiedAtMillis)
        assertNull(result.centralStock)
    }

    @Test fun `OBI respects app budget and batches 1 10 11 20 with no per-store fanout`() = runBlocking {
        for ((size, expectedCalls) in listOf(1 to 1, 10 to 1, 11 to 2, 20 to 2)) {
            val calls = mutableListOf<List<String>>()
            val adapter = ObiProductLocationsAdapter(
                fetcher = LocationsHttpFetcher { url ->
                    val segment = url.queryParameter("storeIds")!!.split(",")
                    calls.add(segment)
                    LocationsHttpResult.Success(obiPayload(segment.reversed()))
                },
            )
            val result = adapter.read(obiRef, ids.take(size)) as ProductLocationsResult.Available
            assertEquals(size, result.locations.size)
            assertEquals(ids.take(size), result.locations.map { it.branch.branchId })
            assertEquals(ids.take(size), result.coverage.returnedIds)
            assertEquals(expectedCalls, calls.size)
            assertTrue(calls.all { it.size <= 10 && it.isNotEmpty() })
            assertEquals(expectedCalls, result.coverage.requestCount)
        }
        var calls = 0
        val adapter = ObiProductLocationsAdapter(
            fetcher = LocationsHttpFetcher { calls++; LocationsHttpResult.Success("[]") },
        )
        assertEquals(
            ProductLocationsResult.Invalid(LocationFailure.INVALID_LOCATION_IDS),
            adapter.read(obiRef, ids.take(21)),
        )
        assertEquals(0, calls)
    }

    @Test fun `OBI omitted requested ID remains unknown rather than fabricated zero`() = runBlocking {
        val wanted = ids.take(3)
        val adapter = ObiProductLocationsAdapter(
            fetcher = LocationsHttpFetcher {
                LocationsHttpResult.Success(obiPayload(wanted.take(2).map { id -> id.value }))
            },
        )
        val result = adapter.read(obiRef, wanted) as ProductLocationsResult.Available
        assertEquals(LocationCoverageKind.PARTIAL, result.coverage.kind)
        assertEquals(listOf(wanted[2]), result.coverage.missingIds)
        assertEquals(2, result.locations.size)
        assertTrue(result.locations.none { it.branch.branchId == wanted[2] })
    }

    @Test fun `OBI unexpected duplicate and invalid numeric values fail closed`() = runBlocking {
        val wanted = ids.take(2)
        val ok = obiPayload(wanted.map { it.value })
        val bad = listOf(
            obiPayload(listOf(wanted[0].value, wanted[0].value)),
            obiPayload(listOf(wanted[0].value, ids[30].value)),
            ok.replace("\"availableQuantity\":7", "\"availableQuantity\":-1"),
            ok.replace("\"availableQuantity\":7", "\"availableQuantity\":1.5"),
            ok.replace("\"availableQuantity\":7", "\"availableQuantity\":null"),
            ok.replace("\"availableQuantity\":7", "\"availableQuantity\":\"7\""),
            """{"unexpected":"shape"}""",
            """[{"storeId":"999","availableQuantity":5}]""",
        )
        for (payload in bad) {
            val adapter = ObiProductLocationsAdapter(
                fetcher = LocationsHttpFetcher { LocationsHttpResult.Success(payload) },
            )
            val result = adapter.read(obiRef, wanted) as ProductLocationsResult.Unavailable
            assertEquals(LocationCoverageKind.UNKNOWN, result.coverage!!.kind)
            assertTrue(result.coverage!!.returnedIds.isEmpty())
            assertEquals(LocationFailure.MALFORMED_RESPONSE, result.failure)
        }
    }

    @Test fun `OBI partial transport failure preserves only trusted first batch`() = runBlocking {
        val wanted = ids.take(11)
        var attempt = 0
        val adapter = ObiProductLocationsAdapter(
            fetcher = LocationsHttpFetcher { url ->
                attempt++
                if (attempt == 2) LocationsHttpResult.Failure(LocationFailure.TRANSPORT)
                else LocationsHttpResult.Success(
                    obiPayload(url.queryParameter("storeIds")!!.split(",")),
                )
            },
        )
        val result = adapter.read(obiRef, wanted) as ProductLocationsResult.Available
        assertEquals(2, attempt)
        assertEquals(10, result.locations.size)
        assertEquals(1, result.coverage.failedRequests)
        assertEquals(LocationCoverageKind.PARTIAL, result.coverage.kind)
        assertEquals(listOf(wanted[10]), result.coverage.missingIds)
    }

    @Test fun `OBI rejects cross-provider malformed product unknown store and duplicate inputs before HTTP`() = runBlocking {
        var calls = 0
        val adapter = ObiProductLocationsAdapter(
            fetcher = LocationsHttpFetcher { calls++; LocationsHttpResult.Success("[]") },
        )
        assertEquals(ProductLocationsResult.Invalid(LocationFailure.WRONG_PROVIDER),
            adapter.read(kwantRef, ids.take(1)))
        assertEquals(ProductLocationsResult.Invalid(LocationFailure.INVALID_PRODUCT_ID),
            adapter.read(ProductRef(OBI_PROVIDER_ID, "123"), ids.take(1)))
        assertEquals(ProductLocationsResult.Invalid(LocationFailure.INVALID_LOCATION_IDS),
            adapter.read(obiRef, listOf(BranchId("999"))))
        assertEquals(ProductLocationsResult.Invalid(LocationFailure.INVALID_LOCATION_IDS),
            adapter.read(obiRef, listOf(ids[0], ids[0])))
        assertEquals(0, calls)
    }

    @Test fun `KWANT default blocks unknown extended value before directory and HTTP`() = runBlocking {
        var directoryCalls = 0
        var requests = 0
        val adapter = KwantProductLocationsAdapter(
            fetcher = LocationsHttpFetcher {
                requests++
                LocationsHttpResult.Success(kwantPayload())
            },
            directory = { directoryCalls++; directory },
        )
        assertEquals(
            ProductLocationsResult.Unavailable(LocationFailure.UNVERIFIED_REQUEST_CONTRACT),
            adapter.read(kwantRef, emptyList()),
        )
        assertEquals(0, requests)
        assertEquals(0, directoryCalls)
    }

    @Test fun `KWANT fixture one-shot returns 21 canonical states including zeros`() = runBlocking {
        var requests = 0
        val adapter = verifiedKwant(
            fetcher = LocationsHttpFetcher { url ->
                requests++
                assertTrue(trustedLocationsEndpoint(url))
                assertEquals("/api/front/products/580/departments", url.encodedPath)
                assertEquals("fixtureOnly", url.queryParameter("extended"))
                LocationsHttpResult.Success(
                    kwantPayload(branchIds = (201..221).toList().reversed()),
                )
            },
        )
        val trusted = ProviderProduct(
            ref = kwantRef, branchId = BranchId("205"), name = "Fixture product",
            stock = 0, centralStock = 9375, grossPrice = null, priceScope = null,
            productUrl = "https://kwant.net.pl/produkt/580", ean = null,
        )
        val result = adapter.read(kwantRef, emptyList(), trusted)
            as ProductLocationsResult.Available
        assertEquals(1, requests)
        assertEquals(21, result.locations.size)
        assertEquals(kwantBranches.map { it.branchId }, result.locations.map { it.branch.branchId })
        assertEquals(kwantBranches.map { it.branchId }, result.coverage.returnedIds)
        assertEquals(LocationCoverageKind.ALL_PUBLIC_LOCATIONS, result.coverage.kind)
        assertEquals(0, result.locations.single { it.branch.branchId == BranchId("205") }.stock)
        assertEquals(9375, result.centralStock)
        assertEquals(1234, result.verifiedAtMillis)
        assertTrue(result.coverage.missingIds.isEmpty())
        // Synthetic "total_stock" is 999999 and is never surfaced as central stock.
        val noTrusted = adapter.read(kwantRef, emptyList()) as ProductLocationsResult.Available
        assertNull(noTrusted.centralStock)
    }

    @Test fun `KWANT missing canonical branch yields partial not zero`() = runBlocking {
        val adapter = verifiedKwant(LocationsHttpFetcher {
            LocationsHttpResult.Success(kwantPayload(branchIds = (201..220).toList()))
        })
        val result = adapter.read(kwantRef, emptyList()) as ProductLocationsResult.Available
        assertEquals(LocationCoverageKind.PARTIAL, result.coverage.kind)
        assertEquals(listOf(BranchId("221")), result.coverage.missingIds)
        assertEquals(20, result.locations.size)
    }

    @Test fun `KWANT malformed duplicate unknown negative string and mismatch reject all rows`() = runBlocking {
        val bad = listOf(
            kwantPayload(branchIds = listOf(201, 201)),
            kwantPayload(branchIds = listOf(999)),
            """{"list":[{"department_id":205,"stock":-3}]}""",
            """{"list":[{"department_id":205,"stock":"3"}]}""",
            """{"list":[{"department_id":"205","stock":3}]}""",
            """{"list":[{"department_id":205,"stock":null}]}""",
            """{"list":[{"department_id":205,"stock":0,"product_id":581}]}""",
            """{"product_id":581,"list":[]}""",
            """{"list":"not_an_array"}""",
        )
        for (payload in bad) {
            val adapter = verifiedKwant(LocationsHttpFetcher {
                LocationsHttpResult.Success(payload)
            })
            val result = adapter.read(kwantRef, emptyList())
                as ProductLocationsResult.Unavailable
            assertEquals(LocationFailure.MALFORMED_RESPONSE, result.failure)
            assertEquals(LocationCoverageKind.UNKNOWN, result.coverage!!.kind)
        }
    }

    @Test fun `KWANT rejects invalid ID location and cross-provider without HTTP`() = runBlocking {
        var requests = 0
        val adapter = verifiedKwant(LocationsHttpFetcher {
            requests++
            LocationsHttpResult.Success(kwantPayload())
        })
        assertEquals(ProductLocationsResult.Invalid(LocationFailure.WRONG_PROVIDER),
            adapter.read(obiRef, emptyList()))
        assertEquals(ProductLocationsResult.Invalid(LocationFailure.INVALID_PRODUCT_ID),
            adapter.read(ProductRef(KWANT_PROVIDER_ID, "abc"), emptyList()))
        assertEquals(ProductLocationsResult.Invalid(LocationFailure.INVALID_LOCATION_IDS),
            adapter.read(kwantRef, listOf(BranchId("999"))))
        assertEquals(0, requests)
    }

    @Test fun `service never discovers automatically or mutates WorkingProfile`() = runBlocking {
        var obiCalls = 0
        var kwantCalls = 0
        val profile = WorkingProfile(OBI_PROVIDER_ID, BranchId("075"))
        val service = ProductLocationsService(listOf(
            ObiProductLocationsAdapter(fetcher = LocationsHttpFetcher {
                obiCalls++
                LocationsHttpResult.Success(obiPayload(it.queryParameter("storeIds")!!.split(",")))
            }),
            KwantProductLocationsAdapter(fetcher = LocationsHttpFetcher {
                kwantCalls++
                LocationsHttpResult.Success(kwantPayload())
            }),
        ))
        assertEquals(0, obiCalls)
        assertEquals(0, kwantCalls)
        assertEquals(ProductLocationsResult.UnsupportedProvider,
            service.read(ProductRef(ProviderId("other-pl"), "580")))
        assertEquals(0, obiCalls)
        assertEquals(0, kwantCalls)
        assertTrue(service.read(obiRef, ids.take(1)) is ProductLocationsResult.Available)
        assertEquals(1, obiCalls)
        assertEquals(0, kwantCalls)
        assertEquals(WorkingProfile(OBI_PROVIDER_ID, BranchId("075")), profile)
    }

    @Test fun `transport permits only exact trusted HTTPS product-bound JSON URLs`() {
        val good = listOf(
            "https://www.obi.pl/api/pdp/v1/stock/3496072?storeIds=075",
            "https://services.kwant.net.pl/api/front/products/580/departments?extended=fixtureOnly",
        )
        assertTrue(good.all { trustedLocationsEndpoint(it.toHttpUrl()) })
        val bad = listOf(
            "http://www.obi.pl/api/pdp/v1/stock/3496072?storeIds=075",
            "https://evil.example/api/pdp/v1/stock/3496072?storeIds=075",
            "https://www.obi.pl/api/pdp/v1/stock/3496072/other?storeIds=075",
            "https://www.obi.pl/api/pdp/v1/stock/3496072?storeIds=075&other=1",
            "https://services.kwant.net.pl/api/front/products/580/departments",
            "https://services.kwant.net.pl/api/front/products/580/current?extended=x",
        )
        assertTrue(bad.none { trustedLocationsEndpoint(it.toHttpUrl()) })
    }

    @Test fun `HTTP adapter rejects unexpected redirects content and oversized JSON`() = runBlocking {
        val url = "https://www.obi.pl/api/pdp/v1/stock/3496072?storeIds=075".toHttpUrl()
        for ((status, media, body) in listOf(
            Triple(302, "application/json", "[]"),
            Triple(200, "text/html", "[]"),
            Triple(200, "application/json", "x".repeat(65537)),
        )) {
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder().request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(status).message("fixture")
                        .addHeader("Content-Type", media)
                        .body(body.toResponseBody(media.toMediaType()))
                        .build()
                }.build()
            assertTrue(OkHttpLocationsFetcher(client).get(url) is LocationsHttpResult.Failure)
        }
    }

    @Test fun `KWANT blocking directory lookup runs on injected IO dispatcher`() = runBlocking {
        val dispatcher = Executors.newSingleThreadExecutor { task ->
            Thread(task, "kwant-locations-directory-test")
        }.asCoroutineDispatcher()
        try {
            val callerThread = Thread.currentThread().name
            var directoryThread: String? = null
            var fetchCount = 0
            val adapter = KwantProductLocationsAdapter(
                fetcher = LocationsHttpFetcher {
                    fetchCount++
                    LocationsHttpResult.Success(kwantPayload())
                },
                directory = {
                    directoryThread = Thread.currentThread().name
                    directory
                },
                directoryDispatcher = dispatcher,
                // Fixture-only shape, not a known production extended value.
                verifiedExtendedValue = "fixtureOnly",
            )
            assertTrue(adapter.read(kwantRef, emptyList()) is ProductLocationsResult.Available)
            assertEquals("kwant-locations-directory-test", directoryThread)
            assertFalse(callerThread == directoryThread)
            assertEquals(1, fetchCount)
        } finally {
            dispatcher.close()
        }
    }

    @Test fun `KWANT directory exceptions become unavailable without inventory GET`() = runBlocking {
        var fetchCount = 0
        val adapter = KwantProductLocationsAdapter(
            fetcher = LocationsHttpFetcher {
                fetchCount++
                LocationsHttpResult.Success(kwantPayload())
            },
            directory = { throw IllegalStateException("synthetic directory failure") },
            verifiedExtendedValue = "fixtureOnly",
        )
        assertEquals(
            ProductLocationsResult.Unavailable(LocationFailure.DIRECTORY_UNAVAILABLE),
            adapter.read(kwantRef, emptyList()),
        )
        assertEquals(0, fetchCount)
    }

    @Test fun `KWANT directory cancellation propagates without inventory GET`() = runBlocking {
        var fetchCount = 0
        val adapter = KwantProductLocationsAdapter(
            fetcher = LocationsHttpFetcher {
                fetchCount++
                LocationsHttpResult.Success(kwantPayload())
            },
            directory = { throw CancellationException("synthetic cancellation") },
            verifiedExtendedValue = "fixtureOnly",
        )
        var cancelled = false
        try {
            adapter.read(kwantRef, emptyList())
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue("Directory cancellation must propagate", cancelled)
        assertEquals(0, fetchCount)
    }

}
