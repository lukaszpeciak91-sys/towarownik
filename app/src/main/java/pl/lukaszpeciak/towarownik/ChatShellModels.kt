package pl.lukaszpeciak.towarownik

import androidx.compose.runtime.saveable.Saver
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import pl.lukaszpeciak.towarownik.conversation.MESSAGE_ROLE_ASSISTANT
import pl.lukaszpeciak.towarownik.conversation.MESSAGE_ROLE_USER
import pl.lukaszpeciak.towarownik.conversation.PersistedConversation
import pl.lukaszpeciak.towarownik.conversation.PersistedWebSource
import pl.lukaszpeciak.towarownik.agent.AdvisorUiState
import pl.lukaszpeciak.towarownik.agent.AdvisorWebSource
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER

internal enum class ChatMessageRole {
    USER,
    ASSISTANT,
}

internal data class AdvisorChatMessage(
    val role: ChatMessageRole,
    val text: String,
    val createdAt: Long,
    val products: List<VerifiedProductUiModel> = emptyList(),
    val sources: List<PersistedWebSource> = emptyList(),
    val persistedMessageId: Long? = null,
)

internal data class AdvisorCaseUiState(
    val draft: String = "",
    val messages: List<AdvisorChatMessage> = emptyList(),
) {
    fun newCase(): AdvisorCaseUiState = AdvisorCaseUiState()

    fun withDraft(value: String): AdvisorCaseUiState =
        copy(draft = value)

    fun withMessage(message: AdvisorChatMessage): AdvisorCaseUiState =
        copy(messages = messages + message)
}


internal fun PersistedConversation.toAdvisorCaseUiState(): AdvisorCaseUiState =
    AdvisorCaseUiState(
        draft = draft,
        messages = messages.mapNotNull { message ->
            val role = when (message.role) {
                MESSAGE_ROLE_USER -> ChatMessageRole.USER
                MESSAGE_ROLE_ASSISTANT -> ChatMessageRole.ASSISTANT
                else -> null
            } ?: return@mapNotNull null

            AdvisorChatMessage(
                role = role,
                text = message.text,
                createdAt = message.createdAt,
                products = message.products.map { product ->
                    product.toVerifiedProductUiModel()
                },
                sources = message.sources,
                sources = message.sources.map {
                    AdvisorWebSource(
                        title = it.title,
                        url = it.url,
                    )
                },
                persistedMessageId = message.id,
            )
        },
    )

internal val AdvisorCaseUiStateSaver = Saver<AdvisorCaseUiState, String>(
    save = { state -> saveAdvisorCase(state) },
    restore = { raw -> restoreAdvisorCase(raw) },
)

internal fun isAdvisorComposerEnabled(
    state: AdvisorUiState,
): Boolean =
    state !is AdvisorUiState.LoadingProxy &&
        state !is AdvisorUiState.RunningLocalTool &&
        state !is AdvisorUiState.WaitingForFinalAnswer

internal fun normalizeAdvisorDisplayText(raw: String): String =
    raw
        .replace(MARKDOWN_HEADING, "")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()

