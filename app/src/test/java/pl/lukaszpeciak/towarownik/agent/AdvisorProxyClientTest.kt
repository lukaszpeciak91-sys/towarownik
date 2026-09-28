package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            assertEquals(setOf("message", "storeNumber"), body.keys)
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
                setOf("previousResponseId", "message", "storeNumber"),
                body.keys,
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
                            "arguments":{"query":"klej montażowy","storeNumber":"074","limit":5}
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
                          "query":"klej",
                          "storeNumber":"075",
                          "limit":1
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
                        "arguments":{"query":"klej","storeNumber":"075","limit":6}
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
    fun `401 maps to bounded authentication failure`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("secret upstream body"))

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.AUTHENTICATION,
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
        }
    }

    @Test
    fun `other non success maps to bounded service failure`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("details"))

            assertEquals(
                AdvisorProxyCallResult.Failure(
                    AdvisorProxyFailureKind.SERVICE,
                ),
                client(server, FAKE_TOKEN).start("test"),
            )
            assertEquals(1, server.requestCount)
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
                    "responseId",
                    "callId",
                    "storeNumber",
                    "tool",
                    "result",
                ),
                body.keys,
            )
            assertEquals("resp_previous", body["responseId"]?.jsonPrimitive?.content)
            assertEquals("call_previous", body["callId"]?.jsonPrimitive?.content)
            assertEquals("075", body["storeNumber"]?.jsonPrimitive?.content)
            assertEquals(FIND_OBI_PRODUCTS, body["tool"]?.jsonPrimitive?.content)

            val resultBody = body["result"] as JsonObject
            assertEquals(
                setOf("query", "storeNumber", "products"),
                resultBody.keys,
            )
            assertEquals(
                "074",
                resultBody["storeNumber"]?.jsonPrimitive?.content,
            )
            val products = resultBody["products"] as kotlinx.serialization.json.JsonArray
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
    fun `worst case three byte rich continuation is trimmed below proxy byte limit without changing authoritative facts`() = runBlocking {
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

            val result = client(server, FAKE_TOKEN).continueTurn(
                responseId = threeByte.repeat(256),
                callId = threeByte.repeat(256),
                storeNumber = "075",
                continuation = AdvisorToolContinuation.Verified(
                    AdvisorVerifiedToolResult(
                        query = threeByte.repeat(200),
                        storeNumber = "075",
                        products = products,
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
            val serializedProducts =
                resultBody["products"] as
                    kotlinx.serialization.json.JsonArray

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
