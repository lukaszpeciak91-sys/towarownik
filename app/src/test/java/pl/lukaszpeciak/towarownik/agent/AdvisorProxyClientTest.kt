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
            assertEquals(setOf("message"), body.keys)
            assertEquals(
                "potrzebuję kleju",
                body["message"]?.jsonPrimitive?.content,
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
                    ),
                ),
                result,
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
                            "name":"find_available_obi_075",
                            "callId":"call_1",
                            "arguments":{"query":"klej montażowy","limit":5}
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
                            limit = 5,
                        ),
                    ),
                ),
                result,
            )
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
                        "name":"find_available_obi_075",
                        "callId":"call_1",
                        "arguments":{"query":"klej","limit":6}
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
                products = listOf(
                    AdvisorVerifiedProduct(
                        obik = "1234567",
                        name = "Synthetic product",
                        stock = 0,
                        price = BigDecimal("14.99"),
                    ),
                ),
            )

            val result = client(server, FAKE_TOKEN).continueTurn(
                responseId = "resp_previous",
                callId = "call_previous",
                result = verified,
            )

            assertTrue(result is AdvisorProxyCallResult.Success)
            val request = server.takeRequest()
            assertEquals("/v1/agent/continue", request.path)
            val raw = request.body.readUtf8()
            val body = Json.parseToJsonElement(raw).jsonObject
            assertEquals(
                setOf("responseId", "callId", "tool", "result"),
                body.keys,
            )
            assertEquals("resp_previous", body["responseId"]?.jsonPrimitive?.content)
            assertEquals("call_previous", body["callId"]?.jsonPrimitive?.content)
            assertEquals(FIND_AVAILABLE_OBI_075, body["tool"]?.jsonPrimitive?.content)

            val resultBody = body["result"] as JsonObject
            assertEquals(setOf("query", "products"), resultBody.keys)
            val products = resultBody["products"] as kotlinx.serialization.json.JsonArray
            val product = products.single() as JsonObject
            assertEquals(
                setOf("obik", "name", "stock", "price"),
                product.keys,
            )
            assertFalse(raw.contains("html", ignoreCase = true))
            assertFalse(raw.contains("cookie", ignoreCase = true))
            assertFalse(raw.contains(FAKE_TOKEN))
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
                  "text":"Synthetic answer"
                }
                """.trimIndent(),
            )

    private companion object {
        const val FAKE_TOKEN = "synthetic-app-token"
    }
}
