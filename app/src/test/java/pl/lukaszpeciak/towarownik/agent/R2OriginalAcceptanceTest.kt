package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.diagnostics.*
import pl.lukaszpeciak.towarownik.product.*
import pl.lukaszpeciak.towarownik.product.provider.*

/** Unchanged R7A RC-3 witnesses; synthetic model/provider, no network. Positive remains RED until the original contract is met. */
class R2OriginalAcceptanceTest {
 private val source=AdvisorWebSource("Synthetic documentation","https://manufacturer.example.invalid/manual")
 private fun tool(p:String="kwant-pl",branch:String="205",queries:List<AdvisorToolQuery> = listOf(AdvisorToolQuery("MBN116E",1)))=
  AdvisorProxyCallResult.Success(AdvisorProxyResult.ToolRequest("resp_tool","call_tool",AdvisorToolArguments(branch,queries,p)))
 private fun answer(text:String="Synthetic grounded answer",p:String="kwant-pl",branch:String="205",refs:Boolean=false,web:Boolean=false)=
  AdvisorProxyCallResult.Success(AdvisorProxyResult.Answer("resp_final",text,
   if(refs)listOf(AdvisorProductRef(branch,if(p=="obi-pl")"7000001" else "90001",p)) else emptyList(),
   if(web)listOf(source) else emptyList(),if(web)1 else 0))
 private data class Seen(val state:AdvisorUiState,val executions:List<String>,val lookups:List<String>,val reasons:List<String?>,
  val outputs:List<AdvisorToolContinuation>,val modelCalls:Int,val webCalls:Long)
 private suspend fun run(text:String,script:List<AdvisorProxyCallResult>,p:String="kwant-pl",unavailable:Boolean=false):Seen {
  val profile=if(p=="obi-pl")"075" else "205"
  val branches=if(p=="obi-pl") OBI_STORES.map{ProviderBranch(BranchId(it.storeNumber),it.city,it.address)} else listOf(
   ProviderBranch(BranchId("205"),"Nowy Sącz"),ProviderBranch(BranchId("204"),"Tarnów"),ProviderBranch(BranchId("203"),"Bochnia"))
  var calls=0;var webCalls=0L
  val executions=mutableListOf<String>();val lookups=mutableListOf<String>();val events=mutableListOf<SafeDiagnosticEvent>();val outputs=mutableListOf<AdvisorToolContinuation>()
  val ids=linkedMapOf<String,String>()
  val provider=object:ProductProvider {
   override val providerId=ProviderId(p)
   override fun branches()=ProviderBranchResult.Available(branches)
   override fun search(query:String,maxResults:Int):ProviderSearchResult {
    if(unavailable)return ProviderSearchResult.Unavailable(ProductProviderFailure.NETWORK,"Synthetic unavailable")
    val id=ids.getOrPut(query){(90001+ids.size).toString()}
    return ProviderSearchResult.Candidates(listOf(ProviderProductCandidate(ProductRef(providerId,id),"Synthetic","MBN116E/HAG")))
   }
   override fun lookup(ref:ProductRef,branchId:BranchId):ProviderLookupResult {
    lookups+="${ref.productId}/${branchId.value}"
    return ProviderLookupResult.Found(ProviderProduct(ref,branchId,"Synthetic product",7,grossPrice=BigDecimal.ONE,
     priceScope=ProviderPriceScope.ONLINE,productUrl="https://offline.invalid/product",ean=null,articleNumber="MBN116E/HAG"))
   }
  }
  val local=FindProviderProductsTool(ProductProviderRegistry(listOf(provider)),now={7000L})
  val obi=FindObiProductsTool(searchProducts={ProductSearchResult.Candidates(listOf(ProductSearchCandidate("7000001","Synthetic")))},lookupObik={id,branch->
   lookups+="$id/$branch"
   ProductLookupResult.Found(LocalProduct(id,"Synthetic WC",7,BigDecimal.ONE,"https://offline.invalid/product",null,branch))},now={7000L})
  fun next():AdvisorProxyCallResult { check(calls<script.size){"Unexpected extra model request"};return script[calls++] }
  val controller=AdvisorController(isConfigured={true},startAgent={input,providerId,b->
   assertEquals(text,input);assertEquals(p,providerId);assertEquals(profile,b);next()},
   messageAgent={_,_,_,_->error("Fresh USER turn")},continueAgent={_,_,providerId,b,_,result->
    assertEquals(p,providerId);assertEquals(profile,b);outputs+=result;next()},
   executeObiTool={args->assertEquals("obi-pl",p);executions+=args.storeNumber;obi.execute(args)},
   executeProviderTool={args->assertEquals("kwant-pl",p);executions+=args.storeNumber;local.execute(args)},
   executeLocationsTool={error("No real inventory executor permitted")},branchDirectory={ProviderBranchResult.Available(branches)})
  val state=controller.runTurn(text,null,profile,p,onDiagnosticEvent={events+=it},onOpenAiResponse={_,web->webCalls+=web}){}
  val reasons=events.filter{it.stage==DiagnosticStage.TOOL_RESULT}.map{it.reason}
  println("R7A input=${text.replace('\n',' ')} executions=$executions lookups=$lookups reasons=$reasons calls=$calls state=$state")
  return Seen(state,executions,lookups,reasons,outputs,calls,webCalls)
 }
 @Test fun R2_safe_manufacturer_answer_should_survive_unproductive_wrong_provider_call()=runBlocking {
  val safe="Dokumentacja producenta opisuje serię. Nie sprawdzałam zapasów ani cen."
  val r=run("Znajdź w sieci instrukcję Hagera",listOf(tool(p="obi-pl"),answer(safe,web=true)))
  assertTrue(r.executions.isEmpty());assertEquals("Research-only prose should not be replaced solely by a prior local rejection",safe,(r.state as AdvisorUiState.Success).text)
  assertEquals(listOf(source),r.state.sources)
 }
 @Test fun R2_unverified_inventory_claim_remains_blocked_after_rejection()=runBlocking {
  val unsafe="Produkt jest dostępny: 999 sztuk po 1 zł."
  val r=run("Znajdź w sieci instrukcję Hagera",listOf(tool(p="obi-pl"),answer(unsafe,refs=true)))
  assertTrue(r.executions.isEmpty());assertNotEquals(unsafe,(r.state as AdvisorUiState.Success).text);assertTrue(r.state.products.isEmpty())
 }
}
