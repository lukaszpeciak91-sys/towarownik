package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Real Android parser; loopback only. Parsed text is not an authenticated claim. */
class ResearchEvidenceWireContractTest {
    private val safe = "Dokumentacja producenta opisuje serię. Nie sprawdzałam zapasów ani cen."
    private val mixed = "$safe Produkt jest dostępny: 999 sztuk po 1 zł."
    private val url = "https://manufacturer.example.invalid/manual"
    private fun envelope(text: String, fullCitation: Boolean = false, title: String = "Synthetic documentation", extra: Pair<String, JsonElement>? = null): String = buildJsonObject {
        put("type", "answer"); put("responseId", "resp_evidence"); put("text", text)
        put("productRefs", JsonArray(emptyList())); put("webSearchCalls", 1)
        put("sources", buildJsonArray { add(buildJsonObject {
            put("title", title); put("url", url); put("startIndex", 0); put("endIndex", if (fullCitation) text.length else 10)
        }) })
        extra?.let { put(it.first, it.second) }
    }.toString()
    private fun parse(body: String): AdvisorProxyCallResult = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
            AdvisorProxyClient(appToken = "synthetic-test-token", baseUrl = server.url("/")).start("Synthetic research request")
        }
    }
    private fun answer(body: String) = (parse(body) as AdvisorProxyCallResult.Success).result as AdvisorProxyResult.Answer

    @Test fun safeAndMixedProseArriveWithIdenticalNonTextEvidence() {
        val a = answer(envelope(safe))
        val b = answer(envelope(mixed))
        assertEquals(a.copy(text = ""), b.copy(text = ""))
        assertEquals(mixed, b.text)
        assertTrue(b.productRefs.isEmpty())
        assertEquals(url, b.sources.single().url)
    }
    @Test fun completeCitationCoverageIsNotSemanticAttestation() {
        val a = answer(envelope(mixed, fullCitation = true))
        assertEquals(0, a.sources.single().startIndex)
        assertEquals(mixed.length, a.sources.single().endIndex)
        assertEquals(mixed, a.text)
    }
    @Test fun modelSelectedTrustLabelsAreProtocolErrorsRatherThanAuthority() {
        for (extra in listOf("answerKind" to JsonPrimitive("technical"), "safe" to JsonPrimitive(true), "approvedResearchFragments" to JsonArray(listOf(JsonPrimitive("invented-proof"))))) {
            val result = parse(envelope(mixed, extra = extra))
            assertTrue(result is AdvisorProxyCallResult.Failure)
            assertEquals(AdvisorProxyFailureKind.PROTOCOL, (result as AdvisorProxyCallResult.Failure).kind)
        }
    }
    @Test fun citationTitleMayContainUnverifiedInventorySoItCannotBeRetainedAsAdvice() {
        val title = "KWANT 205: 999 sztuk po 1 zł"
        assertEquals(title, answer(envelope(safe, title = title)).sources.single().title)
    }
}
