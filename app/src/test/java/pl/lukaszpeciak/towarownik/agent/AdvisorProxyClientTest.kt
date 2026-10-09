package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvisorProxyClientTest {
    @Test
    fun `start sends only message with bearer token and json content type`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(answerResponse())
            val client = client(server, FAKE_TOKEN)

            val result = client.start("potrzebuję kleju")

            assertTrue(result is AdvisorProxyCallResult.Success)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/agent/start", request.path)
            assertEquals("Bearer $FAKE_TOKEN", request.getHeader("Authorization"))
            assertTrue(
                request.getHeader("Content-Type")
                    ?.startsWith("application/json") == true,
            )

            val raw = request.body.readUtf8()
            val body = Json.parseToJsonElement(raw).jsonObject
            assertEquals(
                setOf("protocolVersion", "message", "storeNumber"),
                body.keys,
            )
            assertEquals(
                OBI_ADVISOR_PROTOCOL_VERSION,
                body["protocolVersion"]?.jsonPrimitive?.intOrNull,
            )
            assertEquals(
                "potrzebuję kleju",
                body["message"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "075",
                body["storeNumber"]?.jsonPrimitive?.content,
            )
            assertFalse(raw.contains(FAKE_TOKEN))
        }
    }

    @Test
    fun `message sends previous response id and only new user message`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(answerResponse())
            val client = client(server, FAKE_TOKEN)

            val result = client.message(
                previousResponseId = "resp_previous",
                message = "A coś tańszego?",
            )

            assertTrue(result is AdvisorProxyCallResult.Success)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/v1/agent/message", request.path)
            assertEquals("Bearer $FAKE_TOKEN", request.getHeader("Authorization"))

            val raw = request.body.readUtf8()
            val body = Json.parseToJsonElement(raw).jsonObject
            assertEquals(
                setOf(
                    "protocolVersion",
                    "previousResponseId",
                    "message",
                    "storeNumber",
                ),
                body.keys,
            )
            assertEquals(
                OBI_ADVISOR_PROTOCOL_VERSION,
                body["protocolVersion"]?.jsonPrimitive?.intOrNull,
            )
            assertEquals(
                "resp_previous",
                body["previousResponseId"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "A coś tańszego?",
                body["message"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "075",
                body["storeNumber"]?.jsonPrimitive?.content,
            )
            assertFalse(raw.contains(FAKE_TOKEN))
        }
    }

    @Test
    fun `provider start sends protocol v3 provider and branch`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(answerResponse())
            val client = client(server, FAKE_TOKEN)

            val result = client.start(
                message = "sprawdź MBN116E",
                providerId = "kwant-pl",
                branchId = "205",
            )

            assertTrue(result is AdvisorProxyCallResult.Success)
            val request = server.takeRequest()
            val body = Json.parseToJsonElement(
                request.body.readUtf8(),
            ).jsonObject
            assertEquals(
                setOf(
                    "protocolVersion",
                    "message",
                    "providerId",
                    "branchId",
                ),
                body.keys,
            )
            assertEquals(
                ADVISOR_PROTOCOL_VERSION,
                body["protocolVersion"]?.jsonPrimitive?.intOrNull,
            )
            assertEquals(
                "kwant-pl",
                body["providerId"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "205",
                body["branchId"]?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun `provider response parses KWANT tool and product refs`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "type":"tool_request",
                          "responseId":"resp_tool",
                          "tool":{
                            "name":"find_products",
                            "callId":"call_1",
                            "arguments":{
                              "providerId":"kwant-pl",
                              "branchId":"205",
                              "requestedBranch":null,
                              "queries":[{"query":"MBN116E","limit":1}]
                            }
                          },
                          "webSearchCalls":0
                        }
                        """.trimIndent(),
                    ),
            )
            val client = client(server, FAKE_TOKEN)

            val tool = client.start(
                message = "sprawdź MBN116E",
                providerId = "kwant-pl",
                branchId = "205",
            ) as AdvisorProxyCallResult.Success

            val request = tool.result as AdvisorProxyResult.ToolRequest
            assertEquals("kwant-pl", request.arguments.providerId)
            assertEquals("205", request.arguments.branchId)

            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "type":"answer",
                          "responseId":"resp_final",
                          "text":"Mam produkt.",
                          "productRefs":[
                            {
                              "providerId":"kwant-pl",
                              "branchId":"205",
                              "productId":"580"
                            }
                          ],
                          "webSearchCalls":0
                        }
                        """.trimIndent(),
                    ),
            )

            val result = client.continueTurn(
                responseId = "resp_tool",
                callId = "call_1",
                providerId = "kwant-pl",
                branchId = "205",
                continuation = AdvisorToolContinuation.Verified(
                    AdvisorVerifiedToolResult(
                        providerId = "kwant-pl",
                        storeNumber = "205",
                        results = listOf(
                            AdvisorVerifiedQueryResult(
                                query = "MBN116E",
                                status =
                                    AdvisorQueryResultStatus.VERIFIED,
                                products = listOf(
                                    AdvisorVerifiedProduct(
                                        obik = "580",
                                        productId = "580",
                                        articleNumber = "MBN116E/HAG",
                                        name = "Wyłącznik",
                                        stock = 140,
                                        centralStock = 5918,
                                        price = BigDecimal("14.55"),
                                        priceScope = "online",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ) as AdvisorProxyCallResult.Success

            val answer = result.result as AdvisorProxyResult.Answer
            assertEquals(
                listOf(
                    AdvisorProductRef(
                        storeNumber = "205",
                        obik = "580",
                        providerId = "kwant-pl",
                    ),
                ),
                answer.productRefs,
            )

            val startBody = Json.parseToJsonElement(
                server.takeRequest().body.readUtf8(),
            ).jsonObject
            assertEquals(
                ADVISOR_PROTOCOL_VERSION,
                startBody["protocolVersion"]?.jsonPrimitive?.intOrNull,
            )

            val continueBody = Json.parseToJsonElement(
                server.takeRequest().body.readUtf8(),
            ).jsonObject
            assertEquals(
                ADVISOR_PROTOCOL_VERSION,
                continueBody["protocolVersion"]?.jsonPrimitive?.intOrNull,
            )
            assertEquals(
                FIND_PRODUCTS,
                continueBody["tool"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "kwant-pl",
                continueBody["providerId"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "205",
                continueBody["branchId"]?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun `provider v4 rejected and limit continuations keep provider contract`() = runBlocking {
        MockWebServer().use { server ->
            repeat(2) {
                server.enqueue(answerResponse())
            }
            val client = client(server, FAKE_TOKEN)

            val rejected = client.continueTurn(
                responseId = "resp_rejected",
                callId = "call_rejected",
                providerId = "obi-pl",
                branchId = "075",
                continuation = AdvisorToolContinuation.RejectedStore(
                    queries = listOf(AdvisorToolQuery("miska", 1)),
                    storeNumber = "075",
                    providerId = "obi-pl",
                ),
                protocolVersion = MULTIMODAL_ADVISOR_PROTOCOL_VERSION,
            )
            val limited = client.continueTurn(
                responseId = "resp_limited",
                callId = "call_limited",
                providerId = "obi-pl",
                branchId = "075",
                continuation = AdvisorToolContinuation.LocalToolLimitReached(
                    queries = listOf(AdvisorToolQuery("miska", 1)),
                    storeNumber = "075",
                    providerId = "obi-pl",
                ),
                protocolVersion = MULTIMODAL_ADVISOR_PROTOCOL_VERSION,
            )

            assertTrue(rejected is AdvisorProxyCallResult.Success)
            assertTrue(limited is AdvisorProxyCallResult.Success)

            val rejectedBody = Json.parseToJsonElement(
                server.takeRequest().body.readUtf8(),
            ).jsonObject
            assertEquals(
                MULTIMODAL_ADVISOR_PROTOCOL_VERSION,
                rejectedBody["protocolVersion"]?.jsonPrimitive?.intOrNull,
            )
            assertEquals(
                FIND_PRODUCTS,
                rejectedBody["tool"]?.jsonPrimitive?.content,
            )
            val rejectedResult = rejectedBody["result"] as JsonObject
            assertEquals(
                "branch_not_authorized",
                rejectedResult["rejection"]?.jsonPrimitive?.content,
            )

            val limitedBody = Json.parseToJsonElement(
                server.takeRequest().body.readUtf8(),
            ).jsonObject
            assertEquals(
                MULTIMODAL_ADVISOR_PROTOCOL_VERSION,
                limitedBody["protocolVersion"]?.jsonPrimitive?.intOrNull,
            )
            assertEquals(
                FIND_PRODUCTS,
                limitedBody["tool"]?.jsonPrimitive?.content,
            )
            val limitedResult = limitedBody["result"] as JsonObject
            assertEquals(
                "local_tool_limit_reached",
                limitedResult["rejection"]?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun `answer response parses to normalized answer`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(answerResponse())
            val result = client(server, FAKE_TOKEN).start("test")

            assertEquals(
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.Answer(
                        responseId = "resp_1",
                        text = "Synthetic answer",
                        productRefs = emptyList(),
                    ),
                ),
                result,
            )
        }
    }

    @Test
    fun `answer response parses selected product obiks and removes duplicates`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "type":"answer",
                          "responseId":"resp_cards",
                          "text":"Synthetic answer",
                          "productRefs":[
                            {"storeNumber":"074","obik":"1234567"},
                            {"storeNumber":"074","obik":"1234567"},
                            {"storeNumber":"075","obik":"7654321"}
                          ]
                        }
                        """.trimIndent(),
                    ),
            )

            val result = client(server, FAKE_TOKEN).start("test")

            assertEquals(
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.Answer(
                        responseId = "resp_cards",
                        text = "Synthetic answer",
                        productRefs = listOf(
                            AdvisorProductRef("074", "1234567"),
                            AdvisorProductRef("075", "7654321"),
                        ),
                    ),
                ),
                result,
            )
        }
    }

    @Test
    fun `answer cannot inject model supplied product facts`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "type":"answer",
                      "responseId":"resp_bad",
                      "text":"Synthetic",
                      "productRefs":[{"storeNumber":"075","obik":"1234567"}],
                      "price":0.01
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.PROTOCOL,
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `more than five selected product obiks fails closed`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "type":"answer",
                      "responseId":"resp_bad",
                      "text":"Synthetic",
                      "productRefs":[
                        {"storeNumber":"075","obik":"1000001"},
                        {"storeNumber":"075","obik":"1000002"},
                        {"storeNumber":"075","obik":"1000003"},
                        {"storeNumber":"075","obik":"1000004"},
                        {"storeNumber":"075","obik":"1000005"},
                        {"storeNumber":"075","obik":"1000006"}
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.PROTOCOL,
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `tool request parses to one known local tool`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody(
                        """
                        {
                          "type":"tool_request",
                          "responseId":"resp_2",
                          "tool":{
                            "name":"find_obi_products",
                            "callId":"call_1",
                            "arguments":{"storeNumber":"074","queries":[{"query":"klej montażowy","limit":5}]}
                          }
                        }
                        """.trimIndent(),
                    ),
            )

            val result = client(server, FAKE_TOKEN).start("test")

            assertEquals(
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.ToolRequest(
                        responseId = "resp_2",
                        callId = "call_1",
                        arguments = AdvisorToolArguments(
                            query = "klej montażowy",
                            storeNumber = "074",
                            limit = 5,
                        ),
                    ),
                ),
                result,
            )
        }
    }

    @Test
    fun `same OBIK in two stores remains two distinct selected refs`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "type":"answer",
                      "responseId":"resp_compare",
                      "text":"Compare",
                      "productRefs":[
                        {"storeNumber":"074","obik":"3496072"},
                        {"storeNumber":"075","obik":"3496072"}
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            val result = client(server, FAKE_TOKEN).start(
                "Porównaj",
                storeNumber = "075",
            )

            assertEquals(
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.Answer(
                        responseId = "resp_compare",
                        text = "Compare",
                        productRefs = listOf(
                            AdvisorProductRef("074", "3496072"),
                            AdvisorProductRef("075", "3496072"),
                        ),
                    ),
                ),
                result,
            )
        }
    }

    @Test
    fun `answer sources parse as bounded clickable https metadata`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "type":"answer",
                      "responseId":"resp_sources",
                      "text":"Web answer",
                      "productRefs":[],
                      "webSearchCalls":1,
                      "sources":[
                        {
                          "title":"Manufacturer manual",
                          "url":"https://manufacturer.example/manual",
                          "startIndex":0,
                          "endIndex":3
                        },
                        {
                          "title":"Manufacturer manual duplicate",
                          "url":"https://manufacturer.example/manual",
                          "startIndex":0,
                          "endIndex":3
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.Answer(
                        responseId = "resp_sources",
                        text = "Web answer",
                        productRefs = emptyList(),
                        sources = listOf(
                            AdvisorWebSource(
                                title = "Manufacturer manual",
                                url = "https://manufacturer.example/manual",
                                startIndex = 0,
                                endIndex = 3,
                            ),
                        ),
                        webSearchCalls = 1,
                    ),
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `unsafe source URL makes normalized proxy contract fail closed`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "type":"answer",
                      "responseId":"resp_bad_source",
                      "text":"Answer",
                      "productRefs":[],
                      "sources":[
                        {
                          "title":"Unsafe",
                          "url":"http://example.com/source",
                          "startIndex":null,
                          "endIndex":null
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.PROTOCOL,
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `tool request rejects unexpected source metadata`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "type":"tool_request",
                      "responseId":"resp_tool_sources",
                      "tool":{
                        "name":"find_obi_products",
                        "callId":"call_tool_sources",
                        "arguments":{
                          "storeNumber":"075",
                          "queries":[{"query":"klej","limit":1}]
                        }
                      },
                      "sources":[
                        {
                          "title":"Unexpected",
                          "url":"https://example.com/"
                        }
                      ]
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.PROTOCOL,
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `usage envelope parses independently from answer content`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "type":"answer",
                      "responseId":"resp_usage",
                      "text":"Measured",
                      "productRefs":[],
                      "webSearchCalls":1,
                      "usage":{
                        "model":"gpt-6-luna",
                        "requestType":"START",
                        "inputTokens":1000,
                        "cachedInputTokens":400,
                        "cacheWriteTokens":100,
                        "outputTokens":100,
                        "reasoningTokens":50,
                        "totalTokens":1100,
                        "estimatedCostUsd":0.0101165,
                        "pricingVersion":"openai-gpt-6-luna-2026-09-28-web-v1"
                      }
                    }
                    """.trimIndent(),
                ),
            )

            val result = client(server, FAKE_TOKEN).start("test")

            assertEquals(
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.Answer(
                        responseId = "resp_usage",
                        text = "Measured",
                        productRefs = emptyList(),
                        usage = AdvisorUsage(
                            model = "gpt-6-luna",
                            requestType = AdvisorRequestType.START,
                            inputTokens = 1_000,
                            cachedInputTokens = 400,
                            cacheWriteTokens = 100,
                            outputTokens = 100,
                            reasoningTokens = 50,
                            totalTokens = 1_100,
                            estimatedCostUsd =
                                BigDecimal("0.0101165"),
                            pricingVersion =
                                "openai-gpt-6-luna-2026-09-28-web-v1",
                        ),
                        webSearchCalls = 1,
                    ),
                ),
                result,
            )
        }
    }

    @Test
    fun `malformed telemetry is ignored without losing answer`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "type":"answer",
                      "responseId":"resp_usage_bad",
                      "text":"Still usable",
                      "productRefs":[],
                      "webSearchCalls":1,
                      "usage":{
                        "model":"gpt-6-luna",
                        "requestType":"START",
                        "inputTokens":"bad",
                        "cachedInputTokens":0,
                        "cacheWriteTokens":0,
                        "outputTokens":1,
                        "reasoningTokens":0,
                        "totalTokens":1,
                        "estimatedCostUsd":0.0000012,
                        "pricingVersion":"openai-gpt-6-luna-2026-09-28-web-v1"
                      }
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.Answer(
                        responseId = "resp_usage_bad",
                        text = "Still usable",
                        productRefs = emptyList(),
                        webSearchCalls = 1,
                        usage = null,
                    ),
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `cached plus cache-write overflow makes telemetry fail soft`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "type":"answer",
                      "responseId":"resp_bad_cache_sum",
                      "text":"Still usable",
                      "productRefs":[],
                      "usage":{
                        "model":"gpt-6-luna",
                        "requestType":"START",
                        "inputTokens":100,
                        "cachedInputTokens":60,
                        "cacheWriteTokens":50,
                        "outputTokens":10,
                        "reasoningTokens":0,
                        "totalTokens":110,
                        "estimatedCostUsd":null,
                        "pricingVersion":null
                      }
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.Answer(
                        responseId = "resp_bad_cache_sum",
                        text = "Still usable",
                        productRefs = emptyList(),
                        usage = null,
                    ),
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `missing usage preserves independent web search count`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "type":"answer",
                      "responseId":"resp_missing_usage_search",
                      "text":"Still useful",
                      "productRefs":[],
                      "webSearchCalls":1
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(
                AdvisorProxyCallResult.Success(
                    AdvisorProxyResult.Answer(
                        responseId = "resp_missing_usage_search",
                        text = "Still useful",
                        productRefs = emptyList(),
                        webSearchCalls = 1,
                        usage = null,
                    ),
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `local tool limit continuation serializes bounded machine result`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(answerResponse())

            val result = client(server, FAKE_TOKEN).continueTurn(
                responseId = "resp_third",
                callId = "call_third",
                storeNumber = "075",
                continuation =
                    AdvisorToolContinuation.LocalToolLimitReached(
                        query = "finishing tools",
                        storeNumber = "075",
                    ),
            )

            assertTrue(result is AdvisorProxyCallResult.Success)
            val requestBody = Json.parseToJsonElement(
                server.takeRequest().body.readUtf8(),
            ).jsonObject
            val continuationResult =
                requestBody["result"] as JsonObject
            assertEquals(
                "local_tool_limit_reached",
                continuationResult["rejection"]
                    ?.jsonPrimitive
                    ?.content,
            )
            val queries =
                continuationResult["queries"] as
                    kotlinx.serialization.json.JsonArray
            val query = queries.single() as JsonObject
            assertEquals(
                "finishing tools",
                query["query"]?.jsonPrimitive?.content,
            )
            assertEquals(
                1,
                query["limit"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    @Test
    fun `malformed store fails locally before request`() = runBlocking {
        MockWebServer().use { server ->
            val result = client(server, FAKE_TOKEN).start(
                "test",
                storeNumber = "74",
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.PROTOCOL,
                ),
                result,
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `unknown response type fails closed`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"type":"other","responseId":"resp_1"}""",
                ),
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.PROTOCOL,
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `malformed tool schema fails closed`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "type":"tool_request",
                      "responseId":"resp_2",
                      "tool":{
                        "name":"find_obi_products",
                        "callId":"call_1",
                        "arguments":{"storeNumber":"075","queries":[{"query":"klej","limit":6}]}
                      }
                    }
                    """.trimIndent(),
                ),
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.PROTOCOL,
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `401 captures safe authentication diagnostic`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(401)
                    .setBody("""{"error":"unauthorized"}"""),
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    kind = AdvisorProxyFailureKind.AUTHENTICATION,
                    httpStatus = 401,
                    proxyErrorCode = "unauthorized",
                    endpoint = "start",
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `503 captures safe service diagnostic`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setBody("""{"error":"server_not_configured"}"""),
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    kind = AdvisorProxyFailureKind.SERVICE,
                    httpStatus = 503,
                    proxyErrorCode = "server_not_configured",
                    endpoint = "start",
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `unsafe proxy failure body is not surfaced`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(502)
                    .setBody("""{"error":"UPSTREAM secret detail"}"""),
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    kind = AdvisorProxyFailureKind.SERVICE,
                    httpStatus = 502,
                    proxyErrorCode = null,
                    endpoint = "start",
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `lowercase unknown proxy error code is not surfaced`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(502)
                    .setBody("""{"error":"secret_token_abc123"}"""),
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    kind = AdvisorProxyFailureKind.SERVICE,
                    httpStatus = 502,
                    proxyErrorCode = null,
                    endpoint = "start",
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `400 proxy contract failure maps to protocol with diagnostic`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setBody("""{"error":"unsupported_protocol_version"}"""),
            )

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    kind = AdvisorProxyFailureKind.PROTOCOL,
                    httpStatus = 400,
                    proxyErrorCode = "unsupported_protocol_version",
                    endpoint = "start",
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `missing token fails locally before any request`() = runBlocking {
        MockWebServer().use { server ->
            val result = client(server, "").start("test")

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.NOT_CONFIGURED,
                ),
                result,
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `continue sends compact verified result and received ids`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(answerResponse())
            val verified = AdvisorVerifiedToolResult(
                query = "klej",
                storeNumber = "074",
                products = listOf(
                    AdvisorVerifiedProduct(
                        obik = "1234567",
                        name = "Synthetic product",
                        brand = "Synthetic Brand",
                        shortDescription =
                            "Verified compact description.",
                        technicalFacts = listOf(
                            AdvisorTechnicalFact(
                                label = "Moc",
                                value = "600 W",
                            ),
                        ),
                        stock = 0,
                        price = BigDecimal("14.99"),
                    ),
                ),
            )

            val result = client(server, FAKE_TOKEN).continueTurn(
                responseId = "resp_previous",
                callId = "call_previous",
                storeNumber = "075",
                continuation = AdvisorToolContinuation.Verified(verified),
            )

            assertTrue(result is AdvisorProxyCallResult.Success)
            val request = server.takeRequest()
            assertEquals("/v1/agent/continue", request.path)
            val raw = request.body.readUtf8()
            val body = Json.parseToJsonElement(raw).jsonObject
            assertEquals(
                setOf(
                    "protocolVersion",
                    "responseId",
                    "callId",
                    "storeNumber",
                    "tool",
                    "result",
                ),
                body.keys,
            )
            assertEquals(
                OBI_ADVISOR_PROTOCOL_VERSION,
                body["protocolVersion"]?.jsonPrimitive?.intOrNull,
            )
            assertEquals("resp_previous", body["responseId"]?.jsonPrimitive?.content)
            assertEquals("call_previous", body["callId"]?.jsonPrimitive?.content)
            assertEquals("075", body["storeNumber"]?.jsonPrimitive?.content)
            assertEquals(FIND_OBI_PRODUCTS, body["tool"]?.jsonPrimitive?.content)

            val resultBody = body["result"] as JsonObject
            assertEquals(
                setOf("storeNumber", "results"),
                resultBody.keys,
            )
            assertEquals(
                "074",
                resultBody["storeNumber"]?.jsonPrimitive?.content,
            )
            val groups =
                resultBody["results"] as
                    kotlinx.serialization.json.JsonArray
            val group = groups.single() as JsonObject
            assertEquals(
                setOf("query", "status", "products"),
                group.keys,
            )
            assertEquals(
                "verified",
                group["status"]?.jsonPrimitive?.content,
            )
            val products =
                group["products"] as
                    kotlinx.serialization.json.JsonArray
            val product = products.single() as JsonObject
            assertEquals(
                setOf(
                    "obik",
                    "name",
                    "brand",
                    "shortDescription",
                    "technicalFacts",
                    "stock",
                    "price",
                ),
                product.keys,
            )
            assertFalse(raw.contains("html", ignoreCase = true))
            assertFalse(raw.contains("cookie", ignoreCase = true))
            assertEquals(
                "Synthetic Brand",
                product["brand"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "Verified compact description.",
                product["shortDescription"]
                    ?.jsonPrimitive
                    ?.content,
            )
            assertFalse(raw.contains("productUrl", ignoreCase = true))
            assertFalse(raw.contains("verifiedAt", ignoreCase = true))
            assertFalse(raw.contains("articleEanEcms", ignoreCase = true))
            assertFalse(raw.contains("__NUXT_DATA__", ignoreCase = true))
            assertFalse(raw.contains(FAKE_TOKEN))
        }
    }

    @Test
    fun `worst case grouped three byte continuation trims optional facts but keeps every product identity`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(answerResponse())
            val threeByte = "漢"
            val products = (1..5).map { index ->
                AdvisorVerifiedProduct(
                    obik = (1_000_000 + index).toString(),
                    name = threeByte.repeat(200),
                    stock = index,
                    price = BigDecimal(index.toString() + ".99"),
                    brand = threeByte.repeat(80),
                    shortDescription = threeByte.repeat(220),
                    technicalFacts = (1..6).map {
                        AdvisorTechnicalFact(
                            label = threeByte.repeat(60),
                            value = threeByte.repeat(100),
                        )
                    },
                )
            }
            val grouped = products.mapIndexed { index, product ->
                AdvisorVerifiedQueryResult(
                    query = threeByte.repeat(200),
                    status = AdvisorQueryResultStatus.VERIFIED,
                    products = listOf(product),
                )
            }

            val result = client(server, FAKE_TOKEN).continueTurn(
                responseId = threeByte.repeat(256),
                callId = threeByte.repeat(256),
                storeNumber = "075",
                continuation = AdvisorToolContinuation.Verified(
                    AdvisorVerifiedToolResult(
                        storeNumber = "075",
                        results = grouped,
                    ),
                ),
            )

            assertTrue(result is AdvisorProxyCallResult.Success)
            val request = server.takeRequest()
            assertTrue(request.body.size < 16L * 1024L)

            val body = Json.parseToJsonElement(
                request.body.readUtf8(),
            ).jsonObject
            val resultBody = body["result"] as JsonObject
            val groups =
                resultBody["results"] as
                    kotlinx.serialization.json.JsonArray
            assertEquals(5, groups.size)
            val serializedProducts = groups.flatMap { element ->
                val group = element as JsonObject
                assertEquals(
                    "verified",
                    group["status"]?.jsonPrimitive?.content,
                )
                assertEquals(
                    threeByte.repeat(200),
                    group["query"]?.jsonPrimitive?.content,
                )
                (group["products"] as
                    kotlinx.serialization.json.JsonArray).toList()
            }

            assertEquals(5, serializedProducts.size)
            serializedProducts.forEachIndexed { index, element ->
                val product = element as JsonObject
                assertEquals(
                    (1_000_001 + index).toString(),
                    product["obik"]?.jsonPrimitive?.content,
                )
                assertEquals(
                    threeByte.repeat(200),
                    product["name"]?.jsonPrimitive?.content,
                )
                assertEquals(
                    index + 1,
                    product["stock"]?.jsonPrimitive?.content?.toIntOrNull(),
                )
                assertEquals(
                    (index + 1).toString() + ".99",
                    product["price"]?.jsonPrimitive?.content,
                )
            }

            val retainedFactCount = serializedProducts.sumOf { element ->
                ((element as JsonObject)["technicalFacts"] as
                    kotlinx.serialization.json.JsonArray).size
            }
            assertTrue(retainedFactCount < 30)
        }
    }

    @Test
    fun `start captures valid Worker trace header`() = runBlocking {
        MockWebServer().use { server ->
            val trace = "11111111-1111-4111-8111-111111111111"
            server.enqueue(
                answerResponse().setHeader(ADVISOR_TRACE_HEADER, trace),
            )

            val result = client(server, FAKE_TOKEN)
                .start("trace me") as AdvisorProxyCallResult.Success

            assertEquals(trace, result.traceId)
            assertEquals(null, server.takeRequest().getHeader(ADVISOR_TRACE_HEADER))
        }
    }

    @Test
    fun `message captures returned trace without sending a previous turn trace`() = runBlocking {
        MockWebServer().use { server ->
            val trace = "22222222-2222-4222-8222-222222222222"
            server.enqueue(
                answerResponse().setHeader(
                    "x-taksula-trace-id",
                    trace,
                ),
            )

            val result = client(server, FAKE_TOKEN).message(
                previousResponseId = "resp_previous",
                message = "next turn",
            ) as AdvisorProxyCallResult.Success

            assertEquals(trace, result.traceId)
            assertEquals(null, server.takeRequest().getHeader(ADVISOR_TRACE_HEADER))
        }
    }

    @Test
    fun `missing or invalid trace header never fails a valid response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(answerResponse())
            server.enqueue(
                answerResponse().setHeader(
                    ADVISOR_TRACE_HEADER,
                    "not-a-uuid",
                ),
            )
            val client = client(server, FAKE_TOKEN)

            val missing = client.start("first")
            val invalid = client.start("second")

            assertTrue(missing is AdvisorProxyCallResult.Success)
            assertEquals(
                null,
                (missing as AdvisorProxyCallResult.Success).traceId,
            )
            assertTrue(invalid is AdvisorProxyCallResult.Success)
            assertEquals(
                null,
                (invalid as AdvisorProxyCallResult.Success).traceId,
            )
        }
    }

    @Test
    fun `continue sends supplied trace and accepts returned authoritative trace`() = runBlocking {
        MockWebServer().use { server ->
            val sent = "33333333-3333-4333-8333-333333333333"
            val returned = "44444444-4444-4444-8444-444444444444"
            server.enqueue(
                answerResponse().setHeader(
                    ADVISOR_TRACE_HEADER,
                    returned,
                ),
            )
            val client = client(server, FAKE_TOKEN)

            val result = client.continueTurn(
                responseId = "resp_previous",
                callId = "call_previous",
                storeNumber = "075",
                continuation = AdvisorToolContinuation.LocalToolLimitReached(
                    queries = listOf(AdvisorToolQuery("test", 1)),
                    storeNumber = "075",
                ),
                traceId = sent,
            ) as AdvisorProxyCallResult.Success

            assertEquals(
                sent,
                server.takeRequest().getHeader(ADVISOR_TRACE_HEADER),
            )
            assertEquals(returned, result.traceId)
        }
    }

    @Test
    fun `http failure keeps valid Worker trace while network failure has none`() = runBlocking {
        MockWebServer().use { server ->
            val trace = "55555555-5555-4555-8555-555555555555"
            server.enqueue(
                MockResponse()
                    .setResponseCode(502)
                    .setHeader("Content-Type", "application/json")
                    .setHeader(ADVISOR_TRACE_HEADER, trace)
                    .setBody("""{"error":"upstream_failure"}"""),
            )
            val client = client(server, FAKE_TOKEN)

            val http = client.start("failure")
                as AdvisorProxyCallResult.Failure
            assertEquals(502, http.httpStatus)
            assertEquals("upstream_failure", http.proxyErrorCode)
            assertEquals(trace, http.traceId)
        }

        val unreachable = AdvisorProxyClient(
            appToken = FAKE_TOKEN,
            baseUrl = "http://127.0.0.1:1/".toHttpUrl(),
        )
        val network = unreachable.start("failure")
            as AdvisorProxyCallResult.Failure
        assertEquals(AdvisorProxyFailureKind.NETWORK, network.kind)
        assertEquals(null, network.traceId)
    }

    private fun client(
        server: MockWebServer,
        token: String,
    ): AdvisorProxyClient =
        AdvisorProxyClient(
            appToken = token,
            baseUrl = server.url("/"),
        )

    private fun answerResponse(): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "type":"answer",
                  "responseId":"resp_1",
                  "text":"Synthetic answer",
                  "productRefs":[]
                }
                """.trimIndent(),
            )

    private companion object {
        const val FAKE_TOKEN = "synthetic-app-token"
    }
}
