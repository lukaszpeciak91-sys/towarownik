package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.*
import pl.lukaszpeciak.towarownik.product.provider.*
import java.math.BigDecimal

private const val A = "potrzebuje wkładki do drzwi 30/20G czy jest w OBI jeśli nie to w podobnych parametrach"
private const val B = "sprawdź stan A9Z01440/SCH w Bochni"
private val obi = OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }
private val kwant = listOf(
    ProviderBranch(BranchId("205"), "Nowy Sącz"),
    ProviderBranch(BranchId("203"), "Bochnia"),
    ProviderBranch(BranchId("128"), "Zamość"),
    ProviderBranch(BranchId("204"), "Tarnów"),
)

/** Synthetic provider facts; never live inventory or an inferred product identity. */
private class FakeProvider(
    override val providerId: ProviderId,
    private val directory: List<ProviderBranch>,
) : ProductProvider {
    val lookups = mutableListOf<String>()
    override fun branches() = ProviderBranchResult.Available(directory)
    override fun search(query: String, maxResults: Int) = ProviderSearchResult.Candidates(
        listOf(ProviderProductCandidate(ProductRef(providerId, "90001"), "Synthetic", "A9Z01440/SCH")),
    )
    override fun lookup(ref: ProductRef, branchId: BranchId): ProviderLookupResult {
        lookups += branchId.value
        return ProviderLookupResult.Found(
            ProviderProduct(
                ref, branchId, "Synthetic", 11, grossPrice = BigDecimal.ONE,
                priceScope = ProviderPriceScope.BRANCH,
                productUrl = "https://offline.invalid/product", ean = null,
                articleNumber = "A9Z01440/SCH",
            ),
        )
    }
}

private data class SearchObservation(
    val outcome: String,
    val called: String,
    val continuation: String,
    val cards: Int,
    val cardBranches: List<String>,
    val inventoryReads: List<String>,
)

private suspend fun search(
    input: String,
    provider: String,
    selected: String,
    hintBranch: String,
    hintText: String? = null,
    hintProvider: String = provider,
    missingDirectory: Boolean = false,
    suppliedDirectory: List<ProviderBranch>? = null,
): SearchObservation {
    val arguments = AdvisorToolArguments(
        hintBranch,
        listOf(AdvisorToolQuery(if (provider == "kwant-pl") "A9Z01440/SCH" else "wkładka 30/20G", 1)),
        hintProvider, hintText,
    )
    val directory = suppliedDirectory ?: if (provider == "obi-pl") obi else kwant
    val fake = FakeProvider(ProviderId(provider), directory)
    val providerTool = FindProviderProductsTool(ProductProviderRegistry(listOf(fake)))
    val executed = mutableListOf<String>()
    var continuation = "none"
    val tool = AdvisorProxyCallResult.Success(
        AdvisorProxyResult.ToolRequest("resp_initial", "call_initial", arguments),
    )
    val controller = AdvisorController(
        isConfigured = { true },
        startAgent = { _, p, b ->
            check(p == provider && b == selected)
            tool
        },
        messageAgent = { _, _, _, _ -> tool },
        continueAgent = { _, _, _, conversationBranch, _, output ->
            // One-off discovery never mutates conversation continuation identity.
            check(conversationBranch == selected)
            continuation = when (output) {
                is AdvisorToolContinuation.RejectedStore ->
                    if (provider == "obi-pl") "store_not_authorized" else "branch_not_authorized"
                is AdvisorToolContinuation.Verified -> "verified"
                else -> output.javaClass.simpleName
            }
            val refs = if (output is AdvisorToolContinuation.Verified) {
                listOf(AdvisorProductRef(output.result.storeNumber,
                    if (provider == "obi-pl") "7000001" else "90001", provider))
            } else emptyList()
            AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_final", "Synthetic final", refs))
        },
        executeObiTool = { args ->
            executed += args.storeNumber
            AdvisorToolExecutionResult.Success(
                AdvisorVerifiedToolResult(
                    args.storeNumber,
                    listOf(AdvisorVerifiedQueryResult(args.query, AdvisorQueryResultStatus.VERIFIED,
                        listOf(AdvisorVerifiedProduct("7000001", "Synthetic", stock = 11, price = BigDecimal.ONE)))),
                ),
                listOf(VerifiedProductSnapshot(
                    obik = "7000001", productId = "7000001", providerId = "obi-pl",
                    branchId = args.storeNumber, storeNumber = args.storeNumber,
                    name = "Synthetic", stock = 11, grossPrice = BigDecimal.ONE,
                    productUrl = "https://offline.invalid/product", verifiedAt = 1000,
                )),
            )
        },
        executeProviderTool = { args ->
            executed += args.storeNumber
            providerTool.execute(args)
        },
        branchDirectory = {
            if (missingDirectory) ProviderBranchResult.Unavailable(
                ProductProviderFailure.NETWORK, "synthetic offline unavailable",
            ) else ProviderBranchResult.Available(directory)
        },
    )
    val state = controller.runTurn(input, null, selected, provider) { }
    val outcome = when {
        state is AdvisorUiState.Error -> "error_${state.error}"
        continuation.endsWith("not_authorized") -> "rejected"
        else -> "authorized"
    }
    return SearchObservation(
        outcome, executed.joinToString(","), continuation,
        if (state is AdvisorUiState.Success) state.products.size else 0,
        if (state is AdvisorUiState.Success) state.products.map { it.branchId } else emptyList(),
        fake.lookups.toList(),
    )
}

