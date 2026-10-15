package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.*
import pl.lukaszpeciak.towarownik.product.provider.*

/** Actual Controller/discovery/location adapters; all catalog and HTTP facts are synthetic. */
class Inc007MultiTurnRecoveryTest {
    private val kwant = listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz"),
        ProviderBranch(BranchId("203"), "Bochnia"),
        ProviderBranch(BranchId("206"), "Mielec"),
        ProviderBranch(BranchId("204"), "Tarnów"),
    )
    private val obi = OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }

    private inner class Harness(val provider: String = "kwant-pl") {
        val profile = if (provider == "obi-pl") "075" else "205"
        val history = mutableListOf<AdvisorLocationHistoryMessage>()
        val lookups = mutableListOf<Pair<String, String>>()
        val queries = mutableListOf<String>()
        val gets = mutableListOf<String>()
        val outputs = mutableListOf<AdvisorToolContinuation>()
        val ids = mutableListOf<Pair<String, String>>()
        var directory = if (provider == "obi-pl") obi else kwant
        var directoryAvailable = true
        var id = if (provider == "obi-pl") "6346951" else "77556"
        var article = "A9Z01440/SCH"
        var modelId = id
        var modelProvider = provider
        var modelBranches = listOf("999")
        var verificationFailure = false
        var recoverVerificationOnContinue = false
        var inventoryFailure = false
        var corruptIdentity = false
        var duplicateCandidates = false
        var stock: Int? = 0
        var plan = "locations"
        var phase = 0
        var turnNumber = 0
        var previous: String? = null
        val branches: () -> ProviderBranchResult = {
            if (directoryAvailable) ProviderBranchResult.Available(directory)
            else ProviderBranchResult.Unavailable(ProductProviderFailure.NETWORK, "Synthetic unavailable")
        }
        val catalog = object : ProductProvider {
            override val providerId = ProviderId(provider)
            override fun branches() = this@Harness.branches()
            override fun search(query: String, maxResults: Int): ProviderSearchResult {
                queries += query
                val products = listOf(ProviderProductCandidate(ProductRef(providerId, id), "Synthetic", article)) +
                    if (duplicateCandidates) listOf(ProviderProductCandidate(ProductRef(providerId, "77557"), "Synthetic", article)) else emptyList()
                return ProviderSearchResult.Candidates(products)
            }
            override fun lookup(ref: ProductRef, branchId: BranchId): ProviderLookupResult {
                lookups += ref.productId to branchId.value
                return if (verificationFailure) ProviderLookupResult.Unavailable(ProductProviderFailure.NETWORK, "Synthetic failure")
                else ProviderLookupResult.Found(ProviderProduct(
                    ProductRef(providerId, if (corruptIdentity) "corrupt" else ref.productId),
                    branchId, "Synthetic", stock, centralStock = 53, grossPrice = BigDecimal.ONE,
                    priceScope = ProviderPriceScope.ONLINE, productUrl = "https://offline.invalid/product",
                    ean = null, articleNumber = article,
                ))
            }
        }
        val discovery = FindProviderProductsTool(ProductProviderRegistry(listOf(catalog)), now = { 1000L })
        val obiDiscovery = FindObiProductsTool(
            searchProducts = { query ->
                queries += query
                ProductSearchResult.Candidates(listOf(ProductSearchCandidate(id, "Synthetic")))
            },
            lookupObik = { requested, branch ->
                lookups += requested to branch
                if (verificationFailure) ProductLookupResult.Unavailable(ProductLookupFailure.NETWORK, "Synthetic failure")
                else ProductLookupResult.Found(LocalProduct(
                    requested, "Synthetic", stock, BigDecimal.ONE, "https://offline.invalid/product", null, branch,
                ))
            }, now = { 1000L },
        )
        val fetcher = LocationsHttpFetcher { url ->
            gets += url.toString()
            if (inventoryFailure) LocationsHttpResult.Failure(LocationFailure.TRANSPORT)
            else if (provider == "obi-pl") LocationsHttpResult.Success(
                url.queryParameter("storeIds")!!.split(",").joinToString(",", "[", "]") {
                    """{"storeId":"$it","availableQuantity":7}"""
                },
            ) else {
                val exact = url.pathSegments[url.pathSegments.indexOf("products") + 1]
                LocationsHttpResult.Success("""{"product_id":$exact,"list":[{"department_id":203,"stock":0},{"department_id":206,"stock":11},{"department_id":204,"stock":13},{"department_id":205,"stock":17}]}""")
            }
        }
        val locations = AdvisorLocationsTool(
            ProductLocationsService(listOf(
                KwantProductLocationsAdapter(fetcher, branches, now = { 2000L }),
                ObiProductLocationsAdapter(fetcher, branches, now = { 2000L }),
            )), branches, branches,
        )
        fun request(discover: Boolean = false): AdvisorProxyCallResult.Success {
            val response = "resp_" + turnNumber + "_" + phase
            val call = "call_" + turnNumber + "_" + phase
            return AdvisorProxyCallResult.Success(if (discover || plan == "discovery")
                AdvisorProxyResult.ToolRequest(response, call, AdvisorToolArguments(
                    profile, listOf(AdvisorToolQuery(if (provider == "obi-pl") id else article, 5)), modelProvider,
                ))
            else AdvisorProxyResult.LocationToolRequest(response, call, AdvisorLocationArguments(modelProvider, modelId, modelBranches)))
        }
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { _, p, b -> assertEquals(provider, p); assertEquals(profile, b); request() },
            messageAgent = { prior, _, p, b ->
                assertEquals(previous, prior); assertEquals(provider, p); assertEquals(profile, b); request()
            },
            continueAgent = { response, call, p, b, _, output ->
                assertEquals("resp_" + turnNumber + "_" + phase, response)
                assertEquals("call_" + turnNumber + "_" + phase, call)
                assertEquals(provider, p); assertEquals(profile, b)
                ids += response to call
                outputs += output
                phase++
                if (recoverVerificationOnContinue && phase == 1) verificationFailure = false
                when {
                    plan in listOf("recover", "recovery_then_discovery") && phase == 1 -> request(discover = true)
                    plan == "recover" && phase == 2 -> { modelId = id; request() }
                    plan == "repeat" && phase < 3 -> request()
                    else -> {
                        val ref = lookups.lastOrNull()?.let { listOf(AdvisorProductRef(it.second, it.first, provider)) }.orEmpty()
                        AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("final_$turnNumber", "Model stale stock 99 / branch 999", ref))
                    }
                }
            },
            executeObiTool = obiDiscovery::execute,
            executeProviderTool = discovery::execute,
            executeLocationsTool = locations::execute,
            branchDirectory = { branches() },
        )
        suspend fun turn(input: String, chosen: String = "locations"): AdvisorUiState.Success {
            turnNumber++; phase = 0; plan = chosen
            val cards = history.takeLast(12).asReversed().filter { it.role == "ASSISTANT" && it.products.isNotEmpty() }
                .take(3).flatMap { it.products }
            val result = controller.runTurn(input, previous, profile, provider, historicalVerifiedProducts = cards,
                locationHistory = history.takeLast(12)) {} as AdvisorUiState.Success
            history += AdvisorLocationHistoryMessage("USER", input)
            history += AdvisorLocationHistoryMessage("ASSISTANT", result.text, result.products)
            previous = result.responseId
            return result
        }
        fun evidence() = (outputs.last() as AdvisorToolContinuation.Locations).evidence
        suspend fun anchor() = turn("sprawdź stan A9Z01440/SCH w Bochni", "discovery")
    }

    @Test fun bareKwantIgnoresGuessedIdAndUsesFreshSavedProfileFacts() = runBlocking {
        val h = Harness(); h.modelId = "99999"
        val result = h.turn(h.article)
        assertEquals(listOf("77556" to "205"), h.lookups)
        assertEquals(listOf(h.article), h.queries)
        assertTrue(h.gets.isEmpty()); assertEquals("77556", h.evidence().productId)
        assertEquals(listOf("205"), h.evidence().checkedIds)
        assertEquals(0, result.products.single().stock); assertEquals(53, h.evidence().centralStock)
        assertEquals(1000L, h.evidence().verifiedAtMillis); assertFalse(result.text.contains("99"))
    }
    @Test fun bareObikUsesExactLookupAndNoInventoryScan() = runBlocking {
        val h = Harness("obi-pl"); h.modelId = "9999999"
        assertEquals("6346951", h.turn(h.id).products.single().effectiveProductId)
        assertEquals(listOf("6346951" to "075"), h.lookups); assertTrue(h.queries.isEmpty()); assertTrue(h.gets.isEmpty())
    }
    @Test fun bareEanUsesExistingDiscoveryWithoutGuessingInventoryIdentity() = runBlocking {
        val h = Harness("obi-pl"); h.modelId = "9999999"
        assertEquals("6346951", h.turn("4007871634695").products.single().effectiveProductId)
        assertEquals(listOf("4007871634695"), h.queries); assertTrue(h.gets.isEmpty())
    }
    @Test fun fortyFiveConsecutiveMistakenLocationSelectionsResetEachTurnForBothProviders() = runBlocking {
        for (p in listOf("obi-pl", "kwant-pl")) {
            val h = Harness(p)
            repeat(45) { i ->
                h.id = (if (p == "obi-pl") 6346951 + i else 77556 + i).toString()
                h.article = "A9Z" + (1440 + i) + "/SCH"; h.modelId = "99999"
                val result = h.turn(if (p == "obi-pl") h.id else h.article)
                assertEquals(h.id, result.products.single().effectiveProductId)
                assertEquals(h.profile, result.products.single().effectiveBranchId)
            }
            assertEquals(45, h.lookups.size); assertTrue(h.gets.isEmpty()); assertEquals(45, h.ids.size)
        }
    }
    @Test fun priceBreakDoesNotBlockNewProductOrItsOwnLocationFollowUp() = runBlocking {
        val h = Harness(); h.anchor(); h.turn("pokaż ceny")
        h.id = "77557"; h.article = "A9Z01441/SCH"; h.modelId = "77556"
        h.turn(h.article); h.modelId = h.id
        h.turn("a w Tarnowie?")
        assertEquals("77557", h.evidence().productId); assertEquals(listOf("204"), h.evidence().checkedIds)
        assertTrue(h.gets.single().contains("/77557/departments?")); assertEquals(13, h.evidence().locations.single().stock)
    }
    @Test fun providerVerificationFailureDoesNotPoisonNextIndependentSearch() = runBlocking {
        val h = Harness(); h.verificationFailure = true
        h.turn(h.article); assertEquals("unavailable", h.evidence().status)
        h.verificationFailure = false; h.id = "77557"; h.article = "A9Z01441/SCH"
        assertEquals("77557", h.turn(h.article).products.single().effectiveProductId); assertTrue(h.gets.isEmpty())
    }
    @Test fun successfulFinalDiscoveryClearsEarlierRecoveryRefusalWithinSameTurn() = runBlocking {
        val h = Harness(); h.verificationFailure = true; h.recoverVerificationOnContinue = true
        val result = h.turn(h.article, "recovery_then_discovery")
        assertEquals(2, h.lookups.size); assertEquals(2, h.outputs.size)
        assertTrue(h.outputs.last() is AdvisorToolContinuation.Verified)
        assertEquals("77556", result.products.single().effectiveProductId)
        assertTrue(result.text, result.text.contains("205 — Nowy Sącz — 0"))
        assertFalse(result.text.contains("99")); assertTrue(h.gets.isEmpty())
    }
    @Test fun naturalRetryAndFillerKeepFreshExactProductAndUserScope() = runBlocking {
        val h = Harness(); h.anchor(); h.turn("a w oddziale w Mielcu?")
        h.turn("nie sprawdzisz mi w Mielcu?"); assertEquals(listOf("206"), h.evidence().checkedIds)
        h.turn("dobrze a w oddziale Tarnów?"); assertEquals(listOf("204"), h.evidence().checkedIds)
        assertEquals(3, h.gets.size); assertTrue(h.gets.all { it.contains("/77556/departments?") })
    }
    @Test fun neutralPriceTurnRetainsUniqueReferentForNaturalRetry() = runBlocking {
        val h = Harness(); h.anchor(); h.turn("pokaż ceny"); h.turn("nie sprawdzisz mi w Mielcu?")
        assertEquals(1, h.gets.size); assertEquals(listOf("206"), h.evidence().checkedIds)
        assertTrue(h.gets.single().contains("/77556/departments?")); assertEquals(11, h.evidence().locations.single().stock)
    }
    @Test fun adviceAndPriceTailsCannotBecomeLocationClarifications() = runBlocking {
        for (input in listOf("dobrze a w oddziale Tarnów pokaż ceny", "nie sprawdzisz mi w Mielcu jak podłączyć pompę",
            "dobrze a w Tarnowie kompatybilność", "dobrze", "nie sprawdzisz mi tego?")) {
            val h = Harness(); h.anchor(); h.turn(input)
            assertTrue(input, h.gets.isEmpty()); assertEquals("location_intent_required", h.evidence().reason)
        }
    }
    @Test fun assistantSuggestionsCannotEstablishThereOrInventoryIntent() = runBlocking {
        val h = Harness(); h.history += AdvisorLocationHistoryMessage("ASSISTANT", "Sprawdź w Tarnowie")
        h.turn("a tam?"); assertTrue(h.gets.isEmpty()); assertEquals("location_intent_required", h.evidence().reason)
    }
    @Test fun uniqueUserThereIgnoresNewAssistantLocationSuggestion() = runBlocking {
        val h = Harness(); h.anchor(); h.history += AdvisorLocationHistoryMessage("ASSISTANT", "Może w Tarnowie")
        h.turn("a tam?"); assertEquals(listOf("203"), h.evidence().checkedIds); assertEquals(1, h.gets.size)
    }
    @Test fun explicitNewArticleBeforeCommandCannotLeakOldProduct() = runBlocking {
        val h = Harness(); h.anchor(); h.turn("A9Z01441/SCH weź sprawdź stan w Tarnowie")
        assertTrue(h.gets.isEmpty()); assertEquals("untrusted_product", h.evidence().reason)
    }
    @Test fun unknownAndKnownArticleTogetherStayFailClosed() = runBlocking {
        val h = Harness(); h.anchor(); h.turn("A9Z01440/SCH A9Z01441/SCH sprawdź stan w Tarnowie")
        assertTrue(h.gets.isEmpty()); assertEquals("untrusted_product", h.evidence().reason)
    }
    @Test fun wrongProviderDoesNotStartRecoveryOrInventory() = runBlocking {
        val h = Harness(); h.modelProvider = "obi-pl"; h.turn(h.article)
        assertTrue(h.lookups.isEmpty()); assertTrue(h.gets.isEmpty()); assertEquals("wrong_provider", h.evidence().reason)
    }
    @Test fun unavailableAndDuplicateDirectoryCannotStartRecovery() = runBlocking {
        for (available in listOf(false, true)) {
            val h = Harness(); h.directoryAvailable = available
            if (available) h.directory = h.directory + h.directory.first()
            h.turn(h.article); assertTrue(h.lookups.isEmpty()); assertTrue(h.gets.isEmpty())
            assertEquals("directory_unavailable", h.evidence().reason)
        }
    }
    @Test fun ambiguousAndUnknownUserScopeNeverStartRecoveryDiscoveryOrInventory() = runBlocking {
        for ((provider, city) in listOf("obi-pl" to "Krakowie", "kwant-pl" to "Atlantydzie")) {
            val h = Harness(provider); h.turn("sprawdź stan produktu " + h.id + " w " + city, "recover")
            assertTrue(h.lookups.isEmpty()); assertTrue(h.gets.isEmpty())
        }
    }
    @Test fun rejectedLocationDiscoveryRetryPreservesExplicitTarnowWithinThreeCalls() = runBlocking {
        val h = Harness(); h.modelId = "99999"
        h.turn("A9Z01440/SCH weź sprawdź stan w Tarnowie", "recover")
        assertEquals(3, h.outputs.size); assertEquals(3, h.ids.distinct().size)
        assertEquals(listOf("77556" to "204"), h.lookups); assertEquals(1, h.gets.size)
        assertEquals("77556", h.evidence().productId); assertEquals(listOf("204"), h.evidence().checkedIds)
    }
    @Test fun recoveryCountsAgainstOriginalThreeCallCap() = runBlocking {
        val h = Harness(); h.turn(h.article, "repeat")
        assertEquals(1, h.lookups.size); assertEquals(3, h.outputs.size)
        assertEquals("local_tool_limit_reached", h.evidence().reason); assertTrue(h.gets.isEmpty())
    }
    @Test fun corruptedVerifiedIdentityCannotProduceCardsOrInventory() = runBlocking {
        val h = Harness(); h.corruptIdentity = true
        assertTrue(h.turn(h.article).products.isEmpty()); assertTrue(h.gets.isEmpty())
        assertEquals("untrusted_product", h.evidence().reason)
    }
    @Test fun nullStockRemainsUnknownAfterSuccessfulIdentityDiscovery() = runBlocking {
        val h = Harness(); h.stock = null
        val result = h.turn(h.article)
        assertNull(result.products.single().stock); assertEquals("unavailable", h.evidence().status)
        assertEquals(listOf("205"), h.evidence().missingIds); assertTrue(h.evidence().returnedIds.isEmpty())
        assertTrue(result.text.contains("nie oznacza stanu 0")); assertTrue(h.gets.isEmpty())
    }
    @Test fun ambiguousExactCatalogMatchesDoNotSelectFirstProduct() = runBlocking {
        val h = Harness(); h.duplicateCandidates = true
        assertTrue(h.turn(h.article).products.isEmpty()); assertEquals(2, h.lookups.size)
        assertEquals("ambiguous_product", h.evidence().reason); assertTrue(h.gets.isEmpty())
    }
    @Test fun unsupportedBareOrMeasurementTextDoesNotStartNativeDiscovery() = runBlocking {
        for (input in listOf("203", "1000W", "203x205mm", "pokaż ceny A9Z01440/SCH", "jak zamontować A9Z01440/SCH w Tarnowie")) {
            val h = Harness(); h.turn(input)
            assertTrue(input, h.lookups.isEmpty()); assertTrue(h.gets.isEmpty())
        }
    }
    @Test fun providerFailureOnRetryIsSpecificAndNeverSubstitutesOldStock() = runBlocking {
        val h = Harness(); h.anchor(); h.inventoryFailure = true
        val result = h.turn("nie sprawdzisz mi w Mielcu?")
        assertEquals("transport", h.evidence().reason); assertEquals(listOf("206"), h.evidence().checkedIds)
        assertNull(h.evidence().locations.single().stock); assertEquals(1, h.gets.size)
        assertFalse(result.text.contains("99")); assertTrue(result.text.contains("nie oznacza stanu 0"))
    }
    @Test fun actualWireRecoveryKeepsOriginalOpaqueIdsAndStrictLocationShape() = runBlocking {
        for (provider in listOf("obi-pl", "kwant-pl")) for (unknown in listOf(false, true)) {
            val h = Harness(provider); if (unknown) h.stock = null
            h.turn(if (provider == "obi-pl") h.id else h.article)
            MockWebServer().use { server ->
                val proxy = AdvisorProxyClient(appToken = "test-token", baseUrl = server.url("/"))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"type":"answer","responseId":"final_wire","text":"Synthetic","productRefs":[]}"""))
                val output = h.outputs.single()
                val result = if (provider == "obi-pl") proxy.continueTurn("opaque response", "opaque call", h.profile, output)
                    else proxy.continueTurn("opaque response", "opaque call", provider, h.profile, output, 3)
                assertTrue(result is AdvisorProxyCallResult.Success)
                val raw = server.takeRequest().body.readUtf8()
                val body = Json.parseToJsonElement(raw).jsonObject
                assertEquals("opaque response", body["responseId"]!!.jsonPrimitive.content)
                assertEquals("opaque call", body["callId"]!!.jsonPrimitive.content)
                assertEquals(h.profile, body[if (provider == "obi-pl") "storeNumber" else "branchId"]!!.jsonPrimitive.content)
                println("INC007_R2_WIRE=$raw")
            }
        }
    }
}
