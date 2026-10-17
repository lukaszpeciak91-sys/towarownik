package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.*

class SingleBranchLookupCompatibilityTest {
    private val branches = listOf(ProviderBranch(BranchId("205"), "Nowy Sącz"), ProviderBranch(BranchId("204"), "Tarnów"))
    private val old = VerifiedProductSnapshot(obik = "580", productId = "580", providerId = "kwant-pl", name = "Synthetic MBN116E",
        stock = 999, centralStock = 888, grossPrice = null, productUrl = "https://offline.invalid/product", verifiedAt = 1,
        storeNumber = "205", branchId = "205", articleNumber = "MBN116E/HAG")
    private fun tool(lookup: (ProductRef, BranchId) -> ProviderLookupResult) = AdvisorLocationsTool(
        locationsService = ProductLocationsService(emptyList()), providers = exactLookupProviders(lookup), now = { 1234 })

    @Test fun exactResultsKeepActualAndroidWireShapeAndRendering() = runBlocking {
        for (sample in listOf("positive", "zero", "null", "failure", "wrong_product", "wrong_branch")) {
            var calls = 0
            val stock = when (sample) { "positive" -> 31; "zero" -> 0; else -> null }
            val tool = tool { ref, branch ->
                calls++; assertEquals("580", ref.productId); assertEquals("204", branch.value)
                when (sample) {
                    "failure" -> ProviderLookupResult.Unavailable(ProductProviderFailure.NETWORK, "Synthetic failure")
                    "wrong_product" -> exactLookupFound(ref.copy(productId = "581"), branch, 52)
                    "wrong_branch" -> exactLookupFound(ref, BranchId("205"), 53)
                    else -> exactLookupFound(ref, branch, stock, central = 777)
                }
            }
            val authority = AdvisorInventoryAuthorizer.resolve(AdvisorLocationArguments("kwant-pl", "580", listOf("205")), "kwant-pl", "205",
                "Sprawdź stan MBN116E w Tarnowie", emptyList(), listOf(old), branches, emptyList()) as AdvisorInventoryAuthorization.Inventory
            val evidence = tool.execute(authority)
            assertEquals(1, calls)
            var body = ""
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                body = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
                    .body("""{"type":"answer","responseId":"resp_final","text":"OK","productRefs":[]}""".toResponseBody()).build()
            }.build()
            val result = AdvisorProxyClient(appToken = "synthetic", client = http).continueTurn("resp_stock", "call_stock", "kwant-pl", "205", AdvisorToolContinuation.Locations(evidence))
            assertTrue(result is AdvisorProxyCallResult.Success)
            val json = Json.parseToJsonElement(body).jsonObject
            assertEquals("resp_stock", json["responseId"]!!.jsonPrimitive.content)
            assertEquals("call_stock", json["callId"]!!.jsonPrimitive.content)
            assertEquals("205", json["branchId"]!!.jsonPrimitive.content)
            assertEquals("find_product_locations", json["tool"]!!.jsonPrimitive.content)
            val wire = json["result"]!!.jsonObject
            assertEquals(setOf("providerId", "productId", "status", "reason", "coverage", "checkedIds", "returnedIds", "missingIds", "locations", "verifiedAtMillis", "centralStock"), wire.keys)
            assertEquals(JsonPrimitive(stock), wire["locations"]!!.jsonArray.single().jsonObject["stock"])
            assertEquals(listOf("204"), evidence.checkedIds)
            val text = renderAdvisorLocationDetails(evidence)
            assertTrue(text.contains("204 — Tarnów")); assertFalse(text.contains("999")); assertFalse(text.contains("888"))
            if (stock == null) assertTrue(text.contains("brak potwierdzenia (stan nieznany)"))
            else assertTrue(text.contains("204 — Tarnów — $stock"))
            println("SINGLETON_WIRE $body") // Controlled fixture body for unchanged Worker-validator replay.
        }
    }

    @Test fun controllerPreservesProfileCallIdentityAndOldCardWhitelist() = runBlocking {
        var exactCalls = 0
        val tool = tool { ref, branch -> exactCalls++; exactLookupFound(ref, branch, 31) }
        val initial = AdvisorProxyCallResult.Success(AdvisorProxyResult.LocationToolRequest("resp_stock", "call_stock", AdvisorLocationArguments("kwant-pl", "580", listOf("205"))))
        val controller = AdvisorController(isConfigured = { true }, startAgent = { _, _, _ -> initial }, messageAgent = { _, _, _, _ -> initial },
            continueAgent = { response, call, provider, profile, _, output ->
                assertEquals("resp_stock", response); assertEquals("call_stock", call); assertEquals("kwant-pl", provider); assertEquals("205", profile)
                assertEquals(listOf("204"), (output as AdvisorToolContinuation.Locations).evidence.checkedIds)
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_final", "stale model 999", listOf(AdvisorProductRef("205", "580", "kwant-pl"))))
            }, executeObiTool = { error("no discovery") }, executeProviderTool = { error("no discovery") },
            executeLocationsTool = tool::execute, branchDirectory = { ProviderBranchResult.Available(branches) })
        val result = controller.runTurn("Sprawdź stan MBN116E w Tarnowie", "resp_prior", "205", "kwant-pl", historicalVerifiedProducts = listOf(old)) {} as AdvisorUiState.Success
        assertEquals(1, exactCalls); assertTrue(result.products.isEmpty()); assertFalse(result.text.contains("999")); assertTrue(result.text.contains("204 — Tarnów — 31"))
    }

    @Test fun fourthLogicalCallDoesNotExecuteAnotherExactLookup() = runBlocking {
        var lookups = 0; var continuations = 0
        val tool = tool { ref, branch -> lookups++; exactLookupFound(ref, branch, 31) }
        fun request(n: Int) = AdvisorProxyCallResult.Success(AdvisorProxyResult.LocationToolRequest("resp_$n", "call_$n", AdvisorLocationArguments("kwant-pl", "580", emptyList())))
        val controller = AdvisorController(isConfigured = { true }, startAgent = { _, _, _ -> request(0) }, messageAgent = { _, _, _, _ -> request(0) },
            continueAgent = { response, call, _, profile, _, output ->
                assertEquals("resp_$continuations", response); assertEquals("call_$continuations", call); assertEquals("205", profile)
                continuations++
                val e = (output as AdvisorToolContinuation.Locations).evidence
                if (continuations <= 3) { assertEquals("verified", e.status); request(continuations) }
                else { assertEquals("local_tool_limit_reached", e.reason); AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_final", "done", emptyList())) }
            }, executeObiTool = { error("no discovery") }, executeProviderTool = { error("no discovery") },
            executeLocationsTool = tool::execute, branchDirectory = { ProviderBranchResult.Available(branches) })
        assertTrue(controller.runTurn("Sprawdź stan MBN116E w Tarnowie", null, "205", "kwant-pl", historicalVerifiedProducts = listOf(old)) {} is AdvisorUiState.Success)
        assertEquals(3, lookups); assertEquals(4, continuations)
    }
}