/** Reuses the INC-006 native diagnostic fixture at the actual Controller boundary. */
class AdvisorDiscoveryRoutingRegressionTest {
    @Test fun `A ordinary OBI context uses profile despite model hints`() = runBlocking {
        for ((id, hint) in listOf("075" to null, "003" to null, "003" to "Kraków")) {
            val r = search(A, "obi-pl", "075", id, hint)
            assertEquals("hint=$id/$hint", "075", r.called)
            assertEquals(1, r.cards)
        }
    }
    @Test fun `B explicit inflected city wins every model hint`() = runBlocking {
        for ((id, hint) in listOf("205" to null, "205" to "Bochni", "205" to "Bochnia", "203" to "Bochni", "203" to "Bochnia", "128" to "Zamość")) {
            val r = search(B, "kwant-pl", "205", id, hint)
            assertEquals("hint=$id/$hint", "203", r.called)
            assertEquals("verified", r.continuation)
            assertEquals(listOf("203"), r.inventoryReads)
            assertEquals(listOf("203"), r.cardBranches)
            assertEquals(1, r.cards)
        }
    }
    @Test fun `C product quantities dimensions and codes cannot grant a branch`() = runBlocking {
        for (text in listOf("potrzebuję kabla 203 m", "potrzebuję 203 szt", "model 203", "kod 203", "sprawdź stan 203", "A9Z203/SCH", "203/205", "203 x 205 mm", "203 mm", "203 W", "203 zł", "203")) {
            assertEquals(text, "205", search(text, "kwant-pl", "205", "203").called)
        }
    }
    @Test fun `D absent user location ignores model authority for both providers`() = runBlocking {
        assertEquals("075", search("sprawdź wkładki 30/20G", "obi-pl", "075", "003", "Wielicka").called)
        assertEquals("205", search("sprawdź stan A9Z01440/SCH", "kwant-pl", "205", "128", "Zamość").called)
    }
    @Test fun `city and street resolve canonical directory entries`() = runBlocking {
        for (text in listOf("sprawdź w Bochni", "sprawdź w Bochnia", "sprawdź KWANT Bochnia", "sprawdź w hurtowni Bochnia")) {
            assertEquals(text, "203", search(text, "kwant-pl", "205", "205").called)
        }
        for (text in listOf("sprawdź w Krakowie na Wielickiej", "sprawdź OBI Kraków Wielicka", "sprawdź OBI na Wielickiej")) {
            assertEquals(text, "003", search(text, "obi-pl", "075", "075").called)
        }
    }
    @Test fun `provider mention alone is context not a location`() = runBlocking {
        for (text in listOf("czy jest w OBI", "czy OBI ma wkładki", "sprawdź w OBI jeśli nie to podobne", "sprawdź KWANT", "potrzebuję kabla w kolorze czarnym")) {
            assertEquals(text, "205", search(text, "kwant-pl", "205", "128").called)
        }
    }
    @Test fun `valid explicit market numbers still authorize only their directory entries`() = runBlocking {
        for (text in listOf("003", "market 003", "sprawdź w OBI 003", "sprawdź w markecie 003")) {
            assertEquals(text, "003", search(text, "obi-pl", "075", "075").called)
        }
        assertEquals("203", search("sprawdź w oddziale 203", "kwant-pl", "205", "205").called)
    }
    @Test fun `OBI city-wide discovery seed is derived from USER city not model hint`() = runBlocking {
        for (hint in listOf("075", "003", "019")) {
            val r = search("sprawdź w Krakowie", "obi-pl", "075", hint)
            assertEquals("003", r.called)
            assertEquals("verified", r.continuation)
            assertEquals(1, r.cards)
        }
    }
    @Test fun `unknown user locations fail closed even beside known locations`() = runBlocking {
        for (text in listOf("sprawdź w OBI Zakopane", "sprawdź w Zakopanem", "sprawdź w markecie 999", "sprawdź w OBI Kraków na Nieistniejącej", "sprawdź w Bochni i Zakopanem", "sprawdź w Bochni i w Zakopanem", "sprawdź w Bochni i u nas")) {
            val p = if ("Bochni" in text) "kwant-pl" else "obi-pl"
            val r = search(text, p, if(p=="kwant-pl") "205" else "075", if(p=="kwant-pl") "203" else "003")
            assertEquals(text, "", r.called)
            assertEquals(text, "rejected", r.outcome)
        }
    }
    @Test fun `conflicting city street and number never select strongest hint`() = runBlocking {
        for (text in listOf("sprawdź w OBI 075 Kraków", "sprawdź w OBI Kraków Tarnowska", "sprawdź w oddziale 205 Bochnia")) {
            val p = if ("OBI" in text) "obi-pl" else "kwant-pl"
            assertEquals(text, "", search(text,p,if(p=="obi-pl")"075" else "205",if(p=="obi-pl")"003" else "203").called)
        }
    }
    @Test fun `wrong provider cannot execute even with a valid user city`() = runBlocking {
        assertEquals("", search(B,"kwant-pl","205","203",hintProvider="obi-pl").called)
    }
    @Test fun `unavailable or duplicate branch directory cannot execute`() = runBlocking {
        assertEquals("error_PRODUCT_PROVIDER",search(B,"kwant-pl","205","203",missingDirectory=true).outcome)
        for (directory in listOf(kwant + kwant.first(), kwant + kwant[1], emptyList())) {
            assertEquals("", search(B,"kwant-pl","205","203",suppliedDirectory=directory).called)
        }
    }
    @Test fun `normal OBI and KWANT search returns verified cards`() = runBlocking {
        for ((p,id) in listOf("obi-pl" to "075", "kwant-pl" to "205")) {
            val r=search("sprawdź produkt",p,id,id)
            assertEquals(id,r.called)
            assertEquals(1,r.cards)
        }
    }
    @Test fun `explicit multiple cities require clarification`() = runBlocking {
        assertEquals("", search("sprawdź w Bochni i Zamościu","kwant-pl","205","203").called)
    }
    @Test fun `KWANT street identifies one of two same-city directory branches`() = runBlocking {
        val directory = kwant.map { if(it.branchId.value=="203") it.copy(address="Krakowska 10") else it } +
            ProviderBranch(BranchId("999"),"Bochnia","Brzeska 20")
        assertEquals("203",search("sprawdź stan A9Z01440/SCH w Bochni na Krakowskiej","kwant-pl","205","999",suppliedDirectory=directory).called)
        assertEquals("",search(B,"kwant-pl","205","203",suppliedDirectory=directory).called)
        assertEquals("",search("sprawdź w Bochni na Nieistniejącej","kwant-pl","205","203",suppliedDirectory=directory).called)
    }
    @Test fun `multiword streets cannot be combined from different branch addresses`() = runBlocking {
        val directory = kwant.map { if(it.branchId.value=="203") it.copy(address="Jana Pawła II 10") else it } +
            ProviderBranch(BranchId("999"),"Bochnia","Jana III Sobieskiego 20")
        assertEquals("203",search("sprawdź w Bochni na Jana Pawła II","kwant-pl","205","999",suppliedDirectory=directory).called)
        assertEquals("999",search("sprawdź w Bochni na Jana III Sobieskiego","kwant-pl","205","203",suppliedDirectory=directory).called)
        assertEquals("",search("sprawdź w Bochni na Jana Pawła Sobieskiego","kwant-pl","205","203",suppliedDirectory=directory).called)
    }
    @Test fun `numeric quantities at a location cannot supersede city authority`() = runBlocking {
        for(text in listOf("sprawdź w Bochni 205 m", "sprawdź w Bochni 205 szt", "sprawdź 205 W w Bochni")) {
            assertEquals(text,"203",search(text,"kwant-pl","205","205").called)
        }
    }
    @Test fun `unresolved deictic branch selection never falls back to profile`() = runBlocking {
        for(text in listOf("sprawdź tam klej", "sprawdź w innym markecie")) {
            assertEquals(text,"",search(text,"obi-pl","075","003").called)
        }
    }
    @Test fun `explicit numeric comparison retains bounded per-call authorization`() = runBlocking {
        assertEquals("203",search("porównaj oddziały 203 i 128","kwant-pl","205","203").called)
        assertEquals("",search("porównaj oddziały 203 i 128","kwant-pl","205","205").called)
    }

