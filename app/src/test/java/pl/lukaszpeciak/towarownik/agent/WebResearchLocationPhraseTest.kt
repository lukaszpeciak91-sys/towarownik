package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import pl.lukaszpeciak.towarownik.diagnostics.DiagnosticStage
import pl.lukaszpeciak.towarownik.diagnostics.SafeDiagnosticEvent
import pl.lukaszpeciak.towarownik.product.*
import pl.lukaszpeciak.towarownik.product.provider.*

private val KARLIK_WEB_REQUEST = """
    klient potrzebuje poniższe:
    2 x włącznik pojedynczy
    3 x gniazdo pojedyncze
    1 x włącznik krzyżowy
    2 x gniazdo podwójne
    3 x ramka pojedyncza okrągła
    1 x ramka podwójna okrągła
    1 x ramka potrójna okrągła
    życzy sobie serie Karlik Mini kolor Taupe, znajdź dla mnie w sieci kody producenta na te artykuły
""".trimIndent()

/** Real resolver, Controller and discovery tools; scripted model and synthetic provider facts only. */
@RunWith(Parameterized::class)
class WebResearchLocationPhraseTest(private val case: Case) {
    data class Case(
        val name: String,
        val text: String,
        val provider: String = "kwant-pl",
        val resolution: String = "absent",
        val branch: String? = if (provider == "obi-pl") "075" else "205",
        val hint: String = "999",
        val requestedBranch: String? = "Atlantyda",
        val modelProvider: String = provider,
        val reason: String? = null,
        val stock: Int? = 9,
        val directory: List<Triple<String, String, String?>>? = null,
    ) { override fun toString() = name }

