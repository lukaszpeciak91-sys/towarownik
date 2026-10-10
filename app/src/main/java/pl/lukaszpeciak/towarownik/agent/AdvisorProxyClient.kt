package pl.lukaszpeciak.towarownik.agent

import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import pl.lukaszpeciak.towarownik.BuildConfig
import pl.lukaszpeciak.towarownik.attachment.AdvisorAttachment
import pl.lukaszpeciak.towarownik.attachment.AttachmentStorage
import pl.lukaszpeciak.towarownik.attachment.AttachmentType
import pl.lukaszpeciak.towarownik.attachment.allowedTextAttachment
import pl.lukaszpeciak.towarownik.attachment.decodedTextAttachmentOrNull
import pl.lukaszpeciak.towarownik.attachment.TEXT_ATTACHMENT_MAX_BYTES
import okio.BufferedSink
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID

internal class AdvisorProxyClient(
    private val appToken: String = BuildConfig.TOWAROWNIK_APP_TOKEN,
    private val baseUrl: HttpUrl = ADVISOR_PROXY_BASE_URL.toHttpUrl(),
    client: OkHttpClient? = null,
    private val attachmentStorage: AttachmentStorage? = null,
) {
    private val client = client ?: OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun isConfigured(): Boolean = appToken.isNotBlank()

    suspend fun start(
        message: String,
        storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    ): AdvisorProxyCallResult {
        if (!isConfigured()) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.NOT_CONFIGURED,
            )
        }

        if (!STORE_NUMBER_PATTERN.matches(storeNumber)) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
            )
        }

        val body = buildJsonObject {
            put("protocolVersion", OBI_ADVISOR_PROTOCOL_VERSION)
            put("message", message)
            put("storeNumber", storeNumber)
        }

        return execute(
            endpoint = "v1/agent/start",
            body = body,
        )
    }

    suspend fun start(
        message: String,
        providerId: String,
        branchId: String,
        attachment: AdvisorAttachment? = null,
    ): AdvisorProxyCallResult {
        if (!isConfigured()) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.NOT_CONFIGURED,
            )
        }
        if (!isValidProfile(providerId, branchId)) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
            )
        }

        val body = buildJsonObject {
            put("protocolVersion", if (attachment == null) ADVISOR_PROTOCOL_VERSION else MULTIMODAL_ADVISOR_PROTOCOL_VERSION)
            put("message", message)
            put("providerId", providerId)
            put("branchId", branchId)
        }
        return if (attachment == null) execute(
            endpoint = "v1/agent/start",
            body = body,
        ) else executeMultipart("v1/agent/start", body, listOf(attachment))
    }

    // Explicit list overload opts into v5; existing nullable single-attachment
    // overload continues to send v4, including today's one-file composer.
    suspend fun start(
        message: String,
        providerId: String,
        branchId: String,
        attachments: List<AdvisorAttachment>,
    ): AdvisorProxyCallResult {
        if (!isConfigured()) return AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.NOT_CONFIGURED)
        if (!isValidProfile(providerId, branchId)) {
            return AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.PROTOCOL)
        }
        val body = buildJsonObject {
            put("protocolVersion", MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION)
            put("message", message)
            put("providerId", providerId)
            put("branchId", branchId)
        }
        return executeMultipart("v1/agent/start", body, attachments)
    }

    suspend fun message(
        previousResponseId: String,
        message: String,
        storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    ): AdvisorProxyCallResult {
        if (!isConfigured()) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.NOT_CONFIGURED,
            )
        }

        if (!STORE_NUMBER_PATTERN.matches(storeNumber)) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
            )
        }

        val body = buildJsonObject {
            put("protocolVersion", OBI_ADVISOR_PROTOCOL_VERSION)
            put("previousResponseId", previousResponseId)
            put("message", message)
            put("storeNumber", storeNumber)
        }

        return execute(
            endpoint = "v1/agent/message",
            body = body,
        )
    }

    suspend fun message(
        previousResponseId: String,
        message: String,
        providerId: String,
        branchId: String,
        attachment: AdvisorAttachment? = null,
    ): AdvisorProxyCallResult {
        if (!isConfigured()) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.NOT_CONFIGURED,
            )
        }
        if (!isValidProfile(providerId, branchId)) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
            )
        }

        val body = buildJsonObject {
            put("protocolVersion", if (attachment == null) ADVISOR_PROTOCOL_VERSION else MULTIMODAL_ADVISOR_PROTOCOL_VERSION)
            put("previousResponseId", previousResponseId)
            put("message", message)
            put("providerId", providerId)
            put("branchId", branchId)
        }
        return if (attachment == null) execute(
            endpoint = "v1/agent/message",
            body = body,
        ) else executeMultipart("v1/agent/message", body, listOf(attachment))
    }

    suspend fun message(
        previousResponseId: String,
        message: String,
        providerId: String,
        branchId: String,
        attachments: List<AdvisorAttachment>,
    ): AdvisorProxyCallResult {
        if (!isConfigured()) return AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.NOT_CONFIGURED)
        if (!isValidProfile(providerId, branchId)) {
            return AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.PROTOCOL)
        }
        val body = buildJsonObject {
            put("protocolVersion", MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION)
            put("previousResponseId", previousResponseId)
            put("message", message)
            put("providerId", providerId)
            put("branchId", branchId)
        }
        return executeMultipart("v1/agent/message", body, attachments)
    }

    suspend fun continueTurn(
        responseId: String,
        callId: String,
        storeNumber: String,
        continuation: AdvisorToolContinuation,
        traceId: String? = null,
    ): AdvisorProxyCallResult {
        if (!isConfigured()) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.NOT_CONFIGURED,
            )
        }

        if (!STORE_NUMBER_PATTERN.matches(storeNumber)) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
            )
        }

        val body = when (continuation) {
            is AdvisorToolContinuation.Locations ->
                buildLocationContinueBody(
                    responseId, callId, OBI_PROVIDER_ID.value, storeNumber,
                    OBI_ADVISOR_PROTOCOL_VERSION, continuation.evidence,
                ).takeIf(::fitsContinueByteBudget)
            is AdvisorToolContinuation.Verified ->
                buildBudgetedVerifiedContinueBody(
                    responseId = responseId,
                    callId = callId,
                    storeNumber = storeNumber,
                    result = continuation.result,
                )
            is AdvisorToolContinuation.RejectedStore ->
                buildContinueBody(
                    responseId = responseId,
                    callId = callId,
                    storeNumber = storeNumber,
                    result = buildJsonObject {
                        put("storeNumber", continuation.storeNumber)
                        put("queries", continuation.queries.toJson())
                        put(
                            "rejection",
                            "store_not_authorized",
                        )
                    },
                ).takeIf(::fitsContinueByteBudget)
            is AdvisorToolContinuation.LocalToolLimitReached ->
                buildContinueBody(
                    responseId = responseId,
                    callId = callId,
                    storeNumber = storeNumber,
                    result = buildJsonObject {
                        put("storeNumber", continuation.storeNumber)
                        put("queries", continuation.queries.toJson())
                        put(
                            "rejection",
                            "local_tool_limit_reached",
                        )
                    },
                ).takeIf(::fitsContinueByteBudget)
        } ?: return AdvisorProxyCallResult.Failure(
            AdvisorProxyFailureKind.PROTOCOL,
        )

        return execute(
            endpoint = "v1/agent/continue",
            body = body,
            traceId = traceId,
        )
    }

    suspend fun continueTurn(
        responseId: String,
        callId: String,
        providerId: String,
        branchId: String,
        continuation: AdvisorToolContinuation,
        protocolVersion: Int = ADVISOR_PROTOCOL_VERSION,
        traceId: String? = null,
    ): AdvisorProxyCallResult {
        if (!isConfigured()) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.NOT_CONFIGURED,
            )
        }
        if (
            !isValidProfile(providerId, branchId) ||
            protocolVersion !in setOf(
                ADVISOR_PROTOCOL_VERSION,
                MULTIMODAL_ADVISOR_PROTOCOL_VERSION,
                MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION,
            )
        ) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
            )
        }

        val body = when (continuation) {
            is AdvisorToolContinuation.Locations ->
                buildLocationContinueBody(
                    responseId, callId, providerId, branchId,
                    protocolVersion, continuation.evidence,
                ).takeIf(::fitsContinueByteBudget)
            is AdvisorToolContinuation.Verified ->
                buildBudgetedVerifiedContinueBodyV3(
                    responseId = responseId,
                    callId = callId,
                    providerId = providerId,
                    branchId = branchId,
                    result = continuation.result,
                    protocolVersion = protocolVersion,
                )

            is AdvisorToolContinuation.RejectedStore ->
                buildContinueBodyV3(
                    responseId = responseId,
                    callId = callId,
                    providerId = providerId,
                    branchId = branchId,
                    protocolVersion = protocolVersion,
                    result = buildJsonObject {
                        put("providerId", continuation.providerId)
                        put("branchId", continuation.storeNumber)
                        put("queries", continuation.queries.toJson())
                        put("rejection", "branch_not_authorized")
                    },
                ).takeIf(::fitsContinueByteBudget)

            is AdvisorToolContinuation.LocalToolLimitReached ->
                buildContinueBodyV3(
                    responseId = responseId,
                    callId = callId,
                    providerId = providerId,
                    branchId = branchId,
                    protocolVersion = protocolVersion,
                    result = buildJsonObject {
                        put("providerId", continuation.providerId)
                        put("branchId", continuation.storeNumber)
                        put("queries", continuation.queries.toJson())
                        put(
                            "rejection",
                            "local_tool_limit_reached",
                        )
                    },
                ).takeIf(::fitsContinueByteBudget)
        } ?: return AdvisorProxyCallResult.Failure(
            AdvisorProxyFailureKind.PROTOCOL,
        )

        return execute(
            endpoint = "v1/agent/continue",
            body = body,
            traceId = traceId,
        )
    }

    private suspend fun execute(
        endpoint: String,
        body: JsonObject,
        traceId: String? = null,
    ): AdvisorProxyCallResult {
        val url = baseUrl.newBuilder()
            .addPathSegments(endpoint)
            .build()
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $appToken")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .header("X-Taksula-Locations-Capability", "1")
        advisorTraceIdOrNull(traceId)?.let {
            requestBuilder.header(ADVISOR_TRACE_HEADER, it)
        }
        val request = requestBuilder.build()

        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, exception: java.io.IOException) {
                        if (continuation.isActive) {
                            continuation.resume(
                                AdvisorProxyCallResult.Failure(
                                    AdvisorProxyFailureKind.NETWORK,
                                ),
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = response.use {
                            mapResponse(
                                response = it,
                                endpoint = endpoint.substringAfterLast('/'),
                            )
                        }
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                },
            )
        }
    }

    private suspend fun executeMultipart(
        endpoint: String,
        payload: JsonObject,
        attachments: List<AdvisorAttachment>,
    ): AdvisorProxyCallResult {
        val isMulti = payload["protocolVersion"]?.jsonPrimitive?.intOrNull ==
            MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION
        val maxCount = if (isMulti) 3 else 1
        val maxPerFileBytes = 16L * 1024 * 1024
        val maxTotalBytes = if (isMulti) 24L * 1024 * 1024 else maxPerFileBytes
        val maxBodyBytes = maxTotalBytes + 16L * 1024
        if (
            attachments.size !in 1..maxCount ||
            attachments.any {
                it.byteSize < 1L || it.byteSize > maxPerFileBytes ||
                    (it.type == pl.lukaszpeciak.towarownik.attachment.AttachmentType.IMAGE &&
                        it.mimeType !in setOf("image/jpeg", "image/png")) ||
                    (it.type == pl.lukaszpeciak.towarownik.attachment.AttachmentType.PDF &&
                        it.mimeType != "application/pdf") ||
                    (it.type == AttachmentType.TEXT &&
                        (!isMulti || it.byteSize > TEXT_ATTACHMENT_MAX_BYTES ||
                            !allowedTextAttachment(it.displayName, it.mimeType)))
            } ||
            attachments.sumOf { it.byteSize } > maxTotalBytes
        ) {
            return AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.PROTOCOL)
        }
        val storage = attachmentStorage ?: return AdvisorProxyCallResult.Failure(
            AdvisorProxyFailureKind.PROTOCOL,
        )
        if (attachments.any { !storage.exists(it.localId) }) {
            return AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.PROTOCOL)
        }
        // Fail locally before networking; the Worker independently repeats this
        // validation because Android is not an authoritative trust boundary.
        for (part in attachments) {
            if (part.type != AttachmentType.TEXT) continue
            val bytes = runCatching {
                storage.open(part.localId)?.use { input ->
                    input.readNBytes((TEXT_ATTACHMENT_MAX_BYTES + 1).toInt())
                }
            }.getOrNull()
            if (bytes == null || bytes.size.toLong() != part.byteSize ||
                decodedTextAttachmentOrNull(bytes) == null
            ) {
                return AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.PROTOCOL)
            }
        }
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("payload", payload.toString())
        for (attachment in attachments) {
            val fileBody = object : RequestBody() {
                override fun contentType() = attachment.mimeType.toMediaType()
                override fun contentLength() = attachment.byteSize
                override fun writeTo(sink: BufferedSink) {
                    val input = storage.open(attachment.localId)
                        ?: throw AttachmentByteCountMismatchException()
                    input.use { source ->
                        val buffer = ByteArray(8 * 1024)
                        var written = 0L
                        while (written < attachment.byteSize) {
                            val remaining = attachment.byteSize - written
                            val read = source.read(
                                buffer,
                                0,
                                minOf(buffer.size.toLong(), remaining).toInt(),
                            )
                            if (read < 0) throw AttachmentByteCountMismatchException()
                            sink.write(buffer, 0, read)
                            written += read
                        }
                        if (written != attachment.byteSize || source.read() != -1) {
                            throw AttachmentByteCountMismatchException()
                        }
                    }
                }
            }
            builder.addFormDataPart("attachment", attachment.displayName, fileBody)
        }
        val multipart = builder.build()
        if (multipart.contentLength() > maxBodyBytes) {
            return AdvisorProxyCallResult.Failure(AdvisorProxyFailureKind.PROTOCOL)
        }
        val requestBuilder = Request.Builder()
            .url(baseUrl.newBuilder().addPathSegments(endpoint).build())
            .post(multipart)
            .header("Authorization", "Bearer $appToken")
            .header("X-Taksula-Locations-Capability", "1")
        if (isMulti) {
            requestBuilder.header("X-Taksula-Attachment-Protocol", "5")
        }
        return executeRequest(requestBuilder.build(), endpoint)
    }

    private suspend fun executeRequest(request: Request, endpoint: String): AdvisorProxyCallResult =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, exception: IOException) {
                    if (continuation.isActive) {
                        continuation.resume(
                            AdvisorProxyCallResult.Failure(
                                if (exception is AttachmentByteCountMismatchException) {
                                    AdvisorProxyFailureKind.PROTOCOL
                                } else {
                                    AdvisorProxyFailureKind.NETWORK
                                },
                            ),
                        )
                    }
                }
                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { mapResponse(it, endpoint.substringAfterLast('/')) }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }

    private fun mapResponse(
        response: Response,
        endpoint: String,
    ): AdvisorProxyCallResult {
        val traceId = advisorTraceIdOrNull(
            response.header(ADVISOR_TRACE_HEADER),
        )
        if (!response.isSuccessful) {
            val kind = when (response.code) {
                401 -> AdvisorProxyFailureKind.AUTHENTICATION
                400, 413 -> AdvisorProxyFailureKind.PROTOCOL
                else -> AdvisorProxyFailureKind.SERVICE
            }
            return AdvisorProxyCallResult.Failure(
                kind = kind,
                httpStatus = response.code,
                proxyErrorCode = parseProxyErrorCode(response),
                endpoint = endpoint,
                traceId = traceId,
            )
        }

        val preview = runCatching {
            response.peekBody(MAX_PROXY_RESPONSE_BYTES + 1L).bytes()
        }.getOrNull()
            ?: return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
                traceId = traceId,
            )

        if (preview.isEmpty() || preview.size > MAX_PROXY_RESPONSE_BYTES) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
                traceId = traceId,
            )
        }

        val text = runCatching {
            preview.toString(Charsets.UTF_8)
        }.getOrNull()
            ?: return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
                traceId = traceId,
            )

        val result = runCatching {
            parseEnvelope(text)
        }.getOrNull()
            ?: return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
                traceId = traceId,
            )

        return AdvisorProxyCallResult.Success(
            result = result,
            traceId = traceId,
        )
    }

    private fun parseProxyErrorCode(
        response: Response,
    ): String? =
        runCatching {
            val raw = response
                .peekBody(MAX_PROXY_ERROR_BYTES.toLong())
                .string()
            val root = Json.parseToJsonElement(raw) as? JsonObject
                ?: return null
            root["error"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf(SAFE_PROXY_ERROR_CODES::contains)
        }.getOrNull()

    private fun parseEnvelope(raw: String): AdvisorProxyResult {
        val root = Json.parseToJsonElement(raw) as? JsonObject
            ?: error("Expected JSON object")
        val type = root["type"]?.jsonPrimitive?.contentOrNull
            ?: error("Missing response type")
        val responseId = root["responseId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS }
            ?: error("Invalid response id")

        return when (type) {
            "answer" -> {
                requireEnvelopeKeys(
                    root,
                    required = setOf(
                        "type",
                        "responseId",
                        "text",
                        "productRefs",
                    ),
                    optional = setOf(
                        "usage",
                        "sources",
                        "webSearchCalls",
                    ),
                )
                val text = root["text"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf {
                        it.isNotBlank() &&
                            it.length <= MAX_ANSWER_CHARS
                    }
                    ?: error("Invalid answer text")
                val productRefs = root["productRefs"]
                    ?.jsonArray
                    ?.map { element ->
                        val reference = element as? JsonObject
                            ?: error("Invalid product reference")
                        when {
                            reference.keys ==
                                setOf("providerId", "branchId", "productId") -> {
                                val providerId = reference["providerId"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?.takeIf(PROVIDER_ID_PATTERN::matches)
                                    ?: error("Invalid selected provider")
                                val branchId = reference["branchId"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?.takeIf(BRANCH_ID_PATTERN::matches)
                                    ?: error("Invalid selected branch")
                                val productId = reference["productId"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?.takeIf(PRODUCT_ID_PATTERN::matches)
                                    ?: error("Invalid selected product id")
                                AdvisorProductRef(
                                    storeNumber = branchId,
                                    obik = productId,
                                    providerId = providerId,
                                )
                            }

                            reference.keys == setOf("storeNumber", "obik") -> {
                                val storeNumber = reference["storeNumber"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?.takeIf(STORE_NUMBER_PATTERN::matches)
                                    ?: error("Invalid selected store")
                                val obik = reference["obik"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?.takeIf(OBIK_PATTERN::matches)
                                    ?: error("Invalid selected OBIK")
                                AdvisorProductRef(
                                    storeNumber = storeNumber,
                                    obik = obik,
                                )
                            }

                            else -> error("Invalid product reference")
                        }
                    }
                    ?.takeIf { it.size <= MAX_TOOL_PRODUCTS }
                    ?: error("Invalid selected products")
                val webSearchCalls =
                    root.requireWebSearchCallCount()
                AdvisorProxyResult.Answer(
                    responseId = responseId,
                    text = text,
                    productRefs = productRefs.distinctBy { it.key },
                    sources = parseSourcesOrEmpty(
                        root["sources"],
                        answerLength = text.length,
                    ),
                    webSearchCalls = webSearchCalls,
                    usage = parseUsageOrNull(root["usage"]),
                )
            }

            "tool_request" -> {
                requireEnvelopeKeys(
                    root,
                    required = setOf(
                        "type",
                        "responseId",
                        "tool",
                    ),
                    optional = setOf("usage", "webSearchCalls"),
                )
                val tool = root["tool"] as? JsonObject
                    ?: error("Missing tool")
                requireExactKeys(
                    tool,
                    setOf("name", "callId", "arguments"),
                )
                val toolName =
                    tool["name"]?.jsonPrimitive?.contentOrNull
                        ?: error("Missing tool name")
                require(
                    toolName == FIND_OBI_PRODUCTS ||
                        toolName == FIND_PRODUCTS ||
                        toolName == FIND_PRODUCT_LOCATIONS,
                )
                val callId = tool["callId"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS }
                    ?: error("Invalid call id")
                val arguments = tool["arguments"] as? JsonObject
                    ?: error("Missing tool arguments")

                if (toolName == FIND_PRODUCT_LOCATIONS) {
                    requireExactKeys(
                        arguments,
                        setOf("providerId", "productId", "locations"),
                    )
                    val providerId = arguments["providerId"]
                        ?.jsonPrimitive?.contentOrNull
                        ?.takeIf(PROVIDER_ID_PATTERN::matches)
                        ?: error("Invalid locations provider")
                    val productId = arguments["productId"]
                        ?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.matches(Regex("""[A-Za-z0-9._-]{1,64}""")) }
                        ?: error("Invalid locations product")
                    val locations = arguments["locations"]?.jsonArray
                        ?.map { item ->
                            item.jsonPrimitive.contentOrNull
                                ?.takeIf { it.isNotBlank() && it.length <= 100 }
                                ?: error("Invalid locations hint")
                        }?.takeIf { it.size <= 20 && it.distinct().size == it.size }
                        ?: error("Invalid locations scope")
                    return AdvisorProxyResult.LocationToolRequest(
                        responseId = responseId,
                        callId = callId,
                        arguments = AdvisorLocationArguments(
                            providerId, productId, locations,
                        ),
                        webSearchCalls = root.requireWebSearchCallCount(),
                        usage = parseUsageOrNull(root["usage"]),
                    )
                }

                val providerId: String
                val branchId: String
                if (toolName == FIND_PRODUCTS) {
                    requireExactKeys(
                        arguments,
                        setOf(
                            "providerId", "branchId",
                            "requestedBranch", "queries",
                        ),
                    )
                    providerId = arguments["providerId"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.takeIf(PROVIDER_ID_PATTERN::matches)
                        ?: error("Invalid tool provider")
                    branchId = arguments["branchId"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.takeIf(BRANCH_ID_PATTERN::matches)
                        ?: error("Invalid tool branch")
                } else {
                    requireExactKeys(
                        arguments,
                        setOf("storeNumber", "queries"),
                    )
                    providerId = OBI_PROVIDER_ID.value
                    branchId = arguments["storeNumber"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.takeIf(STORE_NUMBER_PATTERN::matches)
                        ?: error("Invalid tool store")
                }

                val queries = arguments["queries"]
                    ?.jsonArray
                    ?.map { element ->
                        val requested = element as? JsonObject
                            ?: error("Invalid tool query entry")
                        requireExactKeys(requested, setOf("query", "limit"))
                        val query = requested["query"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.normalizeWhitespace()
                            ?.takeIf {
                                it.isNotBlank() &&
                                    it.length <= MAX_QUERY_CHARS
                            }
                            ?: error("Invalid tool query")
                        val limit = requested["limit"]
                            ?.jsonPrimitive
                            ?.intOrNull
                            ?.takeIf { it in 1..MAX_TOOL_PRODUCTS }
                            ?: error("Invalid tool limit")
                        AdvisorToolQuery(
                            query = query,
                            limit = limit,
                        )
                    }
                    ?.takeIf {
                        it.isNotEmpty() &&
                            it.size <= MAX_TOOL_QUERIES &&
                            it.sumOf(AdvisorToolQuery::limit) <=
                            MAX_TOOL_PRODUCTS
                    }
                    ?: error("Invalid tool queries")

                val requestedBranch = if (toolName == FIND_PRODUCTS) {
                    arguments["requestedBranch"]
                        ?.let { value ->
                            if (value is JsonNull) null else value.jsonPrimitive
                                .contentOrNull
                                ?.normalizeWhitespace()
                                ?.takeIf { it.isNotBlank() && it.length <= 100 }
                                ?: error("Invalid requested branch")
                        }
                } else {
                    null
                }

                AdvisorProxyResult.ToolRequest(
                    responseId = responseId,
                    callId = callId,
                    arguments = AdvisorToolArguments(
                        storeNumber = branchId,
                        queries = queries,
                        providerId = providerId,
                        requestedBranch = requestedBranch,
                    ),
                    webSearchCalls = root.requireWebSearchCallCount(),
                    usage = parseUsageOrNull(root["usage"]),
                )
            }

            else -> error("Unknown response type")
        }
    }

    private fun buildLocationContinueBody(
        responseId: String,
        callId: String,
        providerId: String,
        branchId: String,
        protocolVersion: Int,
        evidence: AdvisorLocationEvidence,
    ): JsonObject = buildJsonObject {
        put("protocolVersion", protocolVersion)
        put("responseId", responseId)
        put("callId", callId)
        if (protocolVersion == OBI_ADVISOR_PROTOCOL_VERSION) {
            put("storeNumber", branchId)
        } else {
            put("providerId", providerId)
            put("branchId", branchId)
        }
        put("tool", FIND_PRODUCT_LOCATIONS)
        put("result", buildJsonObject {
            put("providerId", evidence.providerId)
            if (evidence.productId == null) put("productId", JsonNull)
            else put("productId", evidence.productId)
            put("status", evidence.status)
            if (evidence.reason == null) put("reason", JsonNull)
            else put("reason", evidence.reason)
            put("coverage", evidence.coverage)
            put("checkedIds", buildJsonArray { evidence.checkedIds.forEach { add(JsonPrimitive(it)) } })
            put("returnedIds", buildJsonArray { evidence.returnedIds.forEach { add(JsonPrimitive(it)) } })
            put("missingIds", buildJsonArray { evidence.missingIds.forEach { add(JsonPrimitive(it)) } })
            put("locations", buildJsonArray {
                evidence.locations.forEach { location ->
                    add(buildJsonObject {
                        put("branchId", location.branchId)
                        put("name", location.name.take(100))
                        if (location.stock == null) put("stock", JsonNull)
                        else put("stock", location.stock)
                    })
                }
            })
            if (evidence.verifiedAtMillis == null) put("verifiedAtMillis", JsonNull)
            else put("verifiedAtMillis", evidence.verifiedAtMillis)
            if (evidence.centralStock == null) put("centralStock", JsonNull)
            else put("centralStock", evidence.centralStock)
        })
    }

    private fun buildBudgetedVerifiedContinueBodyV3(
        responseId: String,
        callId: String,
        providerId: String,
        branchId: String,
        result: AdvisorVerifiedToolResult,
        protocolVersion: Int,
    ): JsonObject? {
        var grouped = result.results

        fun currentBody(): JsonObject =
            buildContinueBodyV3(
                responseId = responseId,
                callId = callId,
                providerId = providerId,
                branchId = branchId,
                result = result.copy(results = grouped).toJsonV3(),
                protocolVersion = protocolVersion,
            )

        fun updateProduct(
            groupIndex: Int,
            productIndex: Int,
            transform: (AdvisorVerifiedProduct) -> AdvisorVerifiedProduct,
        ) {
            grouped = grouped.toMutableList().also { groups ->
                val group = groups[groupIndex]
                val products = group.products.toMutableList()
                products[productIndex] = transform(products[productIndex])
                groups[groupIndex] = group.copy(products = products)
            }
        }

        var body = currentBody()
        if (fitsContinueByteBudget(body)) return body

        for (groupIndex in grouped.indices.reversed()) {
            for (productIndex in grouped[groupIndex].products.indices.reversed()) {
                while (
                    grouped[groupIndex]
                        .products[productIndex]
                        .technicalFacts
                        .isNotEmpty()
                ) {
                    updateProduct(groupIndex, productIndex) { product ->
                        product.copy(
                            technicalFacts =
                                product.technicalFacts.dropLast(1),
                        )
                    }
                    body = currentBody()
                    if (fitsContinueByteBudget(body)) return body
                }
            }
        }

        for (groupIndex in grouped.indices.reversed()) {
            for (productIndex in grouped[groupIndex].products.indices.reversed()) {
                if (
                    grouped[groupIndex]
                        .products[productIndex]
                        .shortDescription != null
                ) {
                    updateProduct(groupIndex, productIndex) {
                        it.copy(shortDescription = null)
                    }
                    body = currentBody()
                    if (fitsContinueByteBudget(body)) return body
                }
            }
        }

        for (groupIndex in grouped.indices.reversed()) {
            for (productIndex in grouped[groupIndex].products.indices.reversed()) {
                if (grouped[groupIndex].products[productIndex].brand != null) {
                    updateProduct(groupIndex, productIndex) {
                        it.copy(brand = null)
                    }
                    body = currentBody()
                    if (fitsContinueByteBudget(body)) return body
                }
            }
        }

        return body.takeIf(::fitsContinueByteBudget)
    }

    private fun buildContinueBodyV3(
        responseId: String,
        callId: String,
        providerId: String,
        branchId: String,
        result: JsonObject,
        protocolVersion: Int,
    ): JsonObject =
        buildJsonObject {
            put("protocolVersion", protocolVersion)
            put("responseId", responseId)
            put("callId", callId)
            put("providerId", providerId)
            put("branchId", branchId)
            put("tool", FIND_PRODUCTS)
            put("result", result)
        }

    private fun buildBudgetedVerifiedContinueBody(
        responseId: String,
        callId: String,
        storeNumber: String,
        result: AdvisorVerifiedToolResult,
    ): JsonObject? {
        var grouped = result.results

        fun currentBody(): JsonObject =
            buildContinueBody(
                responseId = responseId,
                callId = callId,
                storeNumber = storeNumber,
                result = result.copy(results = grouped).toJson(),
            )

        fun updateProduct(
            groupIndex: Int,
            productIndex: Int,
            transform: (AdvisorVerifiedProduct) -> AdvisorVerifiedProduct,
        ) {
            grouped = grouped.toMutableList().also { groups ->
                val group = groups[groupIndex]
                val products = group.products.toMutableList()
                products[productIndex] = transform(products[productIndex])
                groups[groupIndex] = group.copy(products = products)
            }
        }

        var body = currentBody()
        if (fitsContinueByteBudget(body)) {
            return body
        }

        for (groupIndex in grouped.indices.reversed()) {
            for (productIndex in grouped[groupIndex].products.indices.reversed()) {
                while (
                    grouped[groupIndex]
                        .products[productIndex]
                        .technicalFacts
                        .isNotEmpty()
                ) {
                    updateProduct(groupIndex, productIndex) { product ->
                        product.copy(
                            technicalFacts =
                                product.technicalFacts.dropLast(1),
                        )
                    }
                    body = currentBody()
                    if (fitsContinueByteBudget(body)) {
                        return body
                    }
                }
            }
        }

        for (groupIndex in grouped.indices.reversed()) {
            for (productIndex in grouped[groupIndex].products.indices.reversed()) {
                if (
                    grouped[groupIndex]
                        .products[productIndex]
                        .shortDescription != null
                ) {
                    updateProduct(groupIndex, productIndex) { product ->
                        product.copy(shortDescription = null)
                    }
                    body = currentBody()
                    if (fitsContinueByteBudget(body)) {
                        return body
                    }
                }
            }
        }

        for (groupIndex in grouped.indices.reversed()) {
            for (productIndex in grouped[groupIndex].products.indices.reversed()) {
                if (
                    grouped[groupIndex]
                        .products[productIndex]
                        .brand != null
                ) {
                    updateProduct(groupIndex, productIndex) { product ->
                        product.copy(brand = null)
                    }
                    body = currentBody()
                    if (fitsContinueByteBudget(body)) {
                        return body
                    }
                }
            }
        }

        return body.takeIf(::fitsContinueByteBudget)
    }

    private fun buildContinueBody(
        responseId: String,
        callId: String,
        storeNumber: String,
        result: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("protocolVersion", OBI_ADVISOR_PROTOCOL_VERSION)
            put("responseId", responseId)
            put("callId", callId)
            put("storeNumber", storeNumber)
            put("tool", FIND_OBI_PRODUCTS)
            put("result", result)
        }

    private fun fitsContinueByteBudget(
        body: JsonObject,
    ): Boolean =
        body.toString()
            .toByteArray(Charsets.UTF_8)
            .size <= MAX_CONTINUE_REQUEST_BYTES

    private fun List<AdvisorToolQuery>.toJson(): JsonArray =
        buildJsonArray {
            forEach { requested ->
                add(
                    buildJsonObject {
                        put("query", requested.query)
                        put("limit", requested.limit)
                    },
                )
            }
        }

    private fun AdvisorVerifiedToolResult.toJsonV3(): JsonObject =
        buildJsonObject {
            put("providerId", providerId)
            put("branchId", storeNumber)
            put(
                "results",
                buildJsonArray {
                    results.forEach { group ->
                        add(
                            buildJsonObject {
                                put("query", group.query)
                                put(
                                    "status",
                                    when (group.status) {
                                        AdvisorQueryResultStatus.VERIFIED ->
                                            "verified"
                                        AdvisorQueryResultStatus.NOT_FOUND ->
                                            "not_found"
                                        AdvisorQueryResultStatus.UNAVAILABLE ->
                                            "unavailable"
                                    },
                                )
                                put(
                                    "products",
                                    buildJsonArray {
                                        group.products.forEach { product ->
                                            add(product.toJsonV3())
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }

    private fun AdvisorVerifiedProduct.toJsonV3(): JsonObject =
        buildJsonObject {
            put("productId", productId)
            put(
                "articleNumber",
                articleNumber?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("name", name)
            put("brand", brand?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "shortDescription",
                shortDescription?.let(::JsonPrimitive) ?: JsonNull,
            )
            put(
                "technicalFacts",
                buildJsonArray {
                    technicalFacts.forEach { fact ->
                        add(
                            buildJsonObject {
                                put("label", fact.label)
                                put("value", fact.value)
                            },
                        )
                    }
                },
            )
            put("stock", stock?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "centralStock",
                centralStock?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("price", price?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "priceScope",
                priceScope?.let(::JsonPrimitive) ?: JsonNull,
            )
        }

    private fun AdvisorVerifiedToolResult.toJson(): JsonObject =
        buildJsonObject {
            put("storeNumber", storeNumber)
            put(
                "results",
                buildJsonArray {
                    results.forEach { group ->
                        add(
                            buildJsonObject {
                                put("query", group.query)
                                put(
                                    "status",
                                    when (group.status) {
                                        AdvisorQueryResultStatus.VERIFIED ->
                                            "verified"
                                        AdvisorQueryResultStatus.NOT_FOUND ->
                                            "not_found"
                                        AdvisorQueryResultStatus.UNAVAILABLE ->
                                            "unavailable"
                                    },
                                )
                                put(
                                    "products",
                                    buildJsonArray {
                                        group.products.forEach { product ->
                                            add(product.toJson())
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }

    private fun AdvisorVerifiedProduct.toJson(): JsonObject =
        buildJsonObject {
            put("obik", obik)
            put("name", name)
            put("brand", brand?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "shortDescription",
                shortDescription?.let(::JsonPrimitive) ?: JsonNull,
            )
            put(
                "technicalFacts",
                buildJsonArray {
                    technicalFacts.forEach { fact ->
                        add(
                            buildJsonObject {
                                put("label", fact.label)
                                put("value", fact.value)
                            },
                        )
                    }
                },
            )
            put("stock", stock?.let(::JsonPrimitive) ?: JsonNull)
            put("price", price?.let(::JsonPrimitive) ?: JsonNull)
        }

    private fun parseUsageOrNull(
        raw: kotlinx.serialization.json.JsonElement?,
    ): AdvisorUsage? =
        runCatching {
            val usage = raw as? JsonObject
                ?: error("Missing usage object")
            requireExactKeys(
                usage,
                setOf(
                    "model",
                    "requestType",
                    "inputTokens",
                    "cachedInputTokens",
                    "cacheWriteTokens",
                    "outputTokens",
                    "reasoningTokens",
                    "totalTokens",
                    "estimatedCostUsd",
                    "pricingVersion",
                ),
            )

            val model = usage["model"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf {
                    it.length in 1..MAX_MODEL_CHARS &&
                        MODEL_PATTERN.matches(it)
                }
                ?: error("Invalid usage model")
            val requestType = usage["requestType"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.let {
                    runCatching {
                        AdvisorRequestType.valueOf(it)
                    }.getOrNull()
                }
                ?: error("Invalid request type")
            val inputTokens = usage.requireTokenCount("inputTokens")
            val cachedInputTokens =
                usage.optionalTokenCount("cachedInputTokens")
            val cacheWriteTokens =
                usage.optionalTokenCount("cacheWriteTokens")
            val outputTokens = usage.requireTokenCount("outputTokens")
            val reasoningTokens =
                usage.optionalTokenCount("reasoningTokens")
            val totalTokens = usage.requireTokenCount("totalTokens")

            require(
                cachedInputTokens == null ||
                    cachedInputTokens <= inputTokens,
            )
            require(
                cacheWriteTokens == null ||
                    cacheWriteTokens <= inputTokens,
            )
            require(
                cachedInputTokens == null ||
                    cacheWriteTokens == null ||
                    cachedInputTokens + cacheWriteTokens <= inputTokens,
            )
            require(
                reasoningTokens == null ||
                    reasoningTokens <= outputTokens,
            )

            val estimatedCostUsd = usage["estimatedCostUsd"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.length <= MAX_MONEY_CHARS }
                ?.toBigDecimalOrNull()
                ?.takeIf { it.signum() >= 0 }
            val pricingVersion = usage["pricingVersion"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf {
                    it.length in 1..MAX_PRICING_VERSION_CHARS
                }

            if (estimatedCostUsd != null) {
                require(pricingVersion != null)
            }

            AdvisorUsage(
                model = model,
                requestType = requestType,
                inputTokens = inputTokens,
                cachedInputTokens = cachedInputTokens,
                cacheWriteTokens = cacheWriteTokens,
                outputTokens = outputTokens,
                reasoningTokens = reasoningTokens,
                totalTokens = totalTokens,
                estimatedCostUsd = estimatedCostUsd,
                pricingVersion = pricingVersion,
            )
        }.getOrNull()

    private fun JsonObject.requireTokenCount(key: String): Long =
        get(key)
            ?.jsonPrimitive
            ?.longOrNull
            ?.takeIf { it in 0..MAX_USAGE_TOKENS }
            ?: error("Invalid token count")

    private fun JsonObject.optionalTokenCount(key: String): Long? {
        val value = get(key) ?: error("Missing token field")
        if (value is JsonNull) return null
        return value.jsonPrimitive.longOrNull
            ?.takeIf { it in 0..MAX_USAGE_TOKENS }
            ?: error("Invalid optional token count")
    }

    private fun parseSourcesOrEmpty(
        raw: kotlinx.serialization.json.JsonElement?,
        answerLength: Int,
    ): List<AdvisorWebSource> {
        if (raw == null || raw is JsonNull) return emptyList()
        val array = raw as? JsonArray ?: error("Invalid sources")
        require(array.size <= MAX_WEB_SOURCES)
        val seen = mutableSetOf<String>()
        return array.mapNotNull { element ->
            val source = element as? JsonObject
                ?: error("Invalid source")
            requireExactKeys(
                source,
                setOf(
                    "title",
                    "url",
                    "startIndex",
                    "endIndex",
                ),
            )
            val title = source["title"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.normalizeWhitespace()
                ?.takeIf {
                    it.isNotBlank() &&
                        it.length <= MAX_WEB_SOURCE_TITLE_CHARS
                }
                ?: error("Invalid source title")
            val url = source["url"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.length <= MAX_WEB_SOURCE_URL_CHARS }
                ?.toHttpUrlOrNull()
                ?.takeIf { it.scheme == "https" }
                ?.toString()
                ?: error("Invalid source URL")
            val startIndex = source.optionalCitationIndex("startIndex")
            val endIndex = source.optionalCitationIndex("endIndex")
            require(
                (startIndex == null && endIndex == null) ||
                    (
                        startIndex != null &&
                            endIndex != null &&
                            startIndex >= 0 &&
                            endIndex > startIndex &&
                            endIndex <= answerLength
                    ),
            )
            if (!seen.add(url)) {
                null
            } else {
                AdvisorWebSource(
                    title = title,
                    url = url,
                    startIndex = startIndex,
                    endIndex = endIndex,
                )
            }
        }
    }

    private fun JsonObject.optionalCitationIndex(
        key: String,
    ): Int? {
        val value = get(key) ?: error("Missing citation index")
        if (value is JsonNull) return null
        return value.jsonPrimitive.intOrNull
            ?: error("Invalid citation index")
    }

    private fun JsonObject.requireWebSearchCallCount(): Long {
        val value = get("webSearchCalls") ?: return 0
        return value.jsonPrimitive.longOrNull
            ?.takeIf { it in 0..MAX_WEB_SEARCH_CALLS }
            ?: error("Invalid web search call count")
    }

    private fun requireEnvelopeKeys(
        objectValue: JsonObject,
        required: Set<String>,
        optional: Set<String> = setOf("usage"),
    ) {
        require(
            objectValue.keys.containsAll(required) &&
                objectValue.keys.all {
                    it in required || it in optional
                },
        )
    }

    private fun requireExactKeys(
        objectValue: JsonObject,
        keys: Set<String>,
    ) {
        require(objectValue.keys == keys)
    }

    private fun String.normalizeWhitespace(): String =
        replace(CONTROL_OR_WHITESPACE, " ").trim()

    private fun isValidProfile(
        providerId: String,
        branchId: String,
    ): Boolean =
        PROVIDER_ID_PATTERN.matches(providerId) &&
            BRANCH_ID_PATTERN.matches(branchId)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val CONTROL_OR_WHITESPACE = Regex("""[\s\p{Cc}]+""")
        val OBIK_PATTERN = Regex("""\d{7}""")
        val STORE_NUMBER_PATTERN = Regex("""\d{3}""")
        val PROVIDER_ID_PATTERN =
            Regex("""[a-z0-9]+(?:-[a-z0-9]+)*""")
        val BRANCH_ID_PATTERN = Regex("""[A-Za-z0-9._-]{1,64}""")
        val PRODUCT_ID_PATTERN = Regex("""[A-Za-z0-9._-]{1,128}""")
        val MODEL_PATTERN = Regex("""[A-Za-z0-9._-]+""")
        val SAFE_PROXY_ERROR_CODES = setOf(
            "unauthorized",
            "server_not_configured",
            "method_not_allowed",
            "request_too_large",
            "unsupported_protocol_version",
            "invalid_request",
            "upstream_failure",
            "not_found",
        )
        const val MAX_PROXY_ERROR_BYTES = 1_024
        const val MAX_PROXY_RESPONSE_BYTES = 64 * 1024
        const val MAX_CONTINUE_REQUEST_BYTES = 16 * 1024 - 1
        const val MAX_ID_CHARS = 256
        const val MAX_QUERY_CHARS = 200
        const val MAX_ANSWER_CHARS = 4_000
        const val MAX_MODEL_CHARS = 100
        const val MAX_PRICING_VERSION_CHARS = 100
        const val MAX_MONEY_CHARS = 32
        const val MAX_USAGE_TOKENS = 10_000_000_000L
        const val MAX_WEB_SEARCH_CALLS = 1L
        const val MAX_WEB_SOURCES = 6
        const val MAX_WEB_SOURCE_TITLE_CHARS = 200
        const val MAX_WEB_SOURCE_URL_CHARS = 2048
    }
}


private class AttachmentByteCountMismatchException :
    IOException("Attachment byte count differs from validated metadata")
