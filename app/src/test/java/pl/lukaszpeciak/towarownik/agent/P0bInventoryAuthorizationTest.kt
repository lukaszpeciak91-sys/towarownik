package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.*
import pl.lukaszpeciak.towarownik.product.provider.*

/** Actual Controller/discovery/location adapters; all catalog and HTTP facts are synthetic. */
class P0bInventoryAuthorizationTest {
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

    private fun trace(h: Harness, input: String) {
        println("P0B_TRACE provider=${h.provider} input=$input result=${h.evidence().status}/${h.evidence().reason} product=${h.evidence().productId} checked=${h.evidence().checkedIds} HTTP=${h.gets} lookups=${h.lookups}")
    }
    @Test fun colloquialCurrentIdentityUsesCanonicalUserBranch() = runBlocking {
        for (prefix in listOf("Czy dostaniemy", "Czy jest", "Macie może", "Sprawdzisz mi", "Nie sprawdzisz mi")) {
            val h = Harness(); h.anchor()
            val input = "$prefix A9Z01440/SCH w Nowym Sączu?"
            h.turn(input); trace(h, input)
            assertEquals(input, listOf("205"), h.evidence().checkedIds)
            assertEquals(input, "77556", h.evidence().productId)
            assertEquals(input, 1, h.gets.size)
            assertTrue(h.gets.single().contains("/77556/departments?"))
            assertEquals(17, h.evidence().locations.single().stock)
        }
    }
    @Test fun priceTurnDoesNotEraseUniqueUserSelectedProduct() = runBlocking {
        for (provider in listOf("kwant-pl", "obi-pl")) {
            val h = Harness(provider)
            val anchor = if (provider == "kwant-pl") "sprawdź stan A9Z01440/SCH w Bochni" else "sprawdź stan produktu 6346951 w OBI 075"
            h.turn(anchor, "discovery"); h.turn("pokaż ceny")
            assertTrue("Price alone cannot read stock", h.gets.isEmpty())
            val input = if (provider == "kwant-pl") "nie sprawdzisz mi w Mielcu?" else "nie sprawdzisz mi w Krakowie na Wielickiej?"
            h.turn(input); trace(h, input)
            assertEquals(listOf(if (provider == "kwant-pl") "206" else "003"), h.evidence().checkedIds)
            assertEquals(1, h.gets.size)
            assertEquals(h.id, h.evidence().productId)
            assertEquals(if (provider == "kwant-pl") 11 else 7, h.evidence().locations.single().stock)
        }
    }
    @Test fun newerUnknownUserIdentityCannotReviveOlderVerifiedProduct() = runBlocking {
        val h = Harness(); h.anchor()
        h.turn("Czy dostaniemy A9Z01441/SCH w Nowym Sączu?")
        h.turn("a w Tarnowie?"); trace(h, "unknown B then Tarnów")
        assertTrue(h.gets.isEmpty())
        assertTrue(h.evidence().reason in listOf("untrusted_product", "ambiguous_product"))
    }
    @Test fun newerVerifiedIdentityWinsDespiteStaleModelSelection() = runBlocking {
        val h = Harness(); h.anchor()
        h.id = "77557"; h.article = "A9Z01441/SCH"; h.modelId = "77556"
        h.turn(h.article); h.turn("a w Tarnowie?")
        assertTrue(h.gets.isEmpty()) // old model product must be rejected, never silently executed
        h.modelId = "77557"; h.turn("a w Tarnowie?"); trace(h, "verified B retry")
        assertEquals(listOf("204"), h.evidence().checkedIds)
        assertEquals(1, h.gets.size); assertTrue(h.gets.single().contains("/77557/departments?"))
    }
    @Test fun currentIdentityOverridesHistoryOnColloquialRecovery() = runBlocking {
        val h = Harness(); h.anchor(); h.id = "77557"; h.article = "A9Z01441/SCH"; h.modelId = "77556"
        h.turn("Czy dostaniemy A9Z01441/SCH w Tarnowie?", "recover")
        trace(h, "new B locations discovery retry")
        assertEquals(listOf("204"), h.evidence().checkedIds)
        assertEquals("77557", h.evidence().productId)
        assertEquals(1, h.gets.size); assertTrue(h.gets.single().contains("/77557/departments?"))
        assertEquals(4, h.ids.size) // anchor + three current-turn continuations
    }
    @Test fun priceOnlyAndAdviceNeverAuthorizeStock() = runBlocking {
        for (input in listOf("pokaż ceny", "pokaż ceny A9Z01440/SCH w Tarnowie", "jak zamontować A9Z01440/SCH w Tarnowie", "a w Tarnowie pokaż ceny", "dobrze", "nie sprawdzisz mi tego?")) {
            val h = Harness(); h.anchor(); h.turn(input); trace(h, input)
            assertTrue(input, h.gets.isEmpty())
        }
    }
    @Test fun genuineUnknownAndConflictingLocationsNeverReadStock() = runBlocking {
        for ((p, place) in listOf("kwant-pl" to "Atlantydzie", "obi-pl" to "Atlantydzie", "obi-pl" to "Krakowie", "obi-pl" to "Krakowie market OBI 075")) {
            val h = Harness(p)
            h.turn("sprawdź stan produktu ${h.id}", "discovery")
            h.turn("Czy dostaniemy produkt ${h.id} w $place?"); trace(h, place)
            assertTrue(place, h.gets.isEmpty())
        }
    }
    @Test fun modelLocationCannotGrantOrVetoUserScope() = runBlocking {
        for (hints in listOf(listOf("999"), listOf("Tarnów"), listOf("Atlantyda"))) {
            val h = Harness(); h.anchor(); h.modelBranches = hints
            h.turn("Czy dostaniemy A9Z01440/SCH w Nowym Sączu?"); trace(h, "hints=$hints")
            assertEquals(listOf("205"), h.evidence().checkedIds); assertEquals(1, h.gets.size)
        }
    }
    @Test fun fabricatedProductAndWrongProviderNeverReadStock() = runBlocking {
        for (wrongProvider in listOf(false, true)) {
            val h = Harness(); h.anchor()
            if (wrongProvider) h.modelProvider = "obi-pl" else h.modelId = "99999"
            h.turn("Czy dostaniemy A9Z01440/SCH w Nowym Sączu?")
            assertTrue(h.gets.isEmpty())
        }
    }
    @Test fun assistantSuggestionAloneCannotAuthorizeReferentOrLocation() = runBlocking {
        val h = Harness()
        h.history += AdvisorLocationHistoryMessage("ASSISTANT", "Sprawdź A9Z01440/SCH w Tarnowie")
        h.turn("a tam?"); assertTrue(h.gets.isEmpty()); assertTrue(h.lookups.isEmpty())
    }
    @Test fun ordinaryShortLocationsStillReadFreshSameProduct() = runBlocking {
        for (input in listOf("A w Bochni?", "U nas?")) {
            val h = Harness(); h.anchor(); h.turn(input); trace(h, input)
            assertEquals(listOf(if (input.startsWith("A")) "203" else "205"), h.evidence().checkedIds)
            assertEquals(1, h.gets.size)
        }
    }
    @Test fun pluralKrakowNeverNarrowsToProfileOrModelHint() = runBlocking {
        val h = Harness("obi-pl"); h.turn("sprawdź stan produktu 6346951", "discovery")
        h.turn("Czy dostaniemy produkt 6346951 w krakowskich marketach?"); trace(h, "plural Kraków")
        assertEquals(listOf("003", "019", "059", "072"), h.evidence().checkedIds.sorted())
        assertEquals(listOf("003", "019", "059", "072"), h.gets.flatMap { it.toHttpUrl().queryParameter("storeIds")!!.split(",") }.sorted())
    }
    private fun authority(h: Harness, text: String): AdvisorInventoryAuthorization = AdvisorInventoryAuthorizer.resolve(
        AdvisorLocationArguments(h.modelProvider, h.modelId, h.modelBranches), h.provider, h.profile, text,
        emptyList(), h.history.filter { it.role == "ASSISTANT" }.flatMap { it.products }, h.directory, h.history,
    )
    @Test fun typedAuthorityCarriesCurrentUserEvidenceAndCanonicalScope() = runBlocking {
        val h = Harness(); h.anchor()
        val text = "Czy dostaniemy A9Z01440/SCH w Nowym Sączu?"
        val a = authority(h, text) as AdvisorInventoryAuthorization.Inventory
        assertEquals(ProviderId("kwant-pl"), a.provider)
        assertEquals("77556", a.product.effectiveProductId)
        assertEquals(setOf("A9Z01440/SCH"), a.currentUserProductEvidence)
        assertEquals(AdvisorInventoryAuthorization.ProductProvenance.CURRENT_USER, a.productProvenance)
        assertEquals(AdvisorInventoryAuthorization.LocationProvenance.CURRENT_USER, a.locationProvenance)
        assertEquals(listOf(BranchId("205")), a.branches)
        assertEquals(text, a.originalUserText); assertFalse(a.fullNetwork)
        assertNull(a.currentProduct); assertTrue(h.gets.isEmpty()) // resolving authority is pure
    }
    @Test fun typedHistoricalAuthorityAfterPriceExecutesOnlyFreshProductBoundRead() = runBlocking {
        val h = Harness(); h.anchor(); h.turn("pokaż ceny")
        val text = "nie sprawdzisz mi w Mielcu?"
        val a = authority(h, text) as AdvisorInventoryAuthorization.Inventory
        assertEquals(AdvisorInventoryAuthorization.ProductProvenance.BOUNDED_USER_HISTORY, a.productProvenance)
        assertEquals(text, a.originalUserText); assertEquals(listOf(BranchId("206")), a.branches)
        val fresh = h.locations.execute(a)
        assertEquals(listOf("206"), fresh.checkedIds); assertEquals(11, fresh.locations.single().stock)
        assertEquals(1, h.gets.size); assertTrue(h.gets.single().contains("/77556/departments?"))
        assertEquals(2000L, fresh.verifiedAtMillis); assertNull(fresh.centralStock)
    }
    @Test fun referenceOutsideFourUserTurnWindowRequiresClarification() = runBlocking {
        val h = Harness(); h.anchor(); repeat(5) { h.turn("pokaż ceny") }
        h.turn("nie sprawdzisz mi w Mielcu?")
        assertTrue(h.gets.isEmpty()); assertEquals("location_intent_required", h.evidence().reason)
    }
    @Test fun failedExplicitNewProductCancelsOldReferentUntilVerified() = runBlocking {
        val h = Harness(); h.anchor(); h.id = "77557"; h.article = "A9Z01441/SCH"
        h.verificationFailure = true; h.turn(h.article)
        h.turn("nie sprawdzisz mi w Tarnowie?"); assertTrue(h.gets.isEmpty())
        assertEquals("untrusted_product", h.evidence().reason)
        h.verificationFailure = false; h.turn(h.article); h.modelId = h.id; h.turn("a w Tarnowie?")
        assertEquals(1, h.gets.size); assertTrue(h.gets.single().contains("/77557/departments?"))
        assertEquals(listOf("204"), h.evidence().checkedIds)
    }
    @Test fun broadWordingCannotEraseUnknownStreetOrConflictingMarket() = runBlocking {
        for (place in listOf("w innych marketach na Nieznanej", "w Krakowie na Nieznanej we wszystkich marketach", "w Krakowie market OBI 075 we wszystkich marketach", "we wszystkich marketach w Atlantydzie")) {
            val h = Harness("obi-pl"); h.turn("sprawdź stan produktu 6346951", "discovery")
            h.turn("Czy dostaniemy produkt 6346951 $place?"); trace(h, place)
            assertTrue(place, h.gets.isEmpty())
        }
    }
    @Test fun modelNetworkHintsCannotExpandSavedBranchRead() = runBlocking {
        val h = Harness(); h.anchor(); h.modelBranches = listOf("203", "204", "205", "206")
        h.turn("Macie może A9Z01440/SCH u nas?")
        assertEquals(listOf("205"), h.evidence().checkedIds)
        assertEquals(listOf("205"), h.evidence().returnedIds)
        assertEquals(1, h.gets.size); assertTrue(h.gets.single().contains("/77556/departments?"))
    }

