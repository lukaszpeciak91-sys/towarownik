package pl.lukaszpeciak.towarownik.agent

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.AdvisorResponseReportEvidenceStore
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.*

/** Synthetic payloads through actual HTTP handling, adapter, authority and Controller. */
class Inc008InventoryDiagnosticsTest {
    private val branches = listOf(ProviderBranch(BranchId("205"), "Nowy Sącz"), ProviderBranch(BranchId("204"), "Tarnów"))
    private val product = VerifiedProductSnapshot(
        obik = "580", productId = "580", providerId = "kwant-pl", name = "Synthetic",
        stock = 398, grossPrice = null, productUrl = "https://kwant.net.pl/produkt/580",
        verifiedAt = 1L, storeNumber = "205", branchId = "205", articleNumber = "MBN116E/HAG",
    )

    private suspend fun lookup(body: String, id: String = "205", status: Int = 200, media: String = "application/json", ioFailure: Boolean = false): AdvisorLocationEvidence {
        // Keep D1 adapter diagnostics on explicit network intent, even with one directory entry.
        val fixtureBranches = branches.filter { it.branchId.value == id }
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            assertEquals("https://services.kwant.net.pl/api/front/products/580/departments?extended=1", chain.request().url.toString())
            assertEquals("GET", chain.request().method)
            assertNull(chain.request().header("Authorization"))
            assertNull(chain.request().header("Cookie"))
            if (ioFailure) throw IOException("SECRET_EXCEPTION_CANARY")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("synthetic")
                .header("Content-Type", media).header("Set-Cookie", "SECRET_COOKIE_CANARY")
                .body(body.toResponseBody()).build()
        }.build()
        val adapter = KwantProductLocationsAdapter(fetcher = OkHttpLocationsFetcher(http), directory = { ProviderBranchResult.Available(fixtureBranches) }, now = { 1234L })
        val tool = AdvisorLocationsTool(ProductLocationsService(listOf(adapter)), kwantBranches = { ProviderBranchResult.Available(fixtureBranches) })
        val evidence = tool.execute(AdvisorLocationArguments("kwant-pl", "580", listOf("wrong_hint")), "kwant-pl", id,
            "Sprawdź stan MBN116E we wszystkich oddziałach", emptyList(), listOf(product))
        assertEquals(1, requests)
        assertEquals("580", evidence.productId)
        assertEquals("kwant-pl", evidence.providerId)
        assertEquals(listOf(id), evidence.authorizedBranchIds)
        assertEquals(listOf(id), evidence.checkedIds)
        return evidence
    }

    private fun report(evidence: AdvisorLocationEvidence): String = AdvisorResponseReportEvidenceStore().also {
        it.record(33, 10, listOf(evidence))
    }.get(33, 10)!!

    @Test fun positiveAndZeroRemainVerifiedAndRetainObservationTime() = runBlocking {
        for (id in listOf("205", "204")) for (stock in listOf(398, 0)) {
            val evidence = lookup("""{"list":[{"department_id":$id,"stock":$stock}]}""", id)
            assertEquals("verified", evidence.status)
            assertNull(evidence.reason)
            assertEquals(stock, evidence.locations.single().stock)
            val text = report(evidence)
            assertTrue(text.contains("requested_ids=[$id]"))
            assertTrue(text.contains("returned_ids=[$id] missing_ids=[]"))
            assertTrue(text.contains("verified_at_millis=1234"))
            assertTrue(text.contains(if (stock == 0) "positive:0,zero:1,unknown:0" else "positive:1,zero:0,unknown:0"))
        }
    }

    @Test fun nullMissingStockAndMissingRequestedRowExposeNoTrustedRows() = runBlocking {
        for (body in listOf("""{"list":[{"department_id":205,"stock":null}]}""", """{"list":[{"department_id":205}]}""", """{"list":[]}""")) {
            val evidence = lookup(body)
            assertEquals("no_trusted_rows", evidence.reason)
            assertTrue(report(evidence).contains("coverage=partial"))
            assertTrue(report(evidence).contains("returned_ids=[] missing_ids=[205]"))
            assertEquals(200, evidence.httpDiagnostic!!.status)
            assertNull(evidence.verifiedAtMillis)
            assertTrue(renderAdvisorLocationDetails(evidence).contains("brak potwierdzenia (stan nieznany)"))
        }
    }

    @Test fun malformedJsonExtraMetadataAndWrongIdentityKeepCategoryWithoutRawBody() = runBlocking {
        for (body in listOf("{SECRET_RAW_CANARY", """{"list":[{"department_id":205,"stock":398}],"secret":"SECRET_RAW_CANARY"}""", """{"product_id":581,"list":[{"department_id":205,"stock":398}]}""")) {
            val evidence = lookup(body)
            assertEquals("malformed_response", evidence.reason)
            val text = report(evidence)
            assertTrue(text.contains("product_id=580"))
            assertTrue(text.contains("http_status=200 content_type=json"))
            assertFalse(text.contains("SECRET_"))
            assertFalse(text.contains("\"list\""))
            assertFalse(text.contains("581"))
        }
    }

    @Test fun httpFailureRetainsOnlyStatusAndContentTypeClass() = runBlocking {
        val evidence = lookup("SECRET_RAW_CANARY", status = 403, media = "text/html; session=SECRET_HEADER_CANARY")
        assertEquals("transport", evidence.reason)
        val text = report(evidence)
        assertTrue(text.contains("http_status=403 content_type=html"))
        assertFalse(text.contains("SECRET_"))
    }

    @Test fun ioFailureHasNoInventedHttpStatusOrExceptionText() = runBlocking {
        val evidence = lookup("", ioFailure = true)
        assertEquals("transport", evidence.reason)
        assertNull(evidence.httpDiagnostic)
        assertTrue(report(evidence).contains("http_status=unavailable content_type=unavailable"))
        assertFalse(report(evidence).contains("SECRET_"))
    }

    @Test fun nonstandardStatusDoesNotThrowFromDiagnosticCapture() = runBlocking {
        val evidence = lookup("", status = 600)
        assertEquals("transport", evidence.reason)
        assertNull(evidence.httpDiagnostic)
    }

    @Test fun diagnosticMetadataDoesNotChangeWorkerContinuationBody() = runBlocking {
        val evidence = lookup("{", status = 200)
        var body = ""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                .body("""{"type":"answer","responseId":"resp_final","text":"OK","productRefs":[]}""".toResponseBody()).build()
        }.build()
        val client = AdvisorProxyClient(appToken = "synthetic", client = http)
        client.continueTurn("resp_loc", "call_loc", "kwant-pl", "205", AdvisorToolContinuation.Locations(evidence))
        assertTrue(body.contains("find_product_locations"))
        assertTrue(body.contains("malformed_response"))
        assertFalse(body.contains("httpDiagnostic")); assertFalse(body.contains("authorizedBranchIds"))
        assertFalse(body.contains("http_status")); assertFalse(body.contains("contentType"))
    }

    @Test fun nonJsonResponseRemainsMalformedAndUnknownContentTypeIsNotEchoed() = runBlocking {
        val evidence = lookup("SECRET_RAW_CANARY", media = "application/SECRET_HEADER_CANARY")
        assertEquals("malformed_response", evidence.reason)
        assertTrue(report(evidence).contains("content_type=other"))
        assertFalse(report(evidence).contains("SECRET_"))
    }

    @Test fun directoryFailureRetainsAuthorizedScopeButDoesNotInventCheckedRows() = runBlocking {
        val authority = AdvisorInventoryAuthorizer.resolve(AdvisorLocationArguments("kwant-pl", "580", emptyList()), "kwant-pl", "205",
            "Sprawdź stan MBN116E we wszystkich oddziałach", emptyList(), listOf(product), branches, emptyList()) as AdvisorInventoryAuthorization.Inventory
        val adapter = KwantProductLocationsAdapter(directory = { error("SECRET_DIRECTORY_CANARY") }, fetcher = LocationsHttpFetcher { error("no HTTP") })
        val evidence = AdvisorLocationsTool(ProductLocationsService(listOf(adapter))).execute(authority)
        assertEquals("directory_unavailable", evidence.reason)
        assertTrue(evidence.checkedIds.isEmpty())
        assertTrue(report(evidence).contains("requested_ids=[205,204]"))
        assertFalse(report(evidence).contains("SECRET_"))
    }

    @Test fun cancellationStillPropagatesAndProducesNoReportResult() = runBlocking {
        val adapter = KwantProductLocationsAdapter(directory = { ProviderBranchResult.Available(branches) }, fetcher = LocationsHttpFetcher { throw CancellationException("cancel") })
        try { adapter.read(ProductRef(KWANT_PROVIDER_ID, "580"), listOf(BranchId("205"))); fail("cancellation swallowed") }
        catch (_: CancellationException) { }
    }

    @Test fun controllerCarriesOnlyThisTurnsEvidenceAndPreservesContinuationIdentity() = runBlocking {
        var inventory = true
        val expected = lookup("""{"list":[]}""")
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { _, _, _ -> AdvisorProxyCallResult.Success(AdvisorProxyResult.LocationToolRequest("resp_loc", "call_loc", AdvisorLocationArguments("kwant-pl", "580", emptyList()))) },
            messageAgent = { _, _, _, _ -> AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_next", "next", emptyList())) },
            continueAgent = { response, call, provider, branch, _, continuation ->
                assertEquals("resp_loc", response); assertEquals("call_loc", call)
                assertEquals("kwant-pl", provider); assertEquals("205", branch)
                assertEquals(expected, (continuation as AdvisorToolContinuation.Locations).evidence)
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_final", "model", emptyList()))
            },
            executeObiTool = { error("not discovery") }, executeProviderTool = { error("not discovery") },
            branchDirectory = { ProviderBranchResult.Available(branches) },
            executeLocationsTool = { check(inventory); expected },
        )
        val one = controller.runTurn("Sprawdź stan MBN116E w Nowym Sączu", null, "205", "kwant-pl", historicalVerifiedProducts = listOf(product), onState = {}) as AdvisorUiState.Success
        assertEquals(listOf(expected), one.locationReportEvidence)
        assertEquals(renderAdvisorLocationDetails(expected), one.text)
        inventory = false
        val two = controller.runTurn("Dziękuję", "resp_final", "205", "kwant-pl", onState = {}) as AdvisorUiState.Success
        assertTrue(two.locationReportEvidence.isEmpty())
        assertEquals(listOf(expected), one.locationReportEvidence)
    }
}
