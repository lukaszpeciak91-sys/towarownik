package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.OBI_STORES
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.*

/** Native Controller -> actual follow-up/identity/scope tool -> offline product-bound read. */
class AdvisorNaturalFollowUpRegressionTest {
    private val kwant = listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz"),
        ProviderBranch(BranchId("203"), "Bochnia", "Krakowska 10"),
        ProviderBranch(BranchId("204"), "Tarnów"),
        ProviderBranch(BranchId("141"), "Włocławek"),
        ProviderBranch(BranchId("128"), "Zamość"),
    )
    private val obi = OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }
    private fun card(provider: String, id: String = if (provider == "obi-pl") "7000001" else "90001") =
        VerifiedProductSnapshot(
            obik = id, productId = id, providerId = provider,
            branchId = if (provider == "obi-pl") "075" else "203",
            storeNumber = if (provider == "obi-pl") "075" else "203",
            name = "Synthetic", stock = 99, grossPrice = BigDecimal.ONE,
            productUrl = "https://offline.invalid/product", verifiedAt = 10,
            articleNumber = if (provider == "kwant-pl") "A9Z01440/SCH" else null,
        )
    private fun user(text: String) = AdvisorLocationHistoryMessage("USER", text)
    private fun history(provider: String, anchor: String? = null) = listOf(
        user(anchor ?: if (provider == "kwant-pl") "sprawdź stan A9Z01440/SCH w Bochni"
            else "sprawdź stan produktu 7000001 w OBI 075"),
        AdvisorLocationHistoryMessage("ASSISTANT", "Synthetic verified result", listOf(card(provider))),
    )
    private data class Observation(
        val evidence: AdvisorLocationEvidence,
        val reads: List<Pair<String, List<String>>>,
        val state: AdvisorUiState.Success,
    )
    private suspend fun run(
        input: String, provider: String = "kwant-pl",
        hints: List<String> = emptyList(),
        prior: List<AdvisorLocationHistoryMessage> = history(provider),
        cards: List<VerifiedProductSnapshot> = listOf(card(provider)),
        modelId: String = if (provider == "obi-pl") "7000001" else "90001",
        directoryUnavailable: Boolean = false,
        modelProvider: String = provider,
        suppliedDirectory: List<ProviderBranch>? = null,
        previousResponseId: String? = "resp_prior",
    ): Observation {
        val selected = if (provider == "obi-pl") "075" else "205"
        val directory = suppliedDirectory ?: if (provider == "obi-pl") obi else kwant
        val reads = mutableListOf<Pair<String, List<String>>>()
        val adapter = object : ProductLocationsAdapter {
            override val providerId = ProviderId(provider)
            override suspend fun read(ref: ProductRef, requested: List<BranchId>, trustedProduct: ProviderProduct?): ProductLocationsResult {
                assertEquals(modelId, ref.productId)
                reads += ref.productId to requested.map { it.value }
                return ProductLocationsResult.Available(
                    ref, requested.map { id -> LocationStock(directory.single { it.branchId == id }, 7) },
                    LocationCoverage(LocationCoverageKind.REQUESTED_SUBSET, requested, requested, 1), 2000,
                )
            }
        }
        val branches = { if (directoryUnavailable) ProviderBranchResult.Unavailable(ProductProviderFailure.NETWORK, "offline")
            else ProviderBranchResult.Available(directory) }
        val tool = AdvisorLocationsTool(ProductLocationsService(listOf(adapter)), branches, branches,
            providers = exactLookupProviders { ref, branch ->
                reads += ref.productId to listOf(branch.value)
                exactLookupFound(ref, branch, 7)
            }, now = { 2000L })
        var evidence: AdvisorLocationEvidence? = null
        val initial = AdvisorProxyCallResult.Success(AdvisorProxyResult.LocationToolRequest(
            "resp_locations", "call_locations", AdvisorLocationArguments(modelProvider, modelId, hints),
        ))
        val controller = AdvisorController(
            isConfigured = { true }, startAgent = { _, _, _ -> initial },
            messageAgent = { previous, _, _, _ -> assertEquals(previousResponseId,previous); initial },
            continueAgent = { response, call, p, b, _, output ->
                assertEquals("resp_locations",response);assertEquals("call_locations",call)
                assertEquals(provider, p); assertEquals(selected, b)
                evidence = (output as AdvisorToolContinuation.Locations).evidence
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer(
                    "resp_final", "Synthetic model: location not authorized",
                    listOf(AdvisorProductRef(card(provider).effectiveBranchId, modelId, provider)),
                ))
            },
            executeObiTool = { error("No discovery fixture") }, executeProviderTool = { error("No discovery fixture") },
            executeLocationsTool = tool::execute, branchDirectory = { branches() },
        )
        val state = controller.runTurn(input, previousResponseId, selected, provider,
            historicalVerifiedProducts = cards, locationHistory = prior) { } as AdvisorUiState.Success
        val observed = requireNotNull(evidence)
        println("R2_SYNTHETIC provider=$provider input=$input status=${observed.status} reason=${observed.reason} checked=${observed.checkedIds} reads=$reads cards=${state.products.size} text=${state.text}")
        return Observation(observed, reads, state)
    }
    private fun assertRead(r: Observation, id: String) {
        assertEquals("verified", r.evidence.status)
        assertEquals(listOf(id), r.evidence.checkedIds)
        assertEquals(listOf(id), r.reads.flatMap { it.second })
        assertEquals(7, r.evidence.locations.single().stock)
        assertEquals(2000L, r.evidence.verifiedAtMillis)
        assertTrue(r.state.products.isEmpty()) // Old cards never become new current-turn cards.
        assertFalse(r.state.text.contains("not authorized"))
        assertFalse(r.state.text.contains("99")) // Historical quantity never substitutes for a read.
    }
    @Test fun `plain KWANT w and we follow-ups use USER anchor and exact product`() = runBlocking {
        assertRead(run("w Bochni"), "203")
        assertRead(run("we Włocławku"), "141")
    }
    @Test fun `prefixed KWANT city follow-up remains valid`() = runBlocking {
        assertRead(run("a w Tarnowie?"), "204")
    }
    @Test fun `OBI plain and prefixed city plus street select unique market`() = runBlocking {
        for (text in listOf("w Krakowie na Wielickiej", "a w Krakowie na Wielickiej?")) {
            assertRead(run(text, "obi-pl"), "003")
        }
    }
    @Test fun `u nas uses conversation profile rather than card or model branch`() = runBlocking {
        assertRead(run("u nas?", hints = listOf("203")), "205")
        assertRead(run("u nas?", "obi-pl", listOf("003")), "075")
    }
    @Test fun `tam refers only to preceding unique USER location`() = runBlocking {
        assertRead(run("a tam?", hints = listOf("205")), "203")
        assertRead(run("a tam?", "obi-pl", listOf("075"), history("obi-pl", "sprawdź stan produktu 7000001 w OBI 003")), "003")
    }
    @Test fun `correct USER scope cannot be vetoed by model hints`() = runBlocking {
        assertRead(run("sprawdź stan A9Z01440/SCH w Bochni", hints = listOf("205")), "203")
        assertRead(run("sprawdź stan produktu 7000001 w OBI 003", "obi-pl", listOf("075")), "003")
    }
    @Test fun `neutral price reference retains same verified product without stock reuse`() = runBlocking {
        val r=run("nie sprawdzisz mi w Bochni?", prior=history("kwant-pl") + user("pokaż ceny"))
        assertRead(r, "203")
    }
    @Test fun `advice and installation cannot inherit stock permission`() = runBlocking {
        for(text in listOf("jak zamontować w Bochni", "a w Bochni pokaż ceny", "a w Bochni ile kosztuje?")) {
            val r=run(text)
            assertTrue(r.reads.isEmpty()); assertEquals("location_intent_required",r.evidence.reason)
        }
    }
    @Test fun `ASSISTANT suggestion alone cannot authorize inventory or tam`() = runBlocking {
        val r=run("a tam?", prior=listOf(AdvisorLocationHistoryMessage("ASSISTANT", "Sprawdź w Bochni", listOf(card("kwant-pl")))))
        assertTrue(r.reads.isEmpty()); assertEquals("rejected", r.evidence.status)
    }
    @Test fun `failed fresh turn cannot fabricate identity and reason asks for product`() = runBlocking {
        val r=run("w Bochni", cards=emptyList(), prior=listOf(user("sprawdź stan A9Z01440/SCH w Bochni"), AdvisorLocationHistoryMessage("ASSISTANT", "Failed turn")),previousResponseId=null)
        assertTrue(r.reads.isEmpty()); assertEquals("untrusted_product",r.evidence.reason)
        assertFalse(r.state.text.contains("not authorized")); assertTrue(r.state.text.contains("produkt"))
    }
    @Test fun `wrong model product stays rejected before inventory`() = runBlocking {
        val r=run("sprawdź stan A9Z01440/SCH w Bochni",modelId="90002")
        assertTrue(r.reads.isEmpty()); assertEquals("untrusted_product",r.evidence.reason)
        assertFalse(r.state.text.contains("not authorized"))
    }
    @Test fun `unverified current numeric product never falls back to historical product`() = runBlocking {
        val r=run("sprawdź stan produktu 9999 w Bochni")
        assertTrue(r.reads.isEmpty()); assertEquals("untrusted_product",r.evidence.reason)
    }
    @Test fun `unverified current article never falls back to historical product`() = runBlocking {
        assertTrue(run("sprawdź stan A9Z01441/SCH w Bochni").reads.isEmpty())
    }
    @Test fun `unknown city has truthful local reason without inventory`() = runBlocking {
        val r=run("w Zakopanem")
        assertTrue(r.reads.isEmpty()); assertEquals("unknown_location",r.evidence.reason)
        assertFalse(r.state.text.contains("not authorized"))
    }
    @Test fun `ambiguous OBI city asks one precise question instead of choosing`() = runBlocking {
        val r=run("w Krakowie", "obi-pl", listOf("003"))
        assertTrue(r.reads.isEmpty()); assertEquals("ambiguous_location",r.evidence.reason)
        assertTrue(r.state.text.contains("ulic")); assertFalse(r.state.text.contains("not authorized"))
    }
    @Test fun `tam cannot use ASSISTANT location suggestion after unscoped USER anchor`() = runBlocking {
        val r=run("a tam?", prior=listOf(user("sprawdź stan A9Z01440/SCH"),AdvisorLocationHistoryMessage("ASSISTANT","W Bochni",listOf(card("kwant-pl")))))
        assertTrue(r.reads.isEmpty()); assertEquals("ambiguous_location",r.evidence.reason)
    }
    @Test fun `directory outage has truthful local reason`() = runBlocking {
        val r=run("w Bochni", directoryUnavailable=true)
        assertTrue(r.reads.isEmpty()); assertEquals("directory_unavailable",r.evidence.reason)
        assertFalse(r.state.text.contains("not authorized"))
    }
    @Test fun `quantity and dimensions cannot authorize branches through short follow-ups`() = runBlocking {
        for(text in listOf("203 m", "203 x 205 mm", "produkt 203", "1000 W")) assertTrue(run(text).reads.isEmpty())
    }
    @Test fun `unknown latest article cannot revive a different older card after failure`() = runBlocking {
        val prior=history("kwant-pl") + user("sprawdź stan A9Z01441/SCH w Bochni") +
            AdvisorLocationHistoryMessage("ASSISTANT","Synthetic failed turn")
        val r=run("w Bochni",prior=prior)
        assertTrue(r.reads.isEmpty()); assertEquals("untrusted_product",r.evidence.reason)
    }
    @Test fun `unknown explicitly labeled short product cannot fall back`() = runBlocking {
        for(text in listOf("sprawdź stan produktu 203 w Bochni","sprawdź stan 203 w Bochni")) {
            assertTrue(run(text).reads.isEmpty())
        }
    }
    @Test fun `current verified short ID supersedes old product selection`() = runBlocking {
        val r=run("sprawdź stan produktu 580 w Bochni",cards=listOf(card("kwant-pl"),card("kwant-pl","580")),modelId="580")
        assertRead(r,"203"); assertEquals("580",r.evidence.productId)
    }
    @Test fun `ambiguous latest USER location prevents tam fallback to older unique market`() = runBlocking {
        val r=run("a tam?","obi-pl",prior=history("obi-pl","sprawdź stan produktu 7000001 w OBI 003") + user("w Krakowie"))
        assertTrue(r.reads.isEmpty()); assertEquals("ambiguous_location",r.evidence.reason)
    }
    @Test fun `unrelated context and provider cards never authorize either provider`() = runBlocking {
        for(p in listOf("obi-pl","kwant-pl")) {
            assertTrue(run("u nas?",p,prior=history(p)+user("pokaż ceny")).reads.isEmpty())
            assertTrue(run("u nas?",p,cards=listOf(card(if(p=="obi-pl")"kwant-pl" else "obi-pl"))).reads.isEmpty())
        }
    }

    @Test fun `no USER location defaults to profile despite wrong model hint`() = runBlocking {
        assertRead(run("sprawdź stan A9Z01440/SCH",hints=listOf("203")),"205")
        assertRead(run("sprawdź stan produktu 7000001","obi-pl",listOf("003")),"075")
    }
    @Test fun `repeated tam retains last USER selection and ignores ASSISTANT suggestion`() = runBlocking {
        val prior=history("kwant-pl") + user("a tam?") + AdvisorLocationHistoryMessage("ASSISTANT","Maybe Tarnów")
        assertRead(run("a tam?",prior=prior),"203")
    }
    @Test fun `wrong provider and duplicate directory stop inventory`() = runBlocking {
        assertTrue(run("w Bochni",modelProvider="obi-pl").reads.isEmpty())
        val r=run("w Bochni",suppliedDirectory=kwant+kwant[1])
        assertTrue(r.reads.isEmpty()); assertEquals("directory_unavailable",r.evidence.reason)
    }
    @Test fun `combined two-product request returns truthful unsupported reason for both providers`() = runBlocking {
        for(p in listOf("kwant-pl","obi-pl")) {
            val cards=listOf(card(p),card(p,if(p=="obi-pl")"7000002" else "90002"))
            val prior=listOf(user("sprawdź stan obu produktów w ${if(p=="obi-pl")"OBI 075" else "Bochni"}"),AdvisorLocationHistoryMessage("ASSISTANT","Synthetic",cards))
            val r=run("obu",p,prior=prior,cards=cards)
            assertTrue(r.reads.isEmpty()); assertEquals("multi_product_tool_contract",r.evidence.reason)
            assertFalse(r.state.text.contains("not authorized")); assertTrue(r.state.text.contains("jeden"))
        }
    }
    @Test fun `discovery unknown location has truthful local explanation without provider work`() = runBlocking {
        var calls=0
        val initial=AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest("resp_tool","call_tool",AdvisorToolArguments("205",listOf(AdvisorToolQuery("synthetic",1)),"kwant-pl")))
        val c=AdvisorController(isConfigured={true},startAgent={_,_,_->initial},messageAgent={_,_,_,_->initial},
            continueAgent={_,_,_,_,_,output->
                assertTrue(output is AdvisorToolContinuation.RejectedStore)
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_final","Synthetic not authorized",emptyList()))
            },executeObiTool={calls++;error("No provider read")},executeProviderTool={calls++;error("No provider read")},branchDirectory={ProviderBranchResult.Available(kwant)})
        val result=c.runTurn("sprawdź stan A9Z01440/SCH w Nieznanowie",null,"205","kwant-pl"){} as AdvisorUiState.Success
        assertEquals(0,calls); assertFalse(result.text.contains("not authorized")); assertTrue(result.text.contains("Nie rozpoznałam"))
    }

    @Test fun `fresh R1 discovery grounds product before R2 location follow-up`() = runBlocking {
        val lookupBranches=mutableListOf<String>()
        val provider=object:ProductProvider {
            override val providerId=ProviderId("kwant-pl")
            override fun branches()=ProviderBranchResult.Available(kwant)
            override fun search(query:String,maxResults:Int)=ProviderSearchResult.Candidates(
                listOf(ProviderProductCandidate(ProductRef(providerId,"90001"),"Synthetic","A9Z01440/SCH")))
            override fun lookup(ref:ProductRef,branchId:BranchId):ProviderLookupResult {
                lookupBranches+=branchId.value
                return ProviderLookupResult.Found(ProviderProduct(ref,branchId,"Synthetic",9,
                    grossPrice=BigDecimal.ONE,priceScope=ProviderPriceScope.ONLINE,
                    productUrl="https://offline.invalid/product",ean=null,articleNumber="A9Z01440/SCH"))
            }
        }
        val tool=FindProviderProductsTool(ProductProviderRegistry(listOf(provider)),now={1000L})
        val firstRequest=AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest("resp_discovery","call_discovery",
            AdvisorToolArguments("205",listOf(AdvisorToolQuery("A9Z01440/SCH",1)),"kwant-pl","Bochni")))
        val firstController=AdvisorController(isConfigured={true},startAgent={_,_,_->firstRequest},messageAgent={_,_,_,_->error("Fresh START only")},
            continueAgent={_,_,_,profile,_,output->
                assertEquals("205",profile);assertTrue(output is AdvisorToolContinuation.Verified)
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_first","Synthetic verified product",
                    listOf(AdvisorProductRef("203","90001","kwant-pl"))))
            },executeObiTool={error("Wrong provider")},executeProviderTool=tool::execute,branchDirectory={provider.branches()})
        val input="sprawdź stan A9Z01440/SCH w Bochni"
        val first=firstController.runTurn(input,null,"205","kwant-pl"){} as AdvisorUiState.Success
        assertEquals(listOf("203"),lookupBranches);assertEquals(1,first.products.size)
        assertEquals("203",first.products.single().effectiveBranchId);assertEquals(9,first.products.single().stock)
        val second=run("w Bochni",hints=listOf("205"),cards=first.products,
            prior=listOf(user(input),AdvisorLocationHistoryMessage("ASSISTANT",first.text,first.products)),previousResponseId=first.responseId)
        assertRead(second,"203")
        assertEquals(7,second.evidence.locations.single().stock)
        assertTrue(second.state.products.isEmpty())
    }

    @Test fun `two explicit historical product IDs cannot be narrowed by model or one ASSISTANT card`() = runBlocking {
        val cards=listOf(card("kwant-pl","9911"),card("kwant-pl","9922"))
        val prior=listOf(user("sprawdź stan produktu 9911 i produktu 9922 w Bochni"),AdvisorLocationHistoryMessage("ASSISTANT","Synthetic",listOf(cards.first())))
        val r=run("w Bochni",cards=cards,prior=prior,modelId="9911")
        assertTrue(r.reads.isEmpty());assertEquals("ambiguous_product",r.evidence.reason)
    }
    @Test fun `manufacturer suffix omission remains ambiguous when two verified articles share a base`() = runBlocking {
        val cards=listOf(card("kwant-pl","9911").copy(articleNumber="MBN116E/HAG"),card("kwant-pl","9922").copy(articleNumber="MBN116E/OTHER"))
        val prior=listOf(user("sprawdź stan MBN116E w Bochni"),AdvisorLocationHistoryMessage("ASSISTANT","Synthetic",cards))
        val r=run("w Bochni",cards=cards,prior=prior,modelId="9911")
        assertTrue(r.reads.isEmpty());assertEquals("ambiguous_product",r.evidence.reason)
    }

    @Test fun `exact OBI A availability question establishes USER anchor after verified discovery`() = runBlocking {
        val anchor="potrzebuje wkładki do drzwi 30/20G czy jest w OBI jeśli nie to w podobnych parametrach"
        assertRead(run("a w Krakowie na Wielickiej?","obi-pl",listOf("075"),history("obi-pl",anchor)),"003")
    }

    @Test fun `fresh same-turn discovery unlocks retry only after independently verified identity`() = runBlocking {
        val lookupBranches=mutableListOf<String>()
        val provider=object:ProductProvider {
            override val providerId=ProviderId("kwant-pl")
            override fun branches()=ProviderBranchResult.Available(kwant)
            override fun search(query:String,maxResults:Int)=ProviderSearchResult.Candidates(
                listOf(ProviderProductCandidate(ProductRef(providerId,"90001"),"Synthetic","A9Z01440/SCH")))
            override fun lookup(ref:ProductRef,branchId:BranchId):ProviderLookupResult {
                lookupBranches+=branchId.value
                return ProviderLookupResult.Found(ProviderProduct(ref,branchId,"Synthetic",9,
                    grossPrice=BigDecimal.ONE,priceScope=ProviderPriceScope.ONLINE,
                    productUrl="https://offline.invalid/product",ean=null,articleNumber="A9Z01440/SCH"))
            }
        }

        val discovery=FindProviderProductsTool(ProductProviderRegistry(listOf(provider)),now={1000L})
        var inventoryReads=0
        val adapter=object:ProductLocationsAdapter {
            override val providerId=ProviderId("kwant-pl")
            override suspend fun read(ref:ProductRef,requested:List<BranchId>,trustedProduct:ProviderProduct?):ProductLocationsResult {
                inventoryReads++;assertEquals("90001",ref.productId);assertEquals(listOf(BranchId("203")),requested)
                return ProductLocationsResult.Available(ref,listOf(LocationStock(kwant.single{it.branchId.value=="203"},7)),
                    LocationCoverage(LocationCoverageKind.REQUESTED_SUBSET,requested,requested,1),2000)
            }
        }
        val locations=AdvisorLocationsTool(ProductLocationsService(listOf(adapter)),{ProviderBranchResult.Available(obi)},{provider.branches()}, providers = ProductProviderRegistry(listOf(provider)), now = { 2000L })
        fun request(suffix:String)=AdvisorProxyCallResult.Success(AdvisorProxyResult.LocationToolRequest("resp_$suffix","call_$suffix",AdvisorLocationArguments("kwant-pl","90001",listOf("205"))))
        var continuations=0
        val c=AdvisorController(isConfigured={true},startAgent={_,_,_->request("one")},messageAgent={_,_,_,_->request("one")},
            continueAgent={response,call,_,profile,_,output->
                assertEquals("205",profile)
                when(++continuations) {
                    1->{
                        assertEquals("resp_one",response);assertEquals("call_one",call)
                        assertEquals("untrusted_product",(output as AdvisorToolContinuation.Locations).evidence.reason)
                        assertEquals(0,inventoryReads)
                        AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest("resp_discovery","call_discovery",AdvisorToolArguments("205",listOf(AdvisorToolQuery("A9Z01440/SCH",1)),"kwant-pl","Bochni")))
                    }
                    2->{assertEquals("resp_discovery",response);assertEquals("call_discovery",call);assertTrue(output is AdvisorToolContinuation.Verified);request("two")}
                    3->{
                        assertEquals("resp_two",response);assertEquals("call_two",call)
                        val evidence=(output as AdvisorToolContinuation.Locations).evidence
                        assertEquals("verified",evidence.status);assertEquals(listOf("203"),evidence.checkedIds)
                        AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_final","Synthetic verified",listOf(AdvisorProductRef("203","90001","kwant-pl"))))
                    }
                    else->error("Existing three-call budget must suffice")
                }
            },executeObiTool={error("Wrong provider")},executeProviderTool=discovery::execute,executeLocationsTool=locations::execute,branchDirectory={provider.branches()})
        val prior=listOf(user("sprawdź stan A9Z01440/SCH w Bochni"),AdvisorLocationHistoryMessage("ASSISTANT","Synthetic failed turn"))
        val state=c.runTurn("w Bochni",null,"205","kwant-pl",locationHistory=prior){} as AdvisorUiState.Success
        assertEquals(3,continuations);assertEquals(0,inventoryReads);assertEquals(listOf("203", "203"),lookupBranches)
        assertEquals("90001",state.products.single().effectiveProductId);assertEquals(1000L,state.products.single().verifiedAt)
    }

    @Test fun `fresh unrelated discovery cannot overwrite USER-selected historical product`() = runBlocking {
        val lookupBranches=mutableListOf<String>()
        val provider=object:ProductProvider {
            override val providerId=ProviderId("kwant-pl")
            override fun branches()=ProviderBranchResult.Available(kwant)
            override fun search(query:String,maxResults:Int)=ProviderSearchResult.Candidates(
                listOf(ProviderProductCandidate(ProductRef(providerId,"90002"),"Synthetic","OTHER02/SCH")))
            override fun lookup(ref:ProductRef,branchId:BranchId):ProviderLookupResult {
                lookupBranches+=branchId.value
                return ProviderLookupResult.Found(ProviderProduct(ref,branchId,"Synthetic",9,
                    grossPrice=BigDecimal.ONE,priceScope=ProviderPriceScope.ONLINE,
                    productUrl="https://offline.invalid/product",ean=null,articleNumber="OTHER02/SCH"))
            }
        }

        val discovery=FindProviderProductsTool(ProductProviderRegistry(listOf(provider)),now={1000L})
        var inventoryReads=0
        val adapter=object:ProductLocationsAdapter {
            override val providerId=ProviderId("kwant-pl")
            override suspend fun read(ref:ProductRef,requested:List<BranchId>,trustedProduct:ProviderProduct?):ProductLocationsResult {
                inventoryReads++;return ProductLocationsResult.Unavailable(LocationFailure.TRANSPORT)
            }
        }
        val locations=AdvisorLocationsTool(ProductLocationsService(listOf(adapter)),{ProviderBranchResult.Available(obi)},{provider.branches()}, providers = ProductProviderRegistry(listOf(provider)), now = { 2000L })
        var continuations=0
        val first=AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest("resp_discover","call_discover",AdvisorToolArguments("205",listOf(AdvisorToolQuery("OTHER02/SCH",1)),"kwant-pl","Bochni")))
        val c=AdvisorController(isConfigured={true},startAgent={_,_,_->first},messageAgent={_,_,_,_->first},
            continueAgent={_,_,_,_,_,output->
                if(++continuations==1) {
                    assertTrue(output is AdvisorToolContinuation.Verified)
                    AdvisorProxyCallResult.Success(AdvisorProxyResult.LocationToolRequest("resp_stock","call_stock",AdvisorLocationArguments("kwant-pl","90002",listOf("203"))))
                } else {
                    val e=(output as AdvisorToolContinuation.Locations).evidence
                    assertEquals("rejected",e.status);assertEquals("untrusted_product",e.reason)
                    AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_final","Synthetic",emptyList()))
                }
            },executeObiTool={error("Wrong provider")},executeProviderTool=discovery::execute,executeLocationsTool=locations::execute,branchDirectory={provider.branches()})
        c.runTurn("w Bochni",null,"205","kwant-pl",historicalVerifiedProducts=listOf(card("kwant-pl")),locationHistory=history("kwant-pl")){}
        assertEquals(0,inventoryReads);assertEquals(listOf("203"),lookupBranches)
    }

}