    @Test fun realDeviceNumberAndStreetMustAuthorizeDiscoveryWithoutModelHints() = runBlocking {
        for (input in listOf(
            "Sprawdź stan produktu OBIK 3496072 w markecie OBI nr 003 (Kraków, ul. Wielicka).",
            "Sprawdź stan produktu OBIK 3496072 w markecie OBI nr 003",
            "A ile tego samego produktu jest w OBI Kraków przy ulicy Wielickiej?",
        )) {
            val result = search(input, "obi-pl", "075", "075")
            assertEquals(input, "003", result.called)
            assertEquals(input, "verified", result.continuation)
        }
    }


    @Test fun unlabeledMeasurementsAndProductIdsMustNotGrantObi003() = runBlocking {
        for (input in listOf(
            "Sprawdź stan produktu OBIK 3496072, 003 sztuki.",
            "Sprawdź produkt z EAN 5905440091067.",
            "Sprawdź listwę 003 cm.",
        )) assertEquals(input, "075", search(input, "obi-pl", "075", "003").called)
        for (input in listOf(
            "Sprawdź stan produktu OBIK 3496072 w OBI 075 na Wielickiej.",
            "Sprawdź stan produktu OBIK 3496072 w markecie OBI nr 999",
        )) assertEquals(input, "", search(input, "obi-pl", "075", "003").called)
    }

}