    @Test fun sameProductAdviceWithExplicitUserIdentityDoesNotEraseReferent() = runBlocking {
        val h = Harness(); h.anchor()
        h.turn("Jak podłączyć A9Z01440/SCH?"); assertTrue(h.gets.isEmpty())
        h.turn("nie sprawdzisz mi w Mielcu?")
        assertEquals(1, h.gets.size); assertEquals(listOf("206"), h.evidence().checkedIds)
        assertTrue(h.gets.single().contains("/77556/departments?"))
    }
    @Test fun currentProductReferenceTailCannotHideUnknownPlaceSelection() = runBlocking {
        for (place in listOf("w mieście Ten", "w mieście Tego", "w Atlantydzie ten produkt")) {
            val h = Harness(); h.anchor(); h.turn("Czy dostaniemy A9Z01440/SCH $place?")
            assertTrue(place, h.gets.isEmpty())
        }
    }
    @Test fun fabricatedModelIdentityIsTypedDenial() = runBlocking {
        val h = Harness(); h.anchor(); h.modelId = "99999"
        val a = authority(h, "Czy dostaniemy A9Z01440/SCH w Nowym Sączu?")
        assertTrue(a is AdvisorInventoryAuthorization.Denied)
        assertEquals("untrusted_product", (a as AdvisorInventoryAuthorization.Denied).reason)
        assertTrue(h.gets.isEmpty())
    }

    @Test fun explicitKwantNetworkScopePreservesAllPublicCoverageAndOneExactGet() = runBlocking {
        val h = Harness(); h.anchor(); h.turn("Sprawdź stan A9Z01440/SCH we wszystkich oddziałach")
        assertEquals(listOf("203", "204", "205", "206"), h.evidence().checkedIds.sorted())
        assertEquals("all_public_locations", h.evidence().coverage)
        assertEquals(1, h.gets.size); assertTrue(h.gets.single().contains("/77556/departments?"))
    }

}
