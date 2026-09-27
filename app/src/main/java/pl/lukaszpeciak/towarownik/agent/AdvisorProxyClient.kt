package pl.lukaszpeciak.towarownik.agent

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import pl.lukaszpeciak.towarownik.BuildConfig
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER

internal class AdvisorProxyClient(
    private val appToken: String = BuildConfig.TOWAROWNIK_APP_TOKEN,
    private val baseUrl: HttpUrl = ADVISOR_PROXY_BASE_URL.toHttpUrl(),
    client: OkHttpClient? = null,
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
            put("message", message)
            put("storeNumber", storeNumber)
        }

        return execute(
            endpoint = "v1/agent/start",
            body = body,
        )
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
            put("previousResponseId", previousResponseId)
            put("message", message)
            put("storeNumber", storeNumber)
        }

        return execute(
            endpoint = "v1/agent/message",
            body = body,
        )
    }

    suspend fun continueTurn(
        responseId: String,
        callId: String,
        storeNumber: String,
        continuation: AdvisorToolContinuation,
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
            put("responseId", responseId)
            put("callId", callId)
            put("storeNumber", storeNumber)
            put("tool", FIND_OBI_PRODUCTS)
            put(
                "result",
                when (continuation) {
                    is AdvisorToolContinuation.Verified ->
                        continuation.result.toJson()
                    is AdvisorToolContinuation.RejectedStore ->
                        buildJsonObject {
                            put("query", continuation.query)
                            put(
                                "storeNumber",
                                continuation.storeNumber,
                            )
                            put(
                                "rejection",
                                "store_not_authorized",
                            )
                        }
                },
            )
        }

        return execute(
            endpoint = "v1/agent/continue",
            body = body,
        )
    }

    private suspend fun execute(
        endpoint: String,
        body: JsonObject,
    ): AdvisorProxyCallResult {
        val url = baseUrl.newBuilder()
            .addPathSegments(endpoint)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Authorization", "Bearer $appToken")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .build()

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
                            mapResponse(it)
                        }
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                },
            )
        }
    }

    private fun mapResponse(response: Response): AdvisorProxyCallResult {
        if (response.code == 401) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.AUTHENTICATION,
            )
        }
        if (!response.isSuccessful) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.SERVICE,
            )
        }

        val preview = runCatching {
            response.peekBody(MAX_PROXY_RESPONSE_BYTES + 1L).bytes()
        }.getOrNull()
            ?: return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
            )

        if (preview.isEmpty() || preview.size > MAX_PROXY_RESPONSE_BYTES) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
            )
        }

        val text = runCatching {
            preview.toString(Charsets.UTF_8)
        }.getOrNull()
            ?: return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
            )

        val result = runCatching {
            parseEnvelope(text)
        }.getOrNull()
            ?: return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.PROTOCOL,
            )

        return AdvisorProxyCallResult.Success(result)
    }

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
                        requireExactKeys(
                            reference,
                            setOf("storeNumber", "obik"),
                        )
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
                    ?.takeIf { it.size <= MAX_TOOL_PRODUCTS }
                    ?: error("Invalid selected products")
                AdvisorProxyResult.Answer(
                    responseId = responseId,
                    text = text,
                    productRefs = productRefs.distinctBy { it.key },
                    usage = parseUsageOrNull(root["usage"]),
                )
            }

            "tool_request" -> {
                requireEnvelopeKeys(
                    root,
                    required = setOf("type", "responseId", "tool"),
                )
                val tool = root["tool"] as? JsonObject
                    ?: error("Missing tool")
                requireExactKeys(
                    tool,
                    setOf("name", "callId", "arguments"),
                )
                require(
                    tool["name"]?.jsonPrimitive?.contentOrNull ==
                        FIND_OBI_PRODUCTS,
                )
                val callId = tool["callId"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS }
                    ?: error("Invalid call id")
                val arguments = tool["arguments"] as? JsonObject
                    ?: error("Missing tool arguments")
                requireExactKeys(arguments, setOf("query", "storeNumber", "limit"))
                val query = arguments["query"]?.jsonPrimitive?.contentOrNull
                    ?.normalizeWhitespace()
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_QUERY_CHARS }
                    ?: error("Invalid tool query")
                val storeNumber = arguments["storeNumber"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf(STORE_NUMBER_PATTERN::matches)
                    ?: error("Invalid tool store")
                val limit = arguments["limit"]?.jsonPrimitive?.intOrNull
                    ?.takeIf { it in 1..MAX_TOOL_PRODUCTS }
                    ?: error("Invalid tool limit")

                AdvisorProxyResult.ToolRequest(
                    responseId = responseId,
                    callId = callId,
                    arguments = AdvisorToolArguments(
                        query = query,
                        storeNumber = storeNumber,
                        limit = limit,
                    ),
                    usage = parseUsageOrNull(root["usage"]),
                )
            }

            else -> error("Unknown response type")
        }
    }

    private fun AdvisorVerifiedToolResult.toJson(): JsonObject =
        buildJsonObject {
            put("query", query)
            put("storeNumber", storeNumber)
            put(
                "products",
                buildJsonArray {
                    products.forEach { product ->
                        add(product.toJson())
                    }
                },
            )
        }

    private fun AdvisorVerifiedProduct.toJson(): JsonObject =
        buildJsonObject {
            put("obik", obik)
            put("name", name)
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

    private fun requireEnvelopeKeys(
        objectValue: JsonObject,
        required: Set<String>,
    ) {
        require(
            objectValue.keys == required ||
                objectValue.keys == required + "usage",
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

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val CONTROL_OR_WHITESPACE = Regex("""[\s\p{Cc}]+""")
        val OBIK_PATTERN = Regex("""\d{7}""")
        val STORE_NUMBER_PATTERN = Regex("""\d{3}""")
        val MODEL_PATTERN = Regex("""[A-Za-z0-9._-]+""")
        const val MAX_PROXY_RESPONSE_BYTES = 64 * 1024
        const val MAX_ID_CHARS = 256
        const val MAX_QUERY_CHARS = 200
        const val MAX_ANSWER_CHARS = 4_000
        const val MAX_MODEL_CHARS = 100
        const val MAX_PRICING_VERSION_CHARS = 100
        const val MAX_MONEY_CHARS = 32
        const val MAX_USAGE_TOKENS = 10_000_000_000L
    }
}
