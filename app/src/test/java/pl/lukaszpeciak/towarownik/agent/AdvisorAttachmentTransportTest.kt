package pl.lukaszpeciak.towarownik.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import pl.lukaszpeciak.towarownik.attachment.AttachmentStorage
import pl.lukaszpeciak.towarownik.attachment.AttachmentType
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult

@RunWith(RobolectricTestRunner::class)
class AdvisorAttachmentTransportTest {
    @Test
    fun `truncated private attachment fails locally instead of sending mismatched bytes`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = AttachmentStorage(context)
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        val attachment = storage.importValidated(
            type = AttachmentType.PDF,
            displayName = "spec.pdf",
            mimeType = "application/pdf",
            byteSize = bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
        )

        File(
            context.filesDir,
            "advisor_attachments/${attachment.localId}",
        ).writeBytes(bytes.copyOf(bytes.size - 1))

        MockWebServer().use { server ->
            val client = AdvisorProxyClient(
                appToken = "token",
                baseUrl = server.url("/"),
                attachmentStorage = storage,
            )

            val result = client.start(
                "",
                "obi-pl",
                "075",
                attachment,
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
    fun `attachment start streams private bytes as protocol v4 multipart`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = AttachmentStorage(context)
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        val attachment = storage.importValidated(
            type = AttachmentType.PDF,
            displayName = "spec.pdf",
            mimeType = "application/pdf",
            byteSize = bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
        )
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"type":"answer","responseId":"resp_1","text":"ok","productRefs":[]}""",
            ))
            val client = AdvisorProxyClient(
                appToken = "token",
                baseUrl = server.url("/"),
                attachmentStorage = storage,
            )
            client.start("", "obi-pl", "075", attachment)
            val request = server.takeRequest()
            assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data;"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"protocolVersion\":4"))
            assertTrue(body.contains("%PDF-"))
            assertEquals("/v1/agent/start", request.path)
            assertEquals(
                null,
                request.getHeader(ADVISOR_TRACE_HEADER),
            )
        }
    }

    @Test
    fun `attachment message starts a fresh trace and sends no prior trace header`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = AttachmentStorage(context)
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        val attachment = storage.importValidated(
            type = AttachmentType.PDF,
            displayName = "message.pdf",
            mimeType = "application/pdf",
            byteSize = bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
        )

        MockWebServer().use { server ->
            val trace = "77777777-7777-4777-8777-777777777777"
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setHeader(ADVISOR_TRACE_HEADER, trace)
                    .setBody(
                        """{"type":"answer","responseId":"resp_2","text":"ok","productRefs":[]}""",
                    ),
            )
            val client = AdvisorProxyClient(
                appToken = "token",
                baseUrl = server.url("/"),
                attachmentStorage = storage,
            )

            val result = client.message(
                previousResponseId = "resp_previous",
                message = "follow up",
                providerId = "obi-pl",
                branchId = "075",
                attachment = attachment,
            ) as AdvisorProxyCallResult.Success

            val request = server.takeRequest()
            assertEquals("/v1/agent/message", request.path)
            assertEquals(null, request.getHeader(ADVISOR_TRACE_HEADER))
            assertEquals(trace, result.traceId)
        }
    }

    @Test
    fun `OBI attachment tool turn stays protocol v4 through continuation and grounds the verified card`() = runBlocking {
        val turnTrace = "88888888-8888-4888-8888-888888888888"
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = AttachmentStorage(context)
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        val attachment = storage.importValidated(
            type = AttachmentType.PDF,
            displayName = "spec.pdf",
            mimeType = "application/pdf",
            byteSize = bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
        )

        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setHeader(ADVISOR_TRACE_HEADER, turnTrace)
                    .setBody(
                        """
                        {
                          "type":"tool_request",
                          "responseId":"resp_tool",
                          "tool":{
                            "name":"find_products",
                            "callId":"call_obi_v4",
                            "arguments":{
                              "providerId":"obi-pl",
                              "branchId":"075",
                              "requestedBranch":null,
                              "queries":[{"query":"miska","limit":1}]
                            }
                          },
                          "webSearchCalls":0
                        }
                        """.trimIndent(),
                    ),
            )
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setHeader(ADVISOR_TRACE_HEADER, turnTrace)
                    .setBody(
                        """
                        {
                          "type":"answer",
                          "responseId":"resp_final",
                          "text":"Mam zweryfikowany produkt.",
                          "productRefs":[
                            {
                              "providerId":"obi-pl",
                              "branchId":"075",
                              "productId":"1234567"
                            }
                          ],
                          "webSearchCalls":0
                        }
                        """.trimIndent(),
                    ),
            )

            val client = AdvisorProxyClient(
                appToken = "token",
                baseUrl = server.url("/"),
                attachmentStorage = storage,
            )
            val snapshot = VerifiedProductSnapshot(
                obik = "1234567",
                name = "Miska testowa",
                stock = 4,
                grossPrice = BigDecimal("29.99"),
                productUrl = "https://www.obi.pl/p/1234567",
                verifiedAt = 1L,
                storeNumber = "075",
            )
            var obiExecutions = 0
            var providerExecutions = 0

            val controller = AdvisorController(
                isConfigured = { true },
                startAgent = { _, _, _ ->
                    error("text-only start must not be used")
                },
                messageAgent = { _, _, _, _ ->
                    error("text-only message must not be used")
                },
                continueAgent = {
                        responseId,
                        callId,
                        providerId,
                        branchId,
                        contract,
                        continuation,
                    ->
                    continueAdvisorToolTurn(
                        proxyClient = client,
                        responseId = responseId,
                        callId = callId,
                        providerId = providerId,
                        branchId = branchId,
                        contract = contract,
                        continuation = continuation,
                    )
                },
                continueAgentWithTrace = {
                        responseId,
                        callId,
                        providerId,
                        branchId,
                        contract,
                        continuation,
                        traceId,
                    ->
                    continueAdvisorToolTurn(
                        proxyClient = client,
                        responseId = responseId,
                        callId = callId,
                        providerId = providerId,
                        branchId = branchId,
                        contract = contract,
                        continuation = continuation,
                        traceId = traceId,
                    )
                },
                executeObiTool = { arguments ->
                    obiExecutions += 1
                    AdvisorToolExecutionResult.Success(
                        result = AdvisorVerifiedToolResult(
                            storeNumber = arguments.storeNumber,
                            providerId = OBI_PROVIDER_ID.value,
                            results = listOf(
                                AdvisorVerifiedQueryResult(
                                    query = arguments.queries.single().query,
                                    status = AdvisorQueryResultStatus.VERIFIED,
                                    products = listOf(
                                        AdvisorVerifiedProduct(
                                            obik = snapshot.obik,
                                            name = snapshot.name,
                                            stock = snapshot.stock,
                                            price = snapshot.grossPrice,
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        snapshots = listOf(snapshot),
                    )
                },
                executeProviderTool = {
                    providerExecutions += 1
                    error("OBI attachment turn must keep the existing OBI tool")
                },
                branchDirectory = {
                    ProviderBranchResult.Available(
                        listOf(
                            ProviderBranch(
                                branchId = BranchId("075"),
                                name = "Nowy Sącz",
                                address = "ul. Prażmowskiego 11",
                            ),
                        ),
                    )
                },
                startAgentWithAttachment = { message, providerId, branchId, sentAttachment ->
                    client.start(
                        message = message,
                        providerId = providerId,
                        branchId = branchId,
                        attachment = sentAttachment,
                    )
                },
                messageAgentWithAttachment = { _, _, _, _, _ ->
                    error("attachment message must not be used in this start-turn test")
                },
            )

            val final = controller.runTurn(
                input = "sprawdź tę miskę",
                previousResponseId = null,
                conversationStoreNumber = "075",
                conversationProviderId = OBI_PROVIDER_ID.value,
                attachment = attachment,
                onState = {},
            )

            assertTrue(final is AdvisorUiState.Success)
            final as AdvisorUiState.Success
            assertEquals("resp_final", final.responseId)
            assertEquals(turnTrace, final.traceId)
            assertEquals(listOf(snapshot), final.products)
            assertEquals(1, obiExecutions)
            assertEquals(0, providerExecutions)

            val startRequest = server.takeRequest()
            assertEquals("/v1/agent/start", startRequest.path)
            assertEquals(null, startRequest.getHeader(ADVISOR_TRACE_HEADER))
            assertTrue(
                startRequest.getHeader("Content-Type")
                    ?.startsWith("multipart/form-data;") == true,
            )
            assertTrue(
                startRequest.body.readUtf8()
                    .contains("\"protocolVersion\":4"),
            )

            val continueRequest = server.takeRequest()
            assertEquals("/v1/agent/continue", continueRequest.path)
            assertEquals(
                turnTrace,
                continueRequest.getHeader(ADVISOR_TRACE_HEADER),
            )
            val continueBody = Json.parseToJsonElement(
                continueRequest.body.readUtf8(),
            ).jsonObject
            assertEquals(
                MULTIMODAL_ADVISOR_PROTOCOL_VERSION,
                continueBody["protocolVersion"]
                    ?.jsonPrimitive
                    ?.intOrNull,
            )
            assertEquals(
                FIND_PRODUCTS,
                continueBody["tool"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "obi-pl",
                continueBody["providerId"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "075",
                continueBody["branchId"]?.jsonPrimitive?.content,
            )

            val result = continueBody["result"] as JsonObject
            assertEquals(
                setOf("providerId", "branchId", "results"),
                result.keys,
            )
            assertEquals(
                "obi-pl",
                result["providerId"]?.jsonPrimitive?.content,
            )
            assertEquals(
                "075",
                result["branchId"]?.jsonPrimitive?.content,
            )
            val product = result["results"]
                ?.jsonArray
                ?.single()
                ?.jsonObject
                ?.get("products")
                ?.jsonArray
                ?.single()
                ?.jsonObject
            assertEquals(
                "1234567",
                product?.get("productId")?.jsonPrimitive?.content,
            )
            assertEquals(
                4,
                product?.get("stock")?.jsonPrimitive?.intOrNull,
            )
            assertEquals(
                "29.99",
                product?.get("price")?.jsonPrimitive?.content,
            )
        }
    }

    @Test
    fun `truncated private attachment fails locally during streaming`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = AttachmentStorage(context)
        val bytes = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        val attachment = storage.importValidated(
            type = AttachmentType.PDF,
            displayName = "spec.pdf",
            mimeType = "application/pdf",
            byteSize = bytes.size.toLong(),
            source = { ByteArrayInputStream(bytes) },
        )

        val storedFile = java.io.File(
            context.filesDir,
            "advisor_attachments/${attachment.localId}",
        )
        storedFile.writeBytes(bytes.copyOf(3))

        MockWebServer().use { server ->
            val client = AdvisorProxyClient(
                appToken = "token",
                baseUrl = server.url("/"),
                attachmentStorage = storage,
            )

            val result = client.start(
                message = "",
                providerId = "obi-pl",
                branchId = "075",
                attachment = attachment,
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

}