internal fun saveAdvisorCase(state: AdvisorCaseUiState): String =
    buildJsonObject {
        put("draft", state.draft)
        put(
            "messages",
            buildJsonArray {
                state.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role.name)
                            put("text", message.text)
                            put("createdAt", message.createdAt)
                            put(
                                "sources",
                                buildJsonArray {
                                    message.sources.forEach { source ->
                                        add(
                                            buildJsonObject {
                                                put("title", source.title)
                                                put("url", source.url)
                                            },
                                        )
                                    }
                                },
                            )
                            put(
                                "persistedMessageId",
                                message.persistedMessageId
                                    ?.let(::JsonPrimitive)
                                    ?: JsonNull,
                            )
                            put(
                                "sources",
                                buildJsonArray {
                                    message.sources.forEach { source ->
                                        add(
                                            buildJsonObject {
                                                put("title", source.title)
                                                put("url", source.url)
                                            },
                                        )
                                    }
                                },
                            )
                            put(
                                "products",
                                buildJsonArray {
                                    message.products.forEach { product ->
                                        add(
                                            buildJsonObject {
                                                put("name", product.name)
                                                put("obik", product.obik)
                                                put(
                                                    "grossPrice",
                                                    product.grossPrice
                                                        ?.toPlainString()
                                                        ?.let(::JsonPrimitive)
                                                        ?: JsonNull,
                                                )
                                                put(
                                                    "stock",
                                                    product.stock
                                                        ?.let(::JsonPrimitive)
                                                        ?: JsonNull,
                                                )
                                                put("productUrl", product.productUrl)
                                                put("storeNumber", product.storeNumber)
                                                put(
                                                    "verifiedAt",
                                                    product.verifiedAt
                                                        ?.let(::JsonPrimitive)
                                                        ?: JsonNull,
                                                )
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
    }.toString()

internal fun restoreAdvisorCase(raw: String): AdvisorCaseUiState =
    runCatching {
        val root = Json.parseToJsonElement(raw) as JsonObject
        val draft = root["draft"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val messages = (root["messages"] as? JsonArray)
            ?.mapNotNull { element ->
                val objectValue = element as? JsonObject ?: return@mapNotNull null
                val role = objectValue["role"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let { runCatching { ChatMessageRole.valueOf(it) }.getOrNull() }
                    ?: return@mapNotNull null
                val text = objectValue["text"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?: return@mapNotNull null
                val createdAt = objectValue["createdAt"]
                    ?.jsonPrimitive
                    ?.longOrNull
                    ?: return@mapNotNull null
                val sources = (objectValue["sources"] as? JsonArray)
                    ?.mapNotNull { sourceElement ->
                        val source = sourceElement as? JsonObject
                            ?: return@mapNotNull null
                        val title = source["title"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?: return@mapNotNull null
                        val url = source["url"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?: return@mapNotNull null
                        pl.lukaszpeciak.towarownik.conversation
                            .persistedWebSourceOrNull(
                                title = title,
                                url = url,
                            )
                            ?: return@mapNotNull null
                    }
                    .orEmpty()
                val products = (objectValue["products"] as? JsonArray)
                    ?.mapNotNull { productElement ->
                        val product = productElement as? JsonObject
                            ?: return@mapNotNull null
                        val name = product["name"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?: return@mapNotNull null
                        val obik = product["obik"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?: return@mapNotNull null
                        val productUrl = product["productUrl"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?: return@mapNotNull null
                        VerifiedProductUiModel(
                            name = name,
                            obik = obik,
                            grossPrice = product["grossPrice"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?.toBigDecimalOrNull(),
                            stock = product["stock"]
                                ?.jsonPrimitive
                                ?.intOrNull,
                            productUrl = productUrl,
                            storeNumber = product["storeNumber"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?: DEFAULT_OBI_STORE_NUMBER,
                            verifiedAt = product["verifiedAt"]
                                ?.jsonPrimitive
                                ?.longOrNull,
                        )
                    }
                    .orEmpty()
                val sources = (objectValue["sources"] as? JsonArray)
                    ?.mapNotNull { sourceElement ->
                        val source = sourceElement as? JsonObject
                            ?: return@mapNotNull null
                        val title = source["title"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?: return@mapNotNull null
                        val url = source["url"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?: return@mapNotNull null
                        AdvisorWebSource(
                            title = title,
                            url = url,
                        )
                    }
                    .orEmpty()
                AdvisorChatMessage(
                    role = role,
                    text = text,
                    createdAt = createdAt,
                    products = products,
                    sources = sources,
                    sources = sources,
                    persistedMessageId = objectValue["persistedMessageId"]
                        ?.jsonPrimitive
                        ?.longOrNull,
                )
            }
            .orEmpty()

        recoverInterruptedAdvisorCase(
            AdvisorCaseUiState(
                draft = draft,
                messages = messages,
            ),
        )
    }.getOrDefault(AdvisorCaseUiState())


internal fun recoverInterruptedAdvisorCase(
    state: AdvisorCaseUiState,
): AdvisorCaseUiState {
    val lastMessage = state.messages.lastOrNull()
    return if (lastMessage?.role == ChatMessageRole.USER) {
        state.copy(
            draft = lastMessage.text,
            messages = state.messages.dropLast(1),
        )
    } else {
        state
    }
}

private val MARKDOWN_HEADING = Regex(
    pattern = """(?m)^\s*#{1,6}\s+""",
)
