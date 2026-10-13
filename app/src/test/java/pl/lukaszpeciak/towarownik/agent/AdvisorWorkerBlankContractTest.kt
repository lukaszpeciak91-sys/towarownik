package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorWorkerBlankContractTest {
    private val blanks = listOf("", " ", "\t\n\r", "\u00a0", "\u2003\u202f", "\u001c", "\u2028\u2029", "\u3000")

    @Test fun blankRequestedBranchIsARealParserFailureIncludingUnicodeAndControls() = runBlocking {
        MockWebServer().use { server ->
            val client = client(server)
            for (blank in blanks + "\u0000\u0085") {
                server.enqueue(response(tool(branch = blank)))
                val result = client.start("synthetic", "kwant-pl", "205")
                assertTrue(result is AdvisorProxyCallResult.Failure)
                assertEquals(AdvisorFailureClass.RESPONSE_PARSE, (result as AdvisorProxyCallResult.Failure).failureClass)
            }
        }
    }

    @Test fun blankCallIdIsRejectedByNativeParserForObiAndProviderTools() = runBlocking {
        MockWebServer().use { server ->
            val client = client(server)
            for (provider in listOf(false, true)) for (blank in blanks) {
                server.enqueue(response(tool(callId = blank, provider = provider)))
                val result = if (provider) client.start("synthetic", "kwant-pl", "205") else client.start("synthetic")
                assertTrue(result is AdvisorProxyCallResult.Failure)
                assertEquals(AdvisorFailureClass.RESPONSE_PARSE, (result as AdvisorProxyCallResult.Failure).failureClass)
            }
        }
    }

    @Test fun blankResponseIdIsRejectedForAnswerAndToolEnvelopes() = runBlocking {
        MockWebServer().use { server ->
            val client = client(server)
            for (blank in blanks) for (body in listOf(tool(responseId = blank), answer(blank))) {
                server.enqueue(response(body))
                val result = client.start("synthetic", "kwant-pl", "205")
                assertTrue(result is AdvisorProxyCallResult.Failure)
                assertEquals(AdvisorFailureClass.RESPONSE_PARSE, (result as AdvisorProxyCallResult.Failure).failureClass)
            }
        }
    }

    @Test fun locationsNormalizeOnlyInAndroidWhileNullAndNonblankIdsRemainValid() = runBlocking {
        MockWebServer().use { server ->
            val client = client(server)
            for ((branch, expected) in listOf(null to null, " \u00a0Nowy Sącz\u00a0 " to "Nowy Sącz", "Nowy\tSącz" to "Nowy Sącz", "\ufeff" to "\ufeff")) {
                server.enqueue(response(tool(branch = branch, responseId = " resp_valid ", callId = " call_valid ")))
                val result = client.start("synthetic", "kwant-pl", "205") as AdvisorProxyCallResult.Success
                val parsed = result.result as AdvisorProxyResult.ToolRequest
                assertEquals(expected, parsed.arguments.requestedBranch)
                assertEquals(" resp_valid ", parsed.responseId)
                assertEquals(" call_valid ", parsed.callId)
                assertEquals(listOf(3, 2), parsed.arguments.queries.map { it.limit })
            }
            for (id in listOf("\ufeff", "\u0085", "\u200b", "\u0000")) {
                server.enqueue(response(tool(responseId = id, callId = id)))
                val result = client.start("synthetic", "kwant-pl", "205") as AdvisorProxyCallResult.Success
                val parsed = result.result as AdvisorProxyResult.ToolRequest
                assertEquals(id, parsed.responseId)
                assertEquals(id, parsed.callId)
            }
        }
    }

    @Test fun continuationKeepsOpaqueIdsAndTraceExactly() = runBlocking {
        MockWebServer().use { server ->
            val client = client(server)
            val id = " \u00a0resp_valid\u00a0 "
            val call = " \u00a0call_valid\u00a0 "
            val trace = "00000000-1111-4111-8111-000000000001"
            server.enqueue(response(tool(responseId = id, callId = call)).setHeader(ADVISOR_TRACE_HEADER, trace))
            val initial = client.start("synthetic", "kwant-pl", "205") as AdvisorProxyCallResult.Success
            server.takeRequest()
            server.enqueue(response(answer("resp_final")).setHeader(ADVISOR_TRACE_HEADER, trace))
            val parsed = initial.result as AdvisorProxyResult.ToolRequest
            val result = client.continueTurn(
                responseId = parsed.responseId, callId = parsed.callId,
                providerId = "kwant-pl", branchId = "205", protocolVersion = ADVISOR_PROTOCOL_VERSION,
                continuation = AdvisorToolContinuation.LocalToolLimitReached(
                    queries = listOf(AdvisorToolQuery("synthetic", 1)), storeNumber = "205", providerId = "kwant-pl",
                ), traceId = initial.traceId,
            )
            assertTrue(result is AdvisorProxyCallResult.Success)
            val request = server.takeRequest()
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(id, body["responseId"]?.jsonPrimitive?.content)
            assertEquals(call, body["callId"]?.jsonPrimitive?.content)
            assertEquals(trace, request.getHeader(ADVISOR_TRACE_HEADER))
        }
    }

    private fun client(server: MockWebServer) = AdvisorProxyClient(appToken = "synthetic-app", baseUrl = server.url("/"))
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun answer(id: String) = buildJsonObject {
        put("type", "answer"); put("responseId", id); put("text", "synthetic final")
        put("productRefs", buildJsonArray {}); put("webSearchCalls", 0)
    }.toString()
    private fun tool(branch: String? = null, callId: String = "call_valid", responseId: String = "resp_valid", provider: Boolean = true) = buildJsonObject {
        put("type", "tool_request"); put("responseId", responseId); put("webSearchCalls", 0)
        put("tool", buildJsonObject {
            put("name", if (provider) FIND_PRODUCTS else FIND_OBI_PRODUCTS); put("callId", callId)
            put("arguments", buildJsonObject {
                if (provider) { put("providerId", "kwant-pl"); put("branchId", "205"); put("requestedBranch", branch?.let(::JsonPrimitive) ?: JsonNull) }
                else put("storeNumber", "075")
                put("queries", buildJsonArray {
                    add(buildJsonObject { put("query", "synthetic A"); put("limit", 3) })
                    add(buildJsonObject { put("query", "synthetic B"); put("limit", 2) })
                })
            })
        })
    }.toString()
}
