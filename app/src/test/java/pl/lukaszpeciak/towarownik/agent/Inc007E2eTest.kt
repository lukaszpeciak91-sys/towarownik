package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.*
import pl.lukaszpeciak.towarownik.product.provider.*

/** Scripted model outputs, actual Controller/tools/adapters; no production stock claims. */
class Inc007E2eTest {
    private val kwant = listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz"),
        ProviderBranch(BranchId("203"), "Bochnia"),
        ProviderBranch(BranchId("206"), "Mielec"),
        ProviderBranch(BranchId("204"), "Tarnów"),
    )
    private val obi = OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }
    private val kwantUsers = listOf(
        "sprawdź proszę stan A9Z01440/SCH w Bochni",
        "a w oddziale w Mielcu?",
        "nie sprawdzisz mi w Mielcu?",
        "dobrze a w oddziale Tarnów?",
        "A9Z01440/SCH",
        "co do kurwy? a co to niby jest jak nie prośba? A9Z01440/SCH weź kurwa sprawdź stan w Tarnowie",
    )
    private val kwantReplies = listOf(
        "W Bochni stan A9Z01440/SCH wynosi 0 szt. W magazynie centralnym jest 53 szt. — to osobny stan, nie zapas oddziału. Cena online: 254,13 zł.",
        "Wskazane oddziały KWANT — wyniki niepełne: sprawdzono 1, dodatni stan: 0, zero: 0, niepotwierdzone: 1\n206 — Mielec — brak potwierdzenia (stan nieznany)\nBrak potwierdzenia nie oznacza stanu 0.",
        "Sprawdziłem — system nie zwrócił potwierdzonego stanu dla Mielca, więc nie mogę podać liczby sztuk. Wynik jest nieznany, a nie równy zero.",
        "Nie mam aktualnej prośby użytkownika o sprawdzenie stanów ani nieprzerwanego kontekstu takiej prośby. Czy chcesz sprawdzić stan konkretnego produktu? Nie sprawdziłam stanów.",
        "Nie mam aktualnej prośby użytkownika o sprawdzenie stanów ani nieprzerwanego kontekstu takiej prośby. Czy chcesz sprawdzić stan konkretnego produktu? Nie sprawdziłam stanów.",
        "Wskazane oddziały KWANT — wyniki niepełne: sprawdzono 1, dodatni stan: 0, zero: 0, niepotwierdzone: 1\n204 — Tarnów — brak potwierdzenia (stan nieznany)\nBrak potwierdzenia nie oznacza stanu 0.",
    )
    private val obiUsers = listOf(
        "potrzebuje wkładki do drzwi 30/20g lub coś o podobnych parametrach, czy jest coś takiego u nas?",
        "6346951 czy mają w krakowskich marketach? sprawdź proszę",
    )
    private val obiFirstReply = "Wkładki dokładnie 30/20 G nie udało mi się potwierdzić w naszym markecie. Znalazłem podobne rozmiary: Gerda 30/30 oraz Domino 26/36, ale obie mają obecnie potwierdzony stan 0 w wybranym markecie, więc nie mogę polecić ich jako dostępnych u nas.\n\nPrzed doborem sprawdź, czy oznaczenie „G” dotyczy strony z gałką — wkładki 30/20 i 30/20G nie są zamienne. Najpewniej zmierzyć starą wkładkę od środka śruby mocującej do obu końców, osobno po każdej stronie, i potwierdzić, czy ma gałkę. Jeśli podasz te wymiary i informację o gałce, pomogę dobrać właściwy wariant."

    private inner class Scenario(val provider: String, var failure: String = "complete") {
        val selected = if (provider == "kwant-pl") "205" else "075"
        val directory = if (provider == "kwant-pl") kwant else obi
        val history = mutableListOf<AdvisorLocationHistoryMessage>()
        val events = mutableListOf<String>()
        val lookups = mutableListOf<Pair<String,String>>()
        val gets = mutableListOf<String>()
        var queryId = if(provider == "kwant-pl") "77556" else "6346951"
        var query = if(provider == "kwant-pl") "A9Z01440/SCH" else "wkładka 30/20G"
        var plan = "discovery"
        var hints = emptyList<String>()
        var modelProvider = provider
        var current = ""
        var previous: String? = null
        var serial = 0
        var evidence: AdvisorLocationEvidence? = null
        var continuation: AdvisorToolContinuation? = null
        val providerImpl = object: ProductProvider {
            override val providerId = ProviderId(provider)
            override fun branches() = ProviderBranchResult.Available(directory)
            override fun search(query:String,maxResults:Int) = ProviderSearchResult.Candidates(
                listOf(ProviderProductCandidate(ProductRef(providerId,queryId),"Synthetic",if(provider=="kwant-pl")this@Scenario.query else null)))
            override fun lookup(ref:ProductRef,branchId:BranchId):ProviderLookupResult {
                lookups += ref.productId to branchId.value
                return ProviderLookupResult.Found(ProviderProduct(ref,branchId,"Synthetic",0,
                    centralStock=53,grossPrice=BigDecimal.ONE,priceScope=ProviderPriceScope.ONLINE,
                    productUrl="https://offline.invalid/product",ean=null,articleNumber=this@Scenario.query))
            }
        }
        val productTool = FindProviderProductsTool(ProductProviderRegistry(listOf(providerImpl)),now={1000L})
        val obiTool = FindObiProductsTool(
            searchProducts={ProductSearchResult.Candidates(listOf(ProductSearchCandidate(queryId,"Synthetic")))},
            lookupObik={id,branch-> lookups += id to branch
                ProductLookupResult.Found(LocalProduct(id,"Synthetic",0,BigDecimal.ONE,"https://offline.invalid/product",null,branch))},
            now={1000L})
        val fetcher = LocationsHttpFetcher { url ->
            gets += url.toString()
            if(failure=="transport") LocationsHttpResult.Failure(LocationFailure.TRANSPORT)
            else if(provider=="obi-pl") LocationsHttpResult.Success(url.queryParameter("storeIds")!!.split(",").joinToString(",","[","]") {
                "{\"storeId\":\"$it\",\"availableQuantity\":7}"
            }) else {
                val rows = when(failure) {
                    "null" -> "{\"department_id\":206,\"stock\":null},{\"department_id\":204,\"stock\":null}"
                    "omitted" -> "{\"department_id\":203,\"stock\":0}"
                    "wrong_identity" -> "{\"department_id\":206,\"stock\":11,\"product_id\":99999},{\"department_id\":204,\"stock\":13}"
                    "extra_field" -> "{\"department_id\":206,\"stock\":11,\"name\":\"Synthetic\"}"
                    else -> "{\"department_id\":203,\"stock\":0},{\"department_id\":206,\"stock\":11},{\"department_id\":204,\"stock\":13},{\"department_id\":205,\"stock\":17}"
                }
                LocationsHttpResult.Success("{\"product_id\":77556,\"list\":[$rows],\"total_stock\":999}")
            }
        }
        val service = ProductLocationsService(listOf(
            KwantProductLocationsAdapter(fetcher,{ProviderBranchResult.Available(kwant)},now={2000L}),
            ObiProductLocationsAdapter(fetcher,{ProviderBranchResult.Available(obi)},now={2000L})))
        val locationsTool = AdvisorLocationsTool(service,{ProviderBranchResult.Available(obi)},{ProviderBranchResult.Available(kwant)},
            providers = exactLookupProviders { ref, branch ->
                // These are synthetic ordinary-lookup events, not inventory adapter reads.
                gets += if (provider == "kwant-pl") "https://services.kwant.net.pl/api/front/products/${ref.productId}/current?depstock=${branch.value}"
                    else "https://www.obi.pl/api/disc/store/change?storeNumber=${branch.value}&redirectUrl=/p/${ref.productId}"
                when (failure) {
                    "transport" -> ProviderLookupResult.Unavailable(ProductProviderFailure.NETWORK, "Synthetic failure")
                    "bad", "wrong_identity", "extra_field" -> ProviderLookupResult.Unavailable(ProductProviderFailure.DATA, "Synthetic malformed product")
                    "null", "omitted" -> exactLookupFound(ref, branch, null)
                    else -> exactLookupFound(ref, branch, if (provider == "obi-pl") 7 else when (branch.value) { "203" -> 0; "206" -> 11; "204" -> 13; else -> 17 })
                }
            }, now = { 2000L })
        fun initial():AdvisorProxyCallResult {
            events += "${if(previous==null)"START" else "MESSAGE"}:$serial:$plan"
            val result = if(plan=="discovery") AdvisorProxyResult.ToolRequest("resp_$serial","call_$serial",
                AdvisorToolArguments(selected,listOf(AdvisorToolQuery(query,1)),modelProvider,null))
            else AdvisorProxyResult.LocationToolRequest("resp_$serial","call_$serial",AdvisorLocationArguments(modelProvider,queryId,hints))
            return AdvisorProxyCallResult.Success(result)
        }
        val controller = AdvisorController(isConfigured={true},startAgent={_,p,b->assertEquals(provider,p);assertEquals(selected,b);initial()},
            messageAgent={id,_,p,b->assertEquals(previous,id);assertEquals(provider,p);assertEquals(selected,b);initial()},
            continueAgent={response,call,p,b,_,output->
                assertEquals("resp_$serial",response);assertEquals("call_$serial",call);assertEquals(provider,p);assertEquals(selected,b)
                continuation=output;evidence=(output as? AdvisorToolContinuation.Locations)?.evidence
                events += "CONTINUE:$serial:${evidence?.reason ?: output.javaClass.simpleName}"
                val refs=if(output is AdvisorToolContinuation.Verified)lookups.lastOrNull()?.let { listOf(AdvisorProductRef(it.second,it.first,provider)) }.orEmpty() else emptyList()
                AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("final_$serial","Synthetic model answer",refs))
            },executeObiTool=obiTool::execute,executeProviderTool=productTool::execute,
            executeLocationsTool=locationsTool::execute,branchDirectory={ProviderBranchResult.Available(directory)})
        suspend fun turn(input:String,tool:String,reportedReply:String?=null):AdvisorUiState.Success {
            serial++;current=input;plan=tool;evidence=null;continuation=null
            // Same filtering as MainActivity: previous 12 messages, newest 3 nonempty ASSISTANT card sets.
            val historical=history.takeLast(12).asReversed().filter {it.role=="ASSISTANT"&&it.products.isNotEmpty()}.take(3).flatMap {it.products}
            val before=gets.size
            val state=controller.runTurn(input,previous,selected,provider,historicalVerifiedProducts=historical,locationHistory=history.takeLast(12)){} as AdvisorUiState.Success
            println("INC007 provider=$provider turn=$serial plan=$tool continuation=${continuation?.javaClass?.simpleName} status=${evidence?.status} reason=${evidence?.reason} checked=${evidence?.checkedIds} getDelta=${gets.size-before} cards=${state.products.map{it.effectiveProductId+"@"+it.effectiveBranchId}} text=${state.text.replace('\n','|')}")
            history += AdvisorLocationHistoryMessage("USER",input)
            history += AdvisorLocationHistoryMessage("ASSISTANT",reportedReply?:state.text,state.products)
            previous=state.responseId
            return state
        }
    }

    private suspend fun sixTurns(bareCodeDiscovery:Boolean):List<Int> {
        val s=Scenario("kwant-pl");val rejected=mutableListOf<Int>()
        for(i in kwantUsers.indices) {
            val plan=if(i==0 || i==4&&bareCodeDiscovery)"discovery" else "locations"
            val state=s.turn(kwantUsers[i],plan,kwantReplies[i])
            if(plan=="discovery") {if(state.products.isEmpty())rejected+=i+1}
            else if(s.evidence?.status!="verified")rejected+=i+1
        }
        assertTrue(s.events.first().startsWith("START"));assertEquals(5,s.events.count{it.startsWith("MESSAGE")})
        assertEquals(6,s.events.count{it.startsWith("CONTINUE")});assertTrue(s.gets.all{it.contains("/77556/current?depstock=")})
        return rejected
    }
    @Test fun `RED exact KWANT six turn history with stock tool after discovery`()=runBlocking {
        assertEquals("Every admitted request should recover the intended operation, not deny ordinary USER requests",emptyList<Int>(),sixTurns(false))
    }
    @Test fun `RED exact KWANT history with bare code independently discovered`()=runBlocking {
        assertEquals(emptyList<Int>(),sixTurns(true))
    }
    @Test fun `RED exact OBI two turn history new product discovery`()=runBlocking {
        val s=Scenario("obi-pl");s.queryId="7777000"
        assertEquals(1,s.turn(obiUsers[0],"discovery",obiFirstReply).products.size)
        s.queryId="6346951";s.query="6346951"
        val state=s.turn(obiUsers[1],"discovery")
        assertEquals("A new exact identity must be verifiable before plural inventory scope",1,state.products.size)
    }
    @Test fun `RED exact OBI plural query with already trusted matching product`()=runBlocking {
        val scopes=mutableListOf<Set<String>>()
        for(h in listOf(emptyList(),listOf("003"),listOf("075"))) {
            val s=Scenario("obi-pl");s.turn(obiUsers[0],"discovery",obiFirstReply);s.hints=h
            s.turn(obiUsers[1],"locations");scopes+=s.evidence?.checkedIds.orEmpty().toSet()
        }
        val expected=obi.filter{it.name=="Kraków"}.map{it.branchId.value}.toSet()
        assertEquals(List(3){expected},scopes)
    }
    @Test fun `GREEN explicitly worded all Krakow markets is supported`()=runBlocking {
        val s=Scenario("obi-pl");s.turn(obiUsers[0],"discovery",obiFirstReply)
        s.hints=listOf("075")
        s.turn("6346951 sprawdź stan we wszystkich marketach w Krakowie","locations")
        assertEquals(obi.filter{it.name=="Kraków"}.map{it.branchId.value}.toSet(),s.evidence?.checkedIds?.toSet())
    }
    @Test fun `GREEN forty independent back to back searches reset budgets for both providers`()=runBlocking {
        for(p in listOf("kwant-pl","obi-pl")) {
            val s=Scenario(p)
            repeat(40) { i ->
                s.queryId=if(p=="kwant-pl")"${77556+i}" else "${6346951+i}"
                s.query=if(p=="kwant-pl")"A9Z${1440+i}/SCH" else s.queryId
                val state=s.turn(if(p=="obi-pl")"sprawdź produkt ${s.query}" else s.query,"discovery")
                assertEquals(s.queryId,state.products.single().effectiveProductId);assertEquals(s.selected,state.products.single().effectiveBranchId)
            }
            assertEquals(40,s.lookups.size);assertEquals(39,s.events.count{it.startsWith("MESSAGE")})
        }
    }
    @Test fun `RED bare OBI identifiers are products not unknown market numbers`()=runBlocking {
        val s=Scenario("obi-pl");val failures=mutableListOf<Int>()
        repeat(40) {i ->
            s.queryId="${6346951+i}";s.query=s.queryId
            if(s.turn(s.query,"discovery").products.isEmpty())failures+=i+1
        }
        assertEquals("Numeric OBIKs must not become location selections",emptyList<Int>(),failures)
    }
    @Test fun `GREEN advice prices and assistant suggestions never authorize stock`()=runBlocking {
        for(text in listOf("pokaż ceny","jak podłączyć pompę w Tarnowie","a tam?")) {
            val s=Scenario("kwant-pl");s.turn(kwantUsers[0],"discovery",kwantReplies[0])
            s.history += AdvisorLocationHistoryMessage("USER","pokaż ceny")
            s.history += AdvisorLocationHistoryMessage("ASSISTANT","Może sprawdź w Tarnowie?")
            s.turn(text,"locations");assertTrue(s.gets.isEmpty())
        }
    }
    @Test fun `GREEN unverified explicit product never falls back to another old card`()=runBlocking {
        val s=Scenario("kwant-pl");s.turn(kwantUsers[0],"discovery",kwantReplies[0]);s.turn("sprawdź stan A9Z01441/SCH w Tarnowie","locations")
        assertTrue(s.gets.isEmpty());assertEquals("untrusted_product",s.evidence?.reason)
    }
    @Test fun `GREEN missing trusted identity and wrong provider stop all inventory`()=runBlocking {
        val s=Scenario("kwant-pl");s.turn("sprawdź stan A9Z01440/SCH w Tarnowie","locations")
        assertTrue(s.gets.isEmpty());assertEquals("untrusted_product",s.evidence?.reason)
        s.turn(kwantUsers[0],"discovery",kwantReplies[0]);s.modelProvider="obi-pl";s.turn("w Tarnowie","locations")
        assertEquals("wrong_provider",s.evidence?.reason);assertTrue(s.gets.isEmpty())
    }
    @Test fun `GREEN valid fixture zero positive stock without promoting old central stock`()=runBlocking {
        val s=Scenario("kwant-pl");s.turn(kwantUsers[0],"discovery",kwantReplies[0])
        for((place,stock) in listOf("Bochni" to 0,"Mielcu" to 11,"Tarnowie" to 13)) {
            s.turn("sprawdź stan A9Z01440/SCH w $place","locations")
            assertEquals(stock,s.evidence?.locations?.single()?.stock);assertNull(s.evidence?.centralStock)
        }
    }
    private suspend fun unknownFingerprint(kind:String,reason:String) {
        val s=Scenario("kwant-pl",kind);s.turn(kwantUsers[0],"discovery",kwantReplies[0])
        for(place in listOf("Mielcu","Tarnowie")) {
            val state=s.turn("sprawdź stan A9Z01440/SCH w $place","locations")
            assertEquals(reason,s.evidence?.reason);assertNull(s.evidence?.locations?.single()?.stock)
            assertTrue(state.text.contains("niepotwierdzone: 1"));assertTrue(state.text.contains("nie oznacza stanu 0"))
        }
        assertEquals(2,s.gets.size)
    }
    @Test fun `GREEN transport UNKNOWN fingerprint`()=runBlocking {unknownFingerprint("transport","transport")}
    @Test fun `GREEN wrong product identity UNKNOWN fingerprint`()=runBlocking {unknownFingerprint("wrong_identity","malformed_response")}
    @Test fun `GREEN extra field parser UNKNOWN fingerprint`()=runBlocking {unknownFingerprint("extra_field","malformed_response")}
    @Test fun `GREEN missing branch rows UNKNOWN fingerprint`()=runBlocking {unknownFingerprint("omitted","no_trusted_rows")}
    @Test fun `GREEN null stock UNKNOWN fingerprint`()=runBlocking {unknownFingerprint("null","no_trusted_rows")}
    @Test fun `GREEN direct stock service remains independent of Advisor prose`()=runBlocking {
        val s=Scenario("kwant-pl");s.turn(kwantUsers[0],"discovery",kwantReplies[0]);s.history += AdvisorLocationHistoryMessage("USER","pokaż ceny")
        s.turn("w Mielcu","locations");assertTrue(s.gets.isEmpty())
        val result=s.service.read(ProductRef(ProviderId("kwant-pl"),"77556"),listOf(BranchId("206"))) as ProductLocationsResult.Available
        assertEquals(11,result.locations.single().stock);assertEquals(1,s.gets.size)
    }
}