    private val profile = if (case.provider == "obi-pl") "075" else "205"
    private val productId = if (case.provider == "obi-pl") "7000001" else "90001"
    private val query = "synthetic product research"
    private val branches = case.directory?.map { ProviderBranch(BranchId(it.first), it.second, it.third) } ?: if (case.provider == "obi-pl") {
        OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }
    } else listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz", "Tarnowska 149"),
        ProviderBranch(BranchId("203"), "Bochnia", "Krakowska 10"),
        ProviderBranch(BranchId("204"), "Tarnów", "Lwowska 20"),
    )

    @Test fun resolverPreservesUserScope() {
        val result = BranchResolver.resolveDiscovery(case.text, branches, BranchId(profile), case.provider == "obi-pl")
        val actual = when (result) {
            BranchResolution.NotMentioned -> "absent"
            BranchResolution.UnknownMention -> "unknown"
            is BranchResolution.CurrentBranch -> "current:${result.branch.branchId.value}"
            is BranchResolution.Resolved -> "resolved:${result.branch.branchId.value}"
            is BranchResolution.Ambiguous -> "ambiguous"
        }
        println("R6_RESOLVER ${case.name}: $actual")
        assertEquals(case.text, case.resolution, actual)
    }

    @Test fun controllerExecutesOnlyUserAuthorizedDiscoveryWithoutChangingModelText() = runBlocking {
        val executions = mutableListOf<AdvisorToolArguments>()
        val searches = mutableListOf<String>()
        val lookups = mutableListOf<Pair<String, String>>()
        val events = mutableListOf<SafeDiagnosticEvent>()
        val modelMessages = mutableListOf<String>()
        var continuation: AdvisorToolContinuation? = null
        var continued = 0
        var inventoryExecutions = 0
        val provider = object : ProductProvider {
            override val providerId = ProviderId(case.provider)
            override fun branches() = ProviderBranchResult.Available(branches)
            override fun search(query: String, maxResults: Int): ProviderSearchResult {
                searches += query
                return ProviderSearchResult.Candidates(listOf(
                    ProviderProductCandidate(ProductRef(providerId, productId), "Synthetic", "SYNTHETIC_ONLY"),
                ))
            }
            override fun lookup(ref: ProductRef, branchId: BranchId): ProviderLookupResult {
                lookups += ref.productId to branchId.value
                return ProviderLookupResult.Found(ProviderProduct(
                    ref, branchId, "Synthetic product", case.stock, centralStock = null,
                    grossPrice = BigDecimal.ONE, priceScope = ProviderPriceScope.ONLINE,
                    productUrl = "https://offline.invalid/product", ean = null, articleNumber = "SYNTHETIC_ONLY",
                ))
            }
        }
        val providerTool = FindProviderProductsTool(ProductProviderRegistry(listOf(provider)), now = { 6000L })
        val obiTool = FindObiProductsTool(
            searchProducts = { q ->
                searches += q
                ProductSearchResult.Candidates(listOf(ProductSearchCandidate(productId, "Synthetic")))
            },
            lookupObik = { id, branch ->
                lookups += id to branch
                ProductLookupResult.Found(LocalProduct(id, "Synthetic product", case.stock,
                    BigDecimal.ONE, "https://offline.invalid/product", null, branch))
            }, now = { 6000L },
        )
        val controller = AdvisorController(
            isConfigured = { true },
            startAgent = { message, p, b ->
                modelMessages += message
                assertEquals(case.provider, p); assertEquals(profile, b)
                AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest(
                    "resp_r6_tool", "call_r6", AdvisorToolArguments(
                        case.hint, listOf(AdvisorToolQuery(query, 1)), case.modelProvider, case.requestedBranch,
                    ),
                ))
            },
            messageAgent = { _, _, _, _ -> error("R6 uses a new conversation") },
            continueAgent = { response, call, p, b, contract, result ->
                continued++; continuation = result
                assertEquals("resp_r6_tool", response); assertEquals("call_r6", call)
                assertEquals(case.provider, p); assertEquals(profile, b)
                assertEquals(if (case.provider == "obi-pl") AdvisorTransportContract.OBI_V2
                    else AdvisorTransportContract.PROVIDER_V3, contract)
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                    "resp_r6_final", "Synthetic final",
                    listOf(AdvisorProductRef(case.branch ?: profile, productId, case.provider),
                        AdvisorProductRef("999", "9999999", case.provider)),
                ))
            },
            executeObiTool = { args ->
                assertEquals("obi-pl", case.provider); executions += args; obiTool.execute(args)
            },
            executeProviderTool = { args ->
                assertEquals("kwant-pl", case.provider); executions += args; providerTool.execute(args)
            },
            executeLocationsTool = { _ -> inventoryExecutions++; error("No network-inventory authority from internet wording") },
            branchDirectory = { ProviderBranchResult.Available(branches) },
        )
        val state = controller.runTurn(case.text, null, profile, case.provider,
            onDiagnosticEvent = { events += it }) {} as AdvisorUiState.Success
        val reasons = events.filter { it.stage == DiagnosticStage.TOOL_RESULT }.map { it.reason }
        println("R6_CONTROLLER ${case.name}: executions=${executions.map { it.storeNumber }} reasons=$reasons cards=${state.products.map { it.effectiveBranchId }}")
        assertEquals("Original multiline USER must reach model unchanged", listOf(case.text), modelMessages)
        assertEquals(1, continued)
        assertEquals(0, inventoryExecutions)
        assertEquals(listOf(case.reason), reasons)
        if (case.branch == null) {
            assertTrue(continuation is AdvisorToolContinuation.RejectedStore)
            assertTrue(executions.isEmpty()); assertTrue(searches.isEmpty()); assertTrue(lookups.isEmpty())
            assertTrue(state.products.isEmpty())
        } else {
            assertTrue("Correct scripted discovery must be admitted", continuation is AdvisorToolContinuation.Verified)
            val args = executions.single()
            assertEquals(case.branch, args.storeNumber)
            assertEquals(case.provider, args.providerId)
            assertNull(args.requestedBranch)
            assertEquals(listOf(AdvisorToolQuery(query, 1)), args.queries)
            assertEquals(listOf(query), searches)
            assertEquals(listOf(productId to case.branch), lookups)
            val card = state.products.single()
            assertEquals(case.branch, card.effectiveBranchId)
            assertEquals(case.provider, card.providerId)
            assertEquals(productId, card.effectiveProductId)
            assertEquals(case.stock, card.stock)
            assertEquals(BigDecimal.ONE, card.grossPrice)
            assertEquals(6000L, card.verifiedAt)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Case>> {
            val rows = mutableListOf<Case>()
            for (p in listOf("kwant-pl", "obi-pl")) {
                val inputs = listOf(
                    KARLIK_WEB_REQUEST,
                    KARLIK_WEB_REQUEST.replace("w sieci", "przez internet"),
                    KARLIK_WEB_REQUEST.replace("w sieci", "w internecie"),
                    "Znajdź w sieci kody producenta Karlik MINI taupe",
                    "w sieci kody producenta",
                    "w sieci instrukcja",
                    "w sieci karta katalogowa",
                    "Poszukaj w sieci instrukcji Hagera",
                    "Sprawdź w sieci kartę katalogową Schneider Electric",
                    "Znajdź w sieci katalog",
                    "Znajdź w sieci kody serii Tarnów",
                    "Znajdź W SIECI kody producenta",
                    "Znajdź w sieci kody. Poszukaj w sieci instrukcji",
                )
                inputs.forEachIndexed { i, text -> rows += Case("$p online $i", text, p) }
                val known = if (p == "kwant-pl") "w KWANT Tarnów" else "w OBI Kraków na Wielickiej"
                val target = if (p == "kwant-pl") "204" else "003"
                rows += Case("$p online then known", "Znajdź w sieci kody Karlik, a stan sprawdź $known", p, "resolved:$target", target)
                // R7B admits the complete KWANT research action; OBI retains
                // its independent market-scope grammar and safety boundary.
                rows += if (p == "kwant-pl") Case("$p joined research action", "Sprawdź $known i znajdź w sieci kody producenta", p,
                    "resolved:$target", target) else Case("$p existing joined action guard", "Sprawdź $known i znajdź w sieci kody producenta", p,
                    "unknown", null, reason = "unknown_location")
                for (tail in listOf("w nieznanym mieście", "w Zakopanem", "w oddziale 999", "tam", "w innym markecie",
                    "$known i w Zakopanem", "w oddziale 205 Bochnia", "$known na Nieistniejącej")) {
                    rows += Case("$p denied $tail", "Znajdź w sieci kody Karlik, a stan sprawdź $tail", p,
                        "unknown", null, reason = "unknown_location")
                }
                val conflicting = if (p == "kwant-pl") "w oddziale 205 i w oddziale 204" else "w OBI 075 i w OBI 003"
                rows += Case("$p conflicting branches", "Znajdź w sieci kody Karlik, a stan sprawdź $conflicting", p,
                    "ambiguous", null, reason = "ambiguous_location")
                for (network in listOf("Sprawdź dostępność w sieci OBI", "Sprawdź dostępność w sieci sklepów OBI",
                    "Sprawdź dostępność w sieci hurtowni KWANT",
                    "Sprawdź we wszystkich oddziałach KWANT", "Czy produkt jest dostępny w całej sieci?")) {
                    rows += Case("$p retail $network", network, p, "unknown", null, reason = "unknown_location")
                }
                for (text in listOf("Znajdź w oddziale w sieci kody producenta", "Znajdź w sieci Zakopane",
                    "Znajdź w sieci sklepów instrukcję", "Znajdź w sieci kody producenta w mieście Atlantyda",
                    "Znajdź w sieci kody producenta adres Atlantyda", "Znajdź w sieci kodowanie",
                    "Znajdź w sieci instrukcyjnej")) {
                    rows += Case("$p bounded $text", text, p, "unknown", null, reason = "unknown_location")
                }
                rows += Case("$p wrong model provider", inputs[0], p, branch = null,
                    modelProvider = if (p == "obi-pl") "kwant-pl" else "obi-pl", reason = "wrong_provider")
                // Internet wording cannot unmask an overlapping foreign-provider
                // city/number as authority in the active provider's directory.
                val foreign = if (p == "kwant-pl") "OBI Nowy Sącz 205" else "KWANT Nowy Sącz 075"
                rows += Case("$p foreign provider scope", "Znajdź w sieci kody, a stan sprawdź w $foreign", p,
                    "unknown", null, reason = "unknown_location")
                for (stock in listOf(0, null)) rows += Case("$p stock $stock", inputs[0], p, stock = stock)
            }
            rows += Case("KWANT Bochnia after instruction", "Poszukaj w sieci instrukcji, a dostępność sprawdź w Bochni",
                resolution = "resolved:203", branch = "203")
            for (hint in listOf("205", "204")) rows += Case("KWANT hint $hint is not authority", KARLIK_WEB_REQUEST, hint = hint)
            rows += Case("canonical city is not erased", "Znajdź w sieci kody producenta", resolution = "unknown",
                branch = null, reason = "unknown_location", directory = listOf(
                    Triple("205", "Nowy Sącz", null), Triple("203", "Sieci", null)))
            rows += Case("canonical street is not erased", "Znajdź w sieci kody producenta", resolution = "resolved:203",
                branch = "203", directory = listOf(Triple("205", "Nowy Sącz", null),
                    Triple("203", "Bochnia", "Sieci Kody Producenta 10")))
            return rows.map { arrayOf(it) }
        }
    }
}
