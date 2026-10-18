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

/** Scripted model outputs, actual Controller/tools/adapters; no production stock claims. */
class Inc007ObiScopeRegressionTest {
    private val kwant = listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz"),
        ProviderBranch(BranchId("203"), "Bochnia"),
        ProviderBranch(BranchId("206"), "Mielec"),
        ProviderBranch(BranchId("204"), "Tarnów"),
    )
    private val obi = OBI_STORES.map { ProviderBranch(BranchId(it.storeNumber), it.city, it.address) }
    private val obiUsers = listOf(
        "potrzebuje wkładki do drzwi 30/20g lub coś o podobnych parametrach, czy jest coś takiego u nas?",
        "6346951 czy mają w krakowskich marketach? sprawdź proszę",
    )
    private val obiFirstReply = "Wkładki dokładnie 30/20 G nie udało mi się potwierdzić w naszym markecie. Znalazłem podobne rozmiary: Gerda 30/30 oraz Domino 26/36, ale obie mają obecnie potwierdzony stan 0 w wybranym markecie, więc nie mogę polecić ich jako dostępnych u nas.\n\nPrzed doborem sprawdź, czy oznaczenie „G” dotyczy strony z gałką — wkładki 30/20 i 30/20G nie są zamienne. Najpewniej zmierzyć starą wkładkę od środka śruby mocującej do obu końców, osobno po każdej stronie, i potwierdzić, czy ma gałkę. Jeśli podasz te wymiary i informację o gałce, pomogę dobrać właściwy wariant."

    private inner class Scenario(val provider: String, var failure: String = "complete", suppliedDirectory: List<ProviderBranch>? = null) {
        val selected = if (provider == "kwant-pl") "205" else "075"
        val directory = suppliedDirectory ?: if (provider == "kwant-pl") kwant else obi
        val history = mutableListOf<AdvisorLocationHistoryMessage>()
        val events = mutableListOf<String>()
        val lookups = mutableListOf<Pair<String,String>>()
        val gets = mutableListOf<String>()
        var verificationUnavailable = false
        var queryId = if(provider == "kwant-pl") "77556" else "6346951"
        var query = if(provider == "kwant-pl") "A9Z01440/SCH" else "wkładka 30/20G"
        var plan = "discovery"
        var hints = emptyList<String>()
        var modelProvider = provider
        var modelBranch = selected
        var locationProductId: String? = null
        var phase = 0
        var freshRef: AdvisorProductRef? = null
        val outputs = mutableListOf<AdvisorToolContinuation>()
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
                if(verificationUnavailable) ProductLookupResult.Unavailable(ProductLookupFailure.NETWORK,"Synthetic unavailable") else
                ProductLookupResult.Found(LocalProduct(id,"Synthetic",0,BigDecimal.ONE,"https://offline.invalid/product",null,branch))},
            now={1000L})
        val fetcher = LocationsHttpFetcher { url ->
            gets += url.toString()
            if(failure=="transport") LocationsHttpResult.Failure(LocationFailure.TRANSPORT)
            else if(provider=="obi-pl") {
                val ids=url.queryParameter("storeIds")!!.split(",")
                val chosen=if(failure=="omitted")ids.dropLast(1) else ids
                val value=when(failure){"null"->"null";"bad"->"-1";else->"7"}
                LocationsHttpResult.Success(chosen.joinToString(",","[","]") {"{\"storeId\":\"$it\",\"availableQuantity\":$value}"})
            } else {
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
        val locationsTool = AdvisorLocationsTool(service,{ProviderBranchResult.Available(if(provider=="obi-pl")directory else obi)},{ProviderBranchResult.Available(if(provider=="kwant-pl")directory else kwant)},
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
            val result = if(plan.startsWith("discovery")) AdvisorProxyResult.ToolRequest("resp_${serial}_$phase","call_${serial}_$phase",
                AdvisorToolArguments(modelBranch,listOf(AdvisorToolQuery(query,1)),modelProvider,null))
            else AdvisorProxyResult.LocationToolRequest("resp_${serial}_$phase","call_${serial}_$phase",AdvisorLocationArguments(modelProvider,locationProductId?:queryId,hints))
            return AdvisorProxyCallResult.Success(result)
        }
        val controller = AdvisorController(isConfigured={true},startAgent={_,p,b->assertEquals(provider,p);assertEquals(selected,b);initial()},
            messageAgent={id,_,p,b->assertEquals(previous,id);assertEquals(provider,p);assertEquals(selected,b);initial()},
            continueAgent={response,call,p,b,_,output->
                assertEquals("resp_${serial}_$phase",response);assertEquals("call_${serial}_$phase",call);assertEquals(provider,p);assertEquals(selected,b)
                continuation=output;evidence=(output as? AdvisorToolContinuation.Locations)?.evidence
                events += "CONTINUE:$serial:${evidence?.reason ?: output.javaClass.simpleName}"
                outputs += output
                if (output is AdvisorToolContinuation.Verified) {
                    freshRef=output.result.results.flatMap{it.products}.firstOrNull{it.obik==queryId}
                        ?.let {AdvisorProductRef(output.result.storeNumber,it.obik,provider)}
                }
                if(plan=="locations_discovery_locations" && phase==0) {
                    phase++
                    AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest("resp_${serial}_$phase","call_${serial}_$phase",AdvisorToolArguments(modelBranch,listOf(AdvisorToolQuery(query,1)),modelProvider,null)))
                } else if ((plan=="discovery_then_locations" && phase==0) || (plan=="locations_discovery_locations" && phase==1)) {
                    phase++
                    AdvisorProxyCallResult.Success(AdvisorProxyResult.LocationToolRequest("resp_${serial}_$phase","call_${serial}_$phase",AdvisorLocationArguments(modelProvider,locationProductId?:queryId,hints)))
                } else AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("final_$serial","Synthetic model: all requested markets have stock 99",listOfNotNull(freshRef)))
            },executeObiTool=obiTool::execute,executeProviderTool=productTool::execute,
            executeLocationsTool=locationsTool::execute,branchDirectory={ProviderBranchResult.Available(directory)})
        suspend fun turn(input:String,tool:String,reportedReply:String?=null):AdvisorUiState.Success =
            run(input,tool,reportedReply) as AdvisorUiState.Success

        suspend fun run(input:String,tool:String,reportedReply:String?=null):AdvisorUiState {
            serial++;plan=tool;evidence=null;continuation=null;phase=0;freshRef=null;outputs.clear()
            // Same filtering as MainActivity: previous 12 messages, newest 3 nonempty ASSISTANT card sets.
            val historical=history.takeLast(12).asReversed().filter {it.role=="ASSISTANT"&&it.products.isNotEmpty()}.take(3).flatMap {it.products}
            val before=gets.size
            val result=controller.runTurn(input,previous,selected,provider,historicalVerifiedProducts=historical,locationHistory=history.takeLast(12)){}
            val state=result as? AdvisorUiState.Success
            println("INC007_R1_SYNTHETIC provider=$provider turn=$serial plan=$tool continuation=${continuation?.javaClass?.simpleName} status=${evidence?.status} reason=${evidence?.reason} checked=${evidence?.checkedIds} getDelta=${gets.size-before} cards=${state?.products.orEmpty().map{it.effectiveProductId+"@"+it.effectiveBranchId}} text=${state?.text?.replace('\n','|')?:result.toString()}")
            history += AdvisorLocationHistoryMessage("USER",input)
            if(state!=null) {
                history += AdvisorLocationHistoryMessage("ASSISTANT",reportedReply?:state.text,state.products)
                previous=state.responseId
            }
            return result
        }
    }

    @Test fun `exact OBI two turn history new product discovery`()=runBlocking {
        val s=Scenario("obi-pl");s.queryId="7777000"
        assertEquals(1,s.turn(obiUsers[0],"discovery",obiFirstReply).products.size)
        s.queryId="6346951";s.query="6346951"
        val state=s.turn(obiUsers[1],"discovery")
        assertEquals("A new exact identity must be verifiable before plural inventory scope",1,state.products.size)
    }
    @Test fun `exact OBI plural query with already trusted matching product`()=runBlocking {
        val scopes=mutableListOf<Set<String>>()
        for(h in listOf(emptyList(),listOf("003"),listOf("075"))) {
            val s=Scenario("obi-pl");s.turn(obiUsers[0],"discovery",obiFirstReply);s.hints=h
            s.turn(obiUsers[1],"locations");scopes+=s.evidence?.checkedIds.orEmpty().toSet()
        }
        val expected=obi.filter{it.name=="Kraków"}.map{it.branchId.value}.toSet()
        assertEquals(List(3){expected},scopes)
    }
    @Test fun `explicitly worded all Krakow markets is supported`()=runBlocking {
        val s=Scenario("obi-pl");s.turn(obiUsers[0],"discovery",obiFirstReply)
        s.hints=listOf("075")
        s.turn("6346951 sprawdź stan we wszystkich marketach w Krakowie","locations")
        assertEquals(obi.filter{it.name=="Kraków"}.map{it.branchId.value}.toSet(),s.evidence?.checkedIds?.toSet())
    }
    @Test fun `forty independent back to back searches reset budgets for both providers`()=runBlocking {
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
    @Test fun `bare OBI identifiers are products not unknown market numbers`()=runBlocking {
        val s=Scenario("obi-pl");val failures=mutableListOf<Int>()
        repeat(40) {i ->
            s.queryId="${6346951+i}";s.query=s.queryId
            if(s.turn(s.query,"discovery").products.isEmpty())failures+=i+1
        }
        assertEquals("Numeric OBIKs must not become location selections",emptyList<Int>(),failures)
    }

    private fun krakowIds()=obi.filter{it.name=="Kraków"}.map{it.branchId.value}.toSet()

    @Test fun `new OBIK discovery then all Krakow stock keeps full USER scope for all hints`()=runBlocking {
        for(branch in listOf("075","003","999")) for(hint in listOf(emptyList(),listOf("003"),listOf("075"),listOf("Atlantyda"))) {
            val s=Scenario("obi-pl");s.queryId="7777000"
            s.turn(obiUsers[0],"discovery",obiFirstReply)
            s.queryId="6346951";s.query="6346951";s.modelBranch=branch;s.hints=hint
            val before=s.lookups.size
            val state=s.turn(obiUsers[1],"discovery_then_locations")
            assertEquals(krakowIds(),s.evidence?.checkedIds?.toSet());assertEquals("6346951",s.evidence?.productId)
            assertEquals(2,s.outputs.size);assertEquals("003",s.lookups.drop(before).single().second)
            assertEquals("6346951",s.lookups.drop(before).single().first)
            assertTrue(s.gets.none{it.contains("075")});assertEquals("003",state.products.single().effectiveBranchId)
            assertFalse(state.text.contains("stock 99"))
        }
    }
    @Test fun `direct identifier remains independent after prior different products and failed turn`()=runBlocking {
        val s=Scenario("obi-pl");s.queryId="7777000";s.query=s.queryId
        s.turn("sprawdź produkt 7777000","discovery")
        s.turn("sprawdź produkt 7777000 w market 999","discovery")
        s.queryId="6346951";s.query=s.queryId
        val state=s.turn("6346951","discovery")
        assertEquals("6346951",state.products.single().effectiveProductId);assertEquals("075",state.products.single().effectiveBranchId)
    }
    @Test fun `EAN identifiers never select markets`()=runBlocking {
        for(ean in listOf("12345678","123456789012","5901234123457","12345678901234")) {
            val s=Scenario("obi-pl");s.query=ean;s.modelBranch="003"
            assertEquals("075",s.turn(ean,"discovery").products.single().effectiveBranchId)
            assertEquals("6346951",s.lookups.single().first)
        }
    }
    @Test fun `known three digit market requests stay directory bound`()=runBlocking {
        for((text,id) in listOf("075" to "075","OBI 003" to "003","market 003" to "003","003" to "003")) {
            val s=Scenario("obi-pl");s.modelBranch="999"
            assertEquals(id,s.turn(text,"discovery").products.single().effectiveBranchId)
        }
    }
    @Test fun `unknown explicit market remains rejected`()=runBlocking {
        for(text in listOf("999","market 999","OBI 999")) {
            val s=Scenario("obi-pl");assertTrue(s.turn(text,"discovery").products.isEmpty());assertTrue(s.lookups.isEmpty())
        }
    }
    @Test fun `quantities product specs and measurements cannot grant market authority`()=runBlocking {
        for(text in listOf("203 m","003 x 075 mm","075 kg","075 W","produkt 075","wkładka 30/20G")) {
            val s=Scenario("obi-pl");s.modelBranch="003"
            assertEquals("075",s.turn(text,"discovery").products.single().effectiveBranchId)
        }
    }
    @Test fun `OBI city-only new product uses trusted seed then retains all four market scope`()=runBlocking {
        val s = Scenario("obi-pl")
        s.hints = listOf("075") // Model hint cannot narrow the USER's Kraków scope.
        s.turn("6346951 sprawdź stan w Krakowie", "discovery_then_locations")
        assertEquals(listOf("6346951" to "003"), s.lookups)
        assertEquals(setOf("003", "019", "059", "072"), s.evidence?.checkedIds?.toSet())
        assertEquals(1, s.gets.size)
        assertTrue(s.gets.single().contains("storeIds="))
        assertFalse(s.gets.single().contains("075"))
    }
    @Test fun `city street resolves unique market without model hint authority`()=runBlocking {
        val s=Scenario("obi-pl");s.modelBranch="075"
        assertEquals("003",s.turn("6346951 sprawdź stan w Krakowie na Wielickiej","discovery").products.single().effectiveBranchId)
    }
    @Test fun `unknown explicit locations never erase scope even with broad stock request`()=runBlocking {
        for(text in listOf("6346951 sprawdź stan w Atlantydzie","6346951 sprawdź stan w atlantydzkich marketach","6346951 gdzie jeszcze jest w markecie Atlantydzie","6346951 sprawdź stan w innych atlantydzkich marketach")) {
            val s=Scenario("obi-pl");s.turn(obiUsers[0],"discovery",obiFirstReply);s.hints=listOf("075")
            s.turn(text,"locations");assertTrue("No stock access for $text",s.gets.isEmpty());assertEquals("rejected",s.evidence?.status)
        }
    }
    @Test fun `conflicting city and market descriptions stay closed`()=runBlocking {
        for(text in listOf("6346951 sprawdź stan w market OBI 075 Kraków","6346951 sprawdź stan w krakowskich marketach 999")) {
            val s=Scenario("obi-pl");s.turn(obiUsers[0],"discovery",obiFirstReply)
            s.turn(text,"locations");assertTrue(s.gets.isEmpty());assertEquals("rejected",s.evidence?.status)
        }
    }
    @Test fun `missing trusted new identity cannot stock an old different card`()=runBlocking {
        val s=Scenario("obi-pl");s.queryId="7777000";s.turn(obiUsers[0],"discovery",obiFirstReply)
        s.queryId="6346951";s.turn(obiUsers[1],"locations")
        assertTrue(s.gets.isEmpty());assertEquals("untrusted_product",s.evidence?.reason)
    }
    @Test fun `wrong location product ID still rejected after fresh discovery`()=runBlocking {
        val s=Scenario("obi-pl");s.locationProductId="7777000"
        s.turn(obiUsers[1],"discovery_then_locations")
        assertTrue(s.gets.isEmpty());assertEquals("untrusted_product",s.evidence?.reason)
    }
    @Test fun `wrong provider cannot verify or read stock`()=runBlocking {
        val s=Scenario("obi-pl");s.modelProvider="kwant-pl"
        s.turn(obiUsers[1],"discovery_then_locations")
        assertTrue(s.lookups.isEmpty());assertTrue(s.gets.isEmpty());assertEquals("wrong_provider",s.evidence?.reason)
    }
    @Test fun `partial transport malformed and null inventory preserve checked scope without false zeros`()=runBlocking {
        for(kind in listOf("omitted","transport","null","bad")) {
            val s=Scenario("obi-pl",kind)
            val state=s.turn(obiUsers[1],"discovery_then_locations")
            assertEquals(krakowIds(),s.evidence?.checkedIds?.toSet())
            assertTrue(s.evidence?.locations.orEmpty().none{it.stock==0})
            assertTrue(s.gets.none{it.contains("075")})
            assertFalse(state.text.contains("stock 99"))
        }
    }
    @Test fun `identity only completion cannot claim full citywide stock verification`()=runBlocking {
        val s=Scenario("obi-pl")
        val state=s.turn(obiUsers[1],"discovery")
        assertEquals(1,state.products.size);assertTrue(s.gets.isEmpty());assertFalse(state.text.contains("stock 99"))
        assertTrue(state.text.contains("Nie sprawdziłam"))
    }
    @Test fun `unavailable product verification never creates identity or performs stock reads`()=runBlocking {
        val s=Scenario("obi-pl");s.query="6346951";s.verificationUnavailable=true
        val state=s.turn(obiUsers[1],"discovery_then_locations")
        assertTrue(state.products.isEmpty());assertTrue(s.gets.isEmpty())
        assertEquals(emptyList<String>(),s.evidence?.checkedIds);assertEquals("untrusted_product",s.evidence?.reason)
        assertEquals(AdvisorQueryResultStatus.UNAVAILABLE,(s.outputs.first() as AdvisorToolContinuation.Verified).result.results.single().status)
    }
    @Test fun `city adjective cannot authorize an absent or invalid directory location`()=runBlocking {
        for(d in listOf(obi.filter{it.name!="Kraków"},obi+obi.first(),emptyList())) {
            val s=Scenario("obi-pl",suppliedDirectory=d)
            val state=s.run(obiUsers[1],"discovery_then_locations")
            assertTrue((state as? AdvisorUiState.Success)?.products.orEmpty().isEmpty());assertTrue(s.lookups.isEmpty());assertTrue(s.gets.isEmpty())
        }
    }
    @Test fun `premature inventory then discovery and full inventory fit the existing three call budget`()=runBlocking {
        val s=Scenario("obi-pl");s.query="6346951"
        s.turn(obiUsers[1],"locations_discovery_locations")
        assertEquals(3,s.outputs.size)
        assertEquals("untrusted_product",(s.outputs.first() as AdvisorToolContinuation.Locations).evidence.reason)
        assertEquals("6346951",s.evidence?.productId);assertEquals(krakowIds(),s.evidence?.checkedIds?.toSet())
        assertEquals(listOf("6346951" to "003"),s.lookups);assertEquals(1,s.gets.size)
    }
    @Test fun `wrong discovered identity never authorizes stock or claimed verification of the USER product`()=runBlocking {
        val s=Scenario("obi-pl");s.queryId="7777000";s.query=s.queryId
        val state=s.turn(obiUsers[1],"discovery")
        assertFalse(state.text.contains("Potwierdziłam tożsamość"));assertTrue(s.gets.isEmpty())
        s.turn(obiUsers[1],"discovery_then_locations")
        assertTrue(s.gets.isEmpty());assertEquals("untrusted_product",s.evidence?.reason)
    }
    @Test fun `actual proxy serializer preserves profile seed identity and full scope in v2 and v3`()=runBlocking {
        val s=Scenario("obi-pl");s.query="6346951"
        s.turn(obiUsers[1],"discovery_then_locations")
        for(version in listOf(2,3)) MockWebServer().use { server ->
            val proxy=AdvisorProxyClient(appToken="test-token",baseUrl=server.url("/"))
            for((index,output) in s.outputs.withIndex()) {
                server.enqueue(MockResponse().setHeader("Content-Type","application/json")
                    .setBody("""{"type":"answer","responseId":"final_wire","text":"Synthetic","productRefs":[]}"""))
                val result=if(version==2) proxy.continueTurn("resp_wire_$index","call_wire_$index","075",output)
                    else proxy.continueTurn("resp_wire_$index","call_wire_$index","obi-pl","075",output,version)
                assertTrue(result is AdvisorProxyCallResult.Success)
                val request=server.takeRequest();assertEquals("/v1/agent/continue",request.path)
                val raw=request.body.readUtf8();val body=Json.parseToJsonElement(raw).jsonObject
                assertEquals("resp_wire_$index",body["responseId"]!!.jsonPrimitive.content)
                assertEquals("call_wire_$index",body["callId"]!!.jsonPrimitive.content)
                assertEquals("075",body[if(version==2)"storeNumber" else "branchId"]!!.jsonPrimitive.content)
                val facts=body["result"]!!.jsonObject
                if(index==0) assertEquals("003",facts[if(version==2)"storeNumber" else "branchId"]!!.jsonPrimitive.content)
                else assertEquals(krakowIds(),facts["checkedIds"]!!.jsonArray.map{it.jsonPrimitive.content}.toSet())
                // Synthetic serialized evidence also permits offline replay into the unchanged Worker validator.
                println("INC007_R1_WIRE=$raw")
            }
        }
    }
}
