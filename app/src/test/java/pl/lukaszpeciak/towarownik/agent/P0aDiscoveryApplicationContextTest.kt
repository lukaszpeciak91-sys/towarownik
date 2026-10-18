package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import pl.lukaszpeciak.towarownik.product.*
import pl.lukaszpeciak.towarownik.product.provider.*

/** Scripted model plan, real discovery authorization/tools, synthetic exact-provider facts. */
@RunWith(Parameterized::class)
class P0aDiscoveryApplicationContextTest(private val case: Case) {
    data class Case(
        val name: String,
        val provider: String,
        val text: String,
        val resolution: String,
        val branch: String?,
        val modelProvider: String = provider,
        val stock: Int? = 7,
        val branches: List<Triple<String, String, String?>>? = null,
    ) {
        override fun toString() = name
    }

    private val profile = if (case.provider == "obi-pl") "075" else "205"
    private val productId = if (case.provider == "obi-pl") "6346951" else "77556"
    private val directory = case.branches?.map { ProviderBranch(BranchId(it.first), it.second, it.third) }
        ?: if (case.provider == "obi-pl") {
        OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }
    } else listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz", "Tarnowska 149"),
        ProviderBranch(BranchId("203"), "Bochnia", "Krakowska 10"),
        ProviderBranch(BranchId("204"), "Tarnów", "Lwowska 20"),
        ProviderBranch(BranchId("206"), "Mielec", "Przemysłowa 30"),
    )

    @Test fun canonicalUserScope() {
        val result = BranchResolver.resolveDiscovery(
            case.text, directory, BranchId(profile), obiMarketScope = case.provider == "obi-pl",
        )
        val actual = when (result) {
            BranchResolution.NotMentioned -> "absent"
            BranchResolution.UnknownMention -> "unknown"
            is BranchResolution.CurrentBranch -> "current:${result.branch.branchId.value}"
            is BranchResolution.Resolved -> "resolved:${result.branch.branchId.value}"
            is BranchResolution.Ambiguous -> if (result.isCityWideScope) "city-wide" else "ambiguous"
        }
        println("P0A_RESOLVER ${case.name}: $actual")
        assertEquals(case.text, case.resolution, actual)
        if (case.resolution == "city-wide") assertEquals(
            setOf("003", "019", "059", "072"),
            (result as BranchResolution.Ambiguous).candidates.map { it.branchId.value }.toSet(),
        )
    }

    @Test fun controllerCallsOnlyAuthorizedProviderAndCards() = runBlocking {
        val searches = mutableListOf<String>()
        val lookups = mutableListOf<Pair<String, String>>()
        var inventoryCalls = 0
        var continued = 0
        var continuation: AdvisorToolContinuation? = null
        val provider = object : ProductProvider {
            override val providerId = ProviderId(case.provider)
            override fun branches() = ProviderBranchResult.Available(directory)
            override fun search(query: String, maxResults: Int): ProviderSearchResult {
                searches += query
                return ProviderSearchResult.Candidates(listOf(
                    ProviderProductCandidate(ProductRef(providerId, productId), "Synthetic", "A9Z01440/SCH"),
                ))
            }
            override fun lookup(ref: ProductRef, branchId: BranchId): ProviderLookupResult {
                lookups += ref.productId to branchId.value
                return ProviderLookupResult.Found(ProviderProduct(
                    ref, branchId, "Synthetic", case.stock, centralStock = 53,
                    grossPrice = BigDecimal.ONE, priceScope = ProviderPriceScope.ONLINE,
                    productUrl = "https://offline.invalid/product", ean = null, articleNumber = "A9Z01440/SCH",
                ))
            }
        }
        val providerTool = FindProviderProductsTool(ProductProviderRegistry(listOf(provider)), now = { 2000L })
        val obiTool = FindObiProductsTool(
            searchProducts = { query ->
                searches += query
                ProductSearchResult.Candidates(listOf(ProductSearchCandidate(productId, "Synthetic")))
            },
            lookupObik = { id, branch ->
                lookups += id to branch
                ProductLookupResult.Found(LocalProduct(
                    id, "Synthetic", case.stock, BigDecimal.ONE, "https://offline.invalid/product", null, branch,
                ))
            }, now = { 2000L },
        )
        val query = if (case.provider == "obi-pl") {
            case.text.takeIf { it.matches(Regex("""[0-9]{8,14}""")) } ?: productId
        } else "A9Z01440/SCH"
        val request = AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
            "response-original", "call-original",
            AdvisorToolArguments("999", listOf(AdvisorToolQuery(query, 1)), case.modelProvider, "Atlantyda"),
        ))
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { _, p, b -> assertEquals(case.provider, p); assertEquals(profile, b); request },
            messageAgent = { _, _, _, _ -> error("Fresh turn") },
            continueAgent = { response, call, p, b, _, output ->
                continued++
                continuation = output
                assertEquals("response-original", response); assertEquals("call-original", call)
                assertEquals(case.provider, p); assertEquals(profile, b)
                println("P0A_AUTH ${case.name}: continuation=${output.javaClass.simpleName} lookups=$lookups searches=${searches.size} inventory=$inventoryCalls")
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                    "response-final", "Synthetic answer",
                    // Include an unverified model ref: it must not create a card.
                    listOf(AdvisorProductRef(case.branch ?: profile, productId, case.provider),
                        AdvisorProductRef("999", "9999999", case.provider)),
                ))
            },
            executeObiTool = obiTool::execute,
            executeProviderTool = providerTool::execute,
            branchDirectory = { ProviderBranchResult.Available(directory) },
            executeLocationsTool = { _ -> inventoryCalls++; error("Discovery cannot start inventory") },
        )
        val stale = VerifiedProductSnapshot(
            "1111111", "Old card", 99, grossPrice = BigDecimal.TEN,
            productUrl = "https://offline.invalid/old", verifiedAt = 1L,
            storeNumber = "999", providerId = case.provider, productId = "1111111", branchId = "999",
        )
        val state = controller.runTurn(case.text, null, profile, case.provider,
            historicalVerifiedProducts = listOf(stale)) {} as AdvisorUiState.Success
        println("P0A_CONTROLLER ${case.name}: lookups=$lookups searches=${searches.size} cards=${state.products.map { it.key }} inventory=$inventoryCalls")
        assertEquals(1, continued)
        assertEquals(0, inventoryCalls)
        if (case.branch == null) {
            assertTrue(continuation is AdvisorToolContinuation.RejectedStore)
            assertTrue(lookups.isEmpty()); assertTrue(searches.isEmpty()); assertTrue(state.products.isEmpty())
        } else {
            assertTrue(continuation is AdvisorToolContinuation.Verified)
            assertEquals(case.branch, (continuation as AdvisorToolContinuation.Verified).result.storeNumber)
            assertEquals(listOf(productId to case.branch), lookups)
            assertEquals(if (case.provider == "obi-pl" && query == productId) 0 else 1, searches.size)
            val card = state.products.single()
            assertEquals(case.provider, card.providerId); assertEquals(case.branch, card.effectiveBranchId)
            assertEquals(productId, card.effectiveProductId); assertEquals(case.stock, card.stock)
            assertEquals(2000L, card.verifiedAt); assertEquals(BigDecimal.ONE, card.grossPrice)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Case>> {
            val rows = mutableListOf<Case>()
            val applications = listOf(
                "Szukam różnicówki Schneider 40 A na szynę DIN",
                "Szukam uszczelniacza do łazienki w mieszkaniu",
                "Szukam uszczelniacza do łazienki w domu",
                "Szukam uszczelniacza w łazience",
                "Szukam kleju na ścianę",
                "Szukam zabezpieczenia do rozdzielnicy",
                "Szukam lampy do montażu na suficie",
                "Szukam złączki na szynę TH35 w rozdzielnicy",
                "Szukam uszczelniacza we własnym mieszkaniu",
                "Szukam uszczelniacza w nowej łazience",
                "Szukam kleju na betonową ścianę",
                "Szukam różnicówki na metalową szynę TH35",
            )
            for (p in listOf("obi-pl", "kwant-pl")) {
                val saved = if (p == "obi-pl") "075" else "205"
                applications.forEachIndexed { i, text -> rows += Case("$p application $i", p, text, "absent", saved) }
                rows += Case("$p zero", p, applications[0], "absent", saved, stock = 0)
                rows += Case("$p unknown stock", p, applications[1], "absent", saved, stock = null)
                rows += Case("$p wrong provider", p, applications[2], "absent", null,
                    modelProvider = if (p == "obi-pl") "kwant-pl" else "obi-pl")
                for (text in listOf("Szukam kabla w Zakopanem", "sprawdź w zakopanem",
                    "Szukam kabla w mieście Atlantyda", "sprawdź w oddziale Nieznanym",
                    "Szukam złączki na szynę DIN w Zakopanem", "Szukam lampy do montażu na suficie w oddziale Nieznanym",
                    "sprawdź w oddziale w mieszkaniu", "sprawdź w mieście na suficie",
                    "Szukam lampy na suficie i w zakopanem", "Szukam lampy na suficie przy ulicy Nieznanej",
                    "Szukam kabla w Mieszkaniu", "Szukam kabla na Suficie",
                    "Szukam lampy na suficie tam", "sprawdź w oddziałach w mieszkaniu",
                    "Szukam kabla w nowym Mieszkaniu")) {
                    rows += Case("$p unknown $text", p, text, "unknown", null)
                }
            }
            rows += listOf(
                Case("KWANT city", "kwant-pl", "Szukam kabla w Bochni", "resolved:203", "203"),
                Case("KWANT city and street", "kwant-pl", "Szukam kabla w Bochni na Krakowskiej", "resolved:203", "203"),
                Case("KWANT application then city", "kwant-pl", "Szukam różnicówki na szynę DIN w Bochni", "resolved:203", "203"),
                Case("KWANT city then application", "kwant-pl", "Szukam różnicówki w Bochni na szynę DIN", "resolved:203", "203"),
                Case("KWANT city then purpose", "kwant-pl", "Szukam lampy w Bochni do montażu na suficie", "resolved:203", "203"),
                Case("KWANT city then enclosure", "kwant-pl", "Szukam różnicówki w Bochni do rozdzielnicy", "resolved:203", "203"),
                Case("KWANT application then enclosure then city", "kwant-pl", "Szukam złączki na szynę TH35 w rozdzielnicy w Bochni", "resolved:203", "203"),
                Case("KWANT adjacent application frames", "kwant-pl", "Szukam lampy w mieszkaniu do montażu na suficie w Bochni", "resolved:203", "203"),
                Case("KWANT unknown street", "kwant-pl", "Szukam kabla w Bochni na Nieistniejącej", "unknown", null),
                Case("KWANT mixed places", "kwant-pl", "sprawdź w Bochni i Zakopanem", "unknown", null),
                Case("KWANT conflict", "kwant-pl", "sprawdź w oddziale 205 Bochnia", "unknown", null),
                Case("KWANT units and codes", "kwant-pl", "203 m kabla A9Z203/SCH 203 x 205 mm 40 A", "absent", "205"),
                Case("OBI whole-city identity seed", "obi-pl", "Szukam kabla w Krakowie", "city-wide", "003"),
                Case("OBI street", "obi-pl", "Szukam kabla w Krakowie na Wielickiej", "resolved:003", "003"),
                Case("OBI application then street", "obi-pl", "Szukam lampy do montażu na suficie w Krakowie na Wielickiej", "resolved:003", "003"),
                Case("OBI city conflict", "obi-pl", "Szukam kabla w OBI 075 Kraków", "unknown", null),
                Case("OBI unknown street", "obi-pl", "sprawdź w Krakowie na Nieistniejącej", "unknown", null),
                Case("OBI market", "obi-pl", "Szukam kabla w markecie 003", "resolved:003", "003"),
                Case("OBI bare market", "obi-pl", "003", "resolved:003", "003"),
                Case("OBI unknown market", "obi-pl", "Szukam kabla w markecie 999", "unknown", null),
                Case("OBI plural", "obi-pl", "6346951 czy mają w krakowskich marketach? sprawdź proszę", "city-wide", "003"),
                Case("OBI plural and application", "obi-pl", "Szukam lampy na sufit w krakowskich marketach", "city-wide", "003"),
                Case("OBI bare OBIK", "obi-pl", "6346951", "absent", "075"),
                Case("OBI bare EAN", "obi-pl", "4007871634695", "absent", "075"),
                Case("OBI units and codes", "obi-pl", "203 m kabla 6346951 203 x 205 mm 40 A", "absent", "075"),
                Case("OBI code is not a market", "obi-pl", "003/205", "absent", "075"),
                Case("OBI application cannot synthesize market", "obi-pl", "003 na szynę DIN", "absent", "075"),
                Case("canonical city takes priority", "kwant-pl", "Szukam lampy w mieszkaniu", "resolved:203", "203",
                    branches = listOf(Triple("205", "Nowy Sącz", null), Triple("203", "Mieszkaniu", null))),
                Case("canonical street takes priority", "kwant-pl", "Szukam lampy na sufit", "resolved:203", "203",
                    branches = listOf(Triple("205", "Nowy Sącz", null), Triple("203", "Bochnia", "Sufit 20"))),
            )
            return rows.map { arrayOf(it) }
        }
    }
}
