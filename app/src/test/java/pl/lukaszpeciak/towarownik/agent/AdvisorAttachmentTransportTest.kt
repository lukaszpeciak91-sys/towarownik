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
    private fun importPart(
        storage: AttachmentStorage, name: String, type: AttachmentType,
    ): pl.lukaszpeciak.towarownik.attachment.AdvisorAttachment {
        val bytes = if (type == AttachmentType.PDF) {
            byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        } else {
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte())
        }
        return storage.importValidated(
            type = type, displayName = name,
            mimeType = if (type == AttachmentType.PDF) "application/pdf" else "image/jpeg",
            byteSize = bytes.size.toLong(),
            width = if (type == AttachmentType.IMAGE) 1 else null,
            height = if (type == AttachmentType.IMAGE) 1 else null,
            source = { ByteArrayInputStream(bytes) },
        )
    }

    private fun largePart(
        storage: AttachmentStorage,
        name: String,
        type: AttachmentType,
        byteSize: Int,
    ): pl.lukaszpeciak.towarownik.attachment.AdvisorAttachment {
        val bytes = ByteArray(byteSize)
        val signature = if (type == AttachmentType.PDF) {
            byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d)
        } else {
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())
        }
        signature.copyInto(bytes)
        return storage.importValidated(
            type = type,
            displayName = name,
            mimeType = if (type == AttachmentType.PDF) "application/pdf" else "image/jpeg",
            byteSize = byteSize.toLong(),
            width = if (type == AttachmentType.IMAGE) 1 else null,
            height = if (type == AttachmentType.IMAGE) 1 else null,
            source = { ByteArrayInputStream(bytes) },
        )
    }

    private fun multiAnswer() = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody("""{"type":"answer","responseId":"resp_multi","text":"ok","productRefs":[]}""")

    private fun textPart(
        storage: AttachmentStorage, name: String, mimeType: String,
        content: ByteArray = "zażółć,12\n".toByteArray(Charsets.UTF_8),
    ) = storage.importValidated(
        type = AttachmentType.TEXT,
        displayName = name,
        mimeType = mimeType,
        byteSize = content.size.toLong(),
        source = { ByteArrayInputStream(content) },
    )

    @Test
    fun v5AllowsEveryTextFamilyInOrderedMultipartWithoutChangingImagePdf() = runBlocking {
        val storage = AttachmentStorage(ApplicationProvider.getApplicationContext())
        val families = listOf(
            "notes.txt" to "text/plain",
            "guide.md" to "text/markdown",
            "stock.csv" to "text/csv",
            "data.json" to "application/json",
            "device.xml" to "application/xml",
            "settings.yaml" to "application/yaml",
            "settings.yml" to "text/x-yaml",
            "events.log" to "text/plain",
            "config.ini" to "text/plain",
            "app.conf" to "text/plain",
        )
        MockWebServer().use { server ->
            repeat(families.size + 1) { server.enqueue(multiAnswer()) }
            val client = AdvisorProxyClient(
                appToken = "token", baseUrl = server.url("/"), attachmentStorage = storage,
            )
            for ((name, mime) in families) {
                val file = textPart(storage, name, mime)
                assertTrue(client.start("", "kwant-pl", "205", listOf(file))
                    is AdvisorProxyCallResult.Success)
                val request = server.takeRequest()
                assertEquals("5", request.getHeader("X-Taksula-Attachment-Protocol"))
                val body = request.body.readUtf8()
                assertTrue(body.contains("filename=\"$name\""))
                assertTrue(body.contains("Content-Type: $mime"))
                assertTrue(body.contains("zażółć,12"))
            }
            val image = importPart(storage, "photo.jpg", AttachmentType.IMAGE)
            val pdf = importPart(storage, "datasheet.pdf", AttachmentType.PDF)
            val text = textPart(storage, "notes.md", "text/markdown")
            val mixed = listOf(image, pdf, text)
            assertTrue(client.start("Compare", "kwant-pl", "205", mixed)
                is AdvisorProxyCallResult.Success)
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            var position = -1
            for (part in mixed) {
                val found = body.indexOf(part.displayName)
                assertTrue(found > position)
                position = found
            }
            assertEquals(3, Regex("name=\"attachment\"; filename=").findAll(body).count())
        }
    }

    @Test
    fun v5RejectsWrongTextMetadataAndBinaryBytesBeforeNetwork() = runBlocking {
        val storage = AttachmentStorage(ApplicationProvider.getApplicationContext())
        val invalidUtf8 = textPart(
            storage, "broken.txt", "text/plain", byteArrayOf(0xc3.toByte(), 0x28),
        )
        val binary = textPart(
            storage, "binary.log", "text/plain", byteArrayOf(0x61, 0x00, 0x62),
        )
        val zip = textPart(
            storage, "archive.txt", "text/plain",
            byteArrayOf(0x50, 0x4b, 0x03, 0x04, 0x20),
        )
        val good = textPart(storage, "ok.csv", "text/csv")
        MockWebServer().use { server ->
            val client = AdvisorProxyClient(
                appToken = "token", baseUrl = server.url("/"), attachmentStorage = storage,
            )
            val variants = listOf(
                listOf(invalidUtf8),
                listOf(binary),
                listOf(zip),
                listOf(good.copy(displayName = "ok.exe")),
                listOf(good.copy(displayName = "ok.docx")),
                listOf(good.copy(displayName = "ok.zip")),
                listOf(good.copy(displayName = "ok.json")),
                listOf(good.copy(mimeType = "application/octet-stream")),
                listOf(good.copy(byteSize = 1024L * 1024L + 1)),
            )
            for (files in variants) {
                assertEquals(
                    AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.PROTOCOL),
                    client.start("", "kwant-pl", "205", files),
                )
            }
            assertEquals(
                AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.PROTOCOL),
                client.start("", "kwant-pl", "205", attachment = good),
            )
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun v5TextAggregateAcceptsExactOneMiBAndMultipleFilesWithinTotal() = runBlocking {
        val storage = AttachmentStorage(ApplicationProvider.getApplicationContext())
        fun text(name: String, size: Int) = textPart(
            storage, name, "text/plain", ByteArray(size) { 'a'.code.toByte() },
        )
        val one = text("full.txt", 1024 * 1024)
        val many = listOf(
            text("part1.txt", 256 * 1024),
            text("part2.txt", 256 * 1024),
            text("part3.txt", 512 * 1024),
        )
        val under = listOf(text("small1.txt", 100), text("small2.txt", 200))
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(multiAnswer()) }
            val client = AdvisorProxyClient(
                appToken = "token", baseUrl = server.url("/"), attachmentStorage = storage,
            )
            for (parts in listOf(listOf(one), many, under)) {
                assertTrue(
                    client.start("", "kwant-pl", "205", parts) is AdvisorProxyCallResult.Success,
                )
                val request = server.takeRequest()
                assertEquals("5", request.getHeader("X-Taksula-Attachment-Protocol"))
                val body = request.body.readUtf8()
                assertEquals(parts.size, Regex("name=\"attachment\"; filename=").findAll(body).count())
                var preceding = -1
                for (part in parts) {
                    val current = body.indexOf(part.displayName)
                    assertTrue(current > preceding)
                    preceding = current
                }
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun v5TextAggregateRejectsOverOneMiBBeforeNetworkIncludingMixedMedia() = runBlocking {
        val storage = AttachmentStorage(ApplicationProvider.getApplicationContext())
        fun text(name: String, size: Int) = textPart(
            storage, name, "text/plain", ByteArray(size) { 'b'.code.toByte() },
        )
        val first = text("first.txt", 512 * 1024)
        val second = text("second.txt", 512 * 1024 + 1)
        val image = importPart(storage, "photo.jpg", AttachmentType.IMAGE)
        val pdf = importPart(storage, "manual.pdf", AttachmentType.PDF)
        MockWebServer().use { server ->
            val client = AdvisorProxyClient(
                appToken = "token", baseUrl = server.url("/"), attachmentStorage = storage,
            )
            for (parts in listOf(
                listOf(first, second),
                listOf(image, first, second),
                listOf(pdf, first, second),
            )) {
                assertEquals(
                    AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.PROTOCOL),
                    client.start("", "kwant-pl", "205", parts),
                )
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun v5MixedImagePdfAndFullOneMiBTextStillSendsSuccessfully() = runBlocking {
        val storage = AttachmentStorage(ApplicationProvider.getApplicationContext())
        val image = importPart(storage, "front.jpg", AttachmentType.IMAGE)
        val pdf = importPart(storage, "spec.pdf", AttachmentType.PDF)
        val text = textPart(
            storage, "full.txt", "text/plain",
            ByteArray(1024 * 1024) { 'c'.code.toByte() },
        )
        MockWebServer().use { server ->
            server.enqueue(multiAnswer())
            val client = AdvisorProxyClient(
                appToken = "token", baseUrl = server.url("/"), attachmentStorage = storage,
            )
            assertTrue(
                client.start("", "kwant-pl", "205", listOf(image, pdf, text))
                    is AdvisorProxyCallResult.Success,
            )
            val request = server.takeRequest()
            assertEquals("5", request.getHeader("X-Taksula-Attachment-Protocol"))
            val body = request.body.readUtf8()
            assertTrue(body.indexOf("front.jpg") < body.indexOf("spec.pdf"))
            assertTrue(body.indexOf("spec.pdf") < body.indexOf("full.txt"))
            assertEquals(3, Regex("name=\"attachment\"; filename=").findAll(body).count())
        }
    }

    @Test
    fun v5ListTransportsOneTwoAndThreeOrderedMixedFiles() = runBlocking {
        val storage = AttachmentStorage(ApplicationProvider.getApplicationContext())
        val first = importPart(storage, "one.pdf", AttachmentType.PDF)
        val second = importPart(storage, "two.jpg", AttachmentType.IMAGE)
        val third = importPart(storage, "three.pdf", AttachmentType.PDF)
        MockWebServer().use { server ->
            repeat(3) { server.enqueue(multiAnswer()) }
            val client = AdvisorProxyClient(
                appToken = "token", baseUrl = server.url("/"), attachmentStorage = storage,
            )
            for (files in listOf(listOf(first), listOf(first, second), listOf(first, second, third))) {
                val result = client.start(
                    message = "", providerId = "kwant-pl", branchId = "205", attachments = files,
                )
                assertTrue(result is AdvisorProxyCallResult.Success)
                val request = server.takeRequest()
                assertEquals("/v1/agent/start", request.path)
                assertEquals("5", request.getHeader("X-Taksula-Attachment-Protocol"))
                assertEquals(null, request.getHeader(ADVISOR_TRACE_HEADER))
                val body = request.body.readUtf8()
                assertTrue(body.contains("\"protocolVersion\":5"))
                assertEquals(files.size, Regex("name=\"attachment\"; filename=").findAll(body).count())
                var offset = -1
                for (file in files) {
                    val next = body.indexOf(file.displayName)
                    assertTrue(next > offset)
                    offset = next
                }
                assertTrue(body.contains("%PDF-"))
            }
        }
    }

    @Test
    fun v5MessageIncludesTextFilesAndPreviousResponse() = runBlocking {
        val storage = AttachmentStorage(ApplicationProvider.getApplicationContext())
        val files = listOf(
            importPart(storage, "image.jpg", AttachmentType.IMAGE),
            importPart(storage, "material.pdf", AttachmentType.PDF),
        )
        MockWebServer().use { server ->
            server.enqueue(multiAnswer())
            val client = AdvisorProxyClient(
                appToken = "token", baseUrl = server.url("/"), attachmentStorage = storage,
            )
            val result = client.message(
                previousResponseId = "resp_before", message = "porównaj dane",
                providerId = "kwant-pl", branchId = "205", attachments = files,
            )
            assertTrue(result is AdvisorProxyCallResult.Success)
            val request = server.takeRequest()
            assertEquals("/v1/agent/message", request.path)
            assertEquals("5", request.getHeader("X-Taksula-Attachment-Protocol"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"previousResponseId\":\"resp_before\""))
            assertTrue(body.contains("\"message\":\"porównaj dane\""))
            assertEquals(2, Regex("name=\"attachment\"; filename=").findAll(body).count())
        }
    }

    @Test
    fun v5AcceptsSixteenMiBPerFileAndTwentyFourMiBAggregate() = runBlocking {
        val storage = AttachmentStorage(ApplicationProvider.getApplicationContext())
        val one = largePart(storage, "full16.jpg", AttachmentType.IMAGE, 16 * 1024 * 1024)
        val two = largePart(storage, "extra8.pdf", AttachmentType.PDF, 8 * 1024 * 1024)
        val totalCap = 24L * 1024 * 1024
        val bodyCap = totalCap + 16 * 1024
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(multiAnswer()) }
            val client = AdvisorProxyClient(
                appToken = "token", baseUrl = server.url("/"), attachmentStorage = storage,
            )
            for (files in listOf(listOf(one), listOf(one, two))) {
                val result = client.start("", "kwant-pl", "205", attachments = files)
                assertTrue(result is AdvisorProxyCallResult.Success)
                val request = server.takeRequest()
                assertEquals("5", request.getHeader("X-Taksula-Attachment-Protocol"))
                val payloadSize = files.sumOf { it.byteSize }
                assertTrue(request.body.size >= payloadSize)
                assertTrue(request.body.size <= bodyCap)
                assertTrue(request.getHeader("Content-Length")!!.toLong() <= bodyCap)
            }
        }
    }

    @Test
    fun v5RejectsInvalidCountAndMetadataBeforeNetwork() = runBlocking {
        val storage = AttachmentStorage(ApplicationProvider.getApplicationContext())
        val file = importPart(storage, "one.pdf", AttachmentType.PDF)
        val image = importPart(storage, "two.jpg", AttachmentType.IMAGE)
        MockWebServer().use { server ->
            val client = AdvisorProxyClient(
                appToken = "token", baseUrl = server.url("/"), attachmentStorage = storage,
            )
            val variants = listOf(
                emptyList(),
                listOf(file, image, file, image),
                listOf(file, image.copy(mimeType = "application/pdf")),
                listOf(file, image.copy(byteSize = 16L * 1024 * 1024 + 1)),
                // Neither individual part exceeds 16 MiB, but combined bytes exceed 24 MiB.
                listOf(
                    file.copy(byteSize = 16L * 1024 * 1024),
                    image.copy(byteSize = 8L * 1024 * 1024 + 1),
                ),
            )
            for (files in variants) {
                assertEquals(
                    AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.PROTOCOL),
                    client.start("", "kwant-pl", "205", files),
                )
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun v5ContinuationRemainsProviderJsonOnly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(multiAnswer())
            val client = AdvisorProxyClient(appToken = "token", baseUrl = server.url("/"))
            val response = client.continueTurn(
                responseId = "resp_tool", callId = "call_tool",
                providerId = "kwant-pl", branchId = "205",
                continuation = AdvisorToolContinuation.LocalToolLimitReached(
                    queries = listOf(AdvisorToolQuery("MBN116E", 1)),
                    storeNumber = "205", providerId = "kwant-pl",
                ),
                protocolVersion = MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION,
            )
            assertTrue(response is AdvisorProxyCallResult.Success)
            val request = server.takeRequest()
            assertEquals("/v1/agent/continue", request.path)
            assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
            assertEquals(null, request.getHeader("X-Taksula-Attachment-Protocol"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(5, body["protocolVersion"]?.jsonPrimitive?.intOrNull)
            assertEquals(FIND_PRODUCTS, body["tool"]?.jsonPrimitive?.content)
        }
    }

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
