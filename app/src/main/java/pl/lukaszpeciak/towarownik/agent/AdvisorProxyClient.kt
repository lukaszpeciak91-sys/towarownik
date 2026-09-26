package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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

    suspend fun start(message: String): AdvisorProxyCallResult {
        if (!isConfigured()) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.NOT_CONFIGURED,
            )
        }

        val body = buildJsonObject {
            put("message", message)
        }

        return execute(
            endpoint = "v1/agent/start",
            body = body,
        )
    }

    suspend fun continueTurn(
        responseId: String,
        callId: String,
        result: AdvisorVerifiedToolResult,
    ): AdvisorProxyCallResult {
        if (!isConfigured()) {
            return AdvisorProxyCallResult.Failure(
                AdvisorProxyFailureKind.NOT_CONFIGURED,
            )
        }

        val body = buildJsonObject {
            put("responseId", responseId)
            put("callId", callId)
            put("tool", FIND_AVAILABLE_OBI_075)
            put(
                "result",
                buildJsonObject {
                    put("query", result.query)
                    put(
                        "products",
                        buildJsonArray {
                            result.products.forEach { product ->
                                add(product.toJson())
                            }
                        },
                    )
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
                requireExactKeys(root, setOf("type", "responseId", "text"))
                val text = root["text"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_ANSWER_CHARS }
                    ?: error("Invalid answer text")
                AdvisorProxyResult.Answer(
                    responseId = responseId,
                    text = text,
                )
            }

            "tool_request" -> {
                requireExactKeys(root, setOf("type", "responseId", "tool"))
                val tool = root["tool"] as? JsonObject
                    ?: error("Missing tool")
                requireExactKeys(
                    tool,
                    setOf("name", "callId", "arguments"),
                )
                require(
                    tool["name"]?.jsonPrimitive?.contentOrNull ==
                        FIND_AVAILABLE_OBI_075,
                )
                val callId = tool["callId"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_ID_CHARS }
                    ?: error("Invalid call id")
                val arguments = tool["arguments"] as? JsonObject
                    ?: error("Missing tool arguments")
                requireExactKeys(arguments, setOf("query", "limit"))
                val query = arguments["query"]?.jsonPrimitive?.contentOrNull
                    ?.normalizeWhitespace()
                    ?.takeIf { it.isNotBlank() && it.length <= MAX_QUERY_CHARS }
                    ?: error("Invalid tool query")
                val limit = arguments["limit"]?.jsonPrimitive?.intOrNull
                    ?.takeIf { it in 1..MAX_TOOL_PRODUCTS }
                    ?: error("Invalid tool limit")

                AdvisorProxyResult.ToolRequest(
                    responseId = responseId,
                    callId = callId,
                    arguments = AdvisorToolArguments(
                        query = query,
                        limit = limit,
                    ),
                )
            }

            else -> error("Unknown response type")
        }
    }

    private fun AdvisorVerifiedProduct.toJson(): JsonObject =
        buildJsonObject {
            put("obik", obik)
            put("name", name)
            put("stock", stock?.let(::JsonPrimitive) ?: JsonNull)
            put("price", price?.let(::JsonPrimitive) ?: JsonNull)
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
        const val MAX_PROXY_RESPONSE_BYTES = 64 * 1024
        const val MAX_ID_CHARS = 256
        const val MAX_QUERY_CHARS = 200
        const val MAX_ANSWER_CHARS = 4_000
    }
}
