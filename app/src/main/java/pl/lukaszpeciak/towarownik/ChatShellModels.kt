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
import pl.lukaszpeciak.towarownik.conversation.PersistedSearchAction
import pl.lukaszpeciak.towarownik.conversation.PersistedWebSource
import pl.lukaszpeciak.towarownik.agent.AdvisorUiState
import pl.lukaszpeciak.towarownik.agent.AdvisorWebSource
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope

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
    val searchActions: List<PersistedSearchAction> = emptyList(),
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

            val display = if (role == ChatMessageRole.ASSISTANT) {
                normalizePersistedAdvisorDisplay(
                    raw = message.text,
                    sources = message.sources,
                )
            } else {
                NormalizedAdvisorDisplay(
                    text = message.text,
                    sources = message.sources,
                )
            }

            AdvisorChatMessage(
                role = role,
                text = display.text,
                createdAt = message.createdAt,
                products = message.products.map { product ->
                    product.toVerifiedProductUiModel()
                },
                sources = display.sources,
                searchActions = message.searchActions,
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

internal fun normalizeAdvisorDisplayText(
    raw: String,
): String =
    normalizeAdvisorText(raw).text

internal data class NormalizedAdvisorDisplay(
    val text: String,
    val sources: List<PersistedWebSource>,
)

internal fun normalizeAdvisorDisplay(
    raw: String,
    sources: List<AdvisorWebSource>,
): NormalizedAdvisorDisplay {
    val normalized = normalizeAdvisorText(raw)
    return NormalizedAdvisorDisplay(
        text = normalized.text,
        sources = sources.mapNotNull { source ->
            val mappedRange = remapAdvisorSourceRange(
                rawLength = raw.length,
                startIndex = source.startIndex,
                endIndex = source.endIndex,
                normalized = normalized,
            )
            pl.lukaszpeciak.towarownik.conversation
                .persistedWebSourceOrNull(
                    title = source.title,
                    url = source.url,
                    startIndex = mappedRange?.first,
                    endIndex = mappedRange?.second,
                )
        },
    )
}

internal fun normalizePersistedAdvisorDisplay(
    raw: String,
    sources: List<PersistedWebSource>,
): NormalizedAdvisorDisplay {
    val normalized = normalizeAdvisorText(raw)
    return NormalizedAdvisorDisplay(
        text = normalized.text,
        sources = sources.mapNotNull { source ->
            val mappedRange = remapAdvisorSourceRange(
                rawLength = raw.length,
                startIndex = source.startIndex,
                endIndex = source.endIndex,
                normalized = normalized,
            )
            pl.lukaszpeciak.towarownik.conversation
                .persistedWebSourceOrNull(
                    title = source.title,
                    url = source.url,
                    startIndex = mappedRange?.first,
                    endIndex = mappedRange?.second,
                )
        },
    )
}

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
                                                put(
                                                    "startIndex",
                                                    source.startIndex
                                                        ?.let(::JsonPrimitive)
                                                        ?: JsonNull,
                                                )
                                                put(
                                                    "endIndex",
                                                    source.endIndex
                                                        ?.let(::JsonPrimitive)
                                                        ?: JsonNull,
                                                )
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
                                "searchActions",
                                buildJsonArray {
                                    message.searchActions.forEach { action ->
                                        add(
                                            buildJsonObject {
                                                put("query", action.query)
                                                put("storeNumber", action.storeNumber)
                                                put(
                                                    "reportedTotalCount",
                                                    action.reportedTotalCount,
                                                )
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
                                                put(
                                                    "primaryImageUrl",
                                                    product.primaryImageUrl
                                                        ?.let(::JsonPrimitive)
                                                        ?: JsonNull,
                                                )
                                                put("storeNumber", product.storeNumber)
                                                put("providerId", product.providerId)
                                                put("productId", product.productId)
                                                put("branchId", product.branchId)
                                                put(
                                                    "articleNumber",
                                                    product.articleNumber
                                                        ?.let(::JsonPrimitive)
                                                        ?: JsonNull,
                                                )
                                                put(
                                                    "priceScope",
                                                    product.priceScope
                                                        ?.name
                                                        ?.lowercase()
                                                        ?.let(::JsonPrimitive)
                                                        ?: JsonNull,
                                                )
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
                        val startIndex = source["startIndex"]
                            ?.let { value ->
                                if (value is JsonNull) null
                                else value.jsonPrimitive.intOrNull
                            }
                        val endIndex = source["endIndex"]
                            ?.let { value ->
                                if (value is JsonNull) null
                                else value.jsonPrimitive.intOrNull
                            }
                        pl.lukaszpeciak.towarownik.conversation
                            .persistedWebSourceOrNull(
                                title = title,
                                url = url,
                                startIndex = startIndex,
                                endIndex = endIndex,
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
                            primaryImageUrl =
                                product["primaryImageUrl"]
                                    ?.let { value ->
                                        if (value is JsonNull) null
                                        else value.jsonPrimitive.contentOrNull
                                    },
                            storeNumber = product["storeNumber"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?: DEFAULT_OBI_STORE_NUMBER,
                            verifiedAt = product["verifiedAt"]
                                ?.jsonPrimitive
                                ?.longOrNull,
                            providerId = product["providerId"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?: "obi-pl",
                            productId = product["productId"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?: obik,
                            branchId = product["branchId"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?: (
                                    product["storeNumber"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                        ?: DEFAULT_OBI_STORE_NUMBER
                                ),
                            articleNumber = product["articleNumber"]
                                ?.let { value ->
                                    if (value is JsonNull) null
                                    else value.jsonPrimitive.contentOrNull
                                },
                            priceScope = product["priceScope"]
                                ?.let { value ->
                                    if (value is JsonNull) {
                                        null
                                    } else {
                                        when (value.jsonPrimitive.contentOrNull) {
                                            "branch" -> ProviderPriceScope.BRANCH
                                            "online" -> ProviderPriceScope.ONLINE
                                            else -> null
                                        }
                                    }
                                },
                        )
                    }
                    .orEmpty()
                val searchActions =
                    (objectValue["searchActions"] as? JsonArray)
                        ?.mapNotNull { actionElement ->
                            val action = actionElement as? JsonObject
                                ?: return@mapNotNull null
                            val query = action["query"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?: return@mapNotNull null
                            val storeNumber = action["storeNumber"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                                ?: return@mapNotNull null
                            val reportedTotalCount =
                                action["reportedTotalCount"]
                                    ?.jsonPrimitive
                                    ?.intOrNull
                                    ?: return@mapNotNull null
                            pl.lukaszpeciak.towarownik.conversation
                                .persistedSearchActionOrNull(
                                    query = query,
                                    storeNumber = storeNumber,
                                    reportedTotalCount =
                                        reportedTotalCount,
                                )
                        }
                        .orEmpty()
                AdvisorChatMessage(
                    role = role,
                    text = text,
                    createdAt = createdAt,
                    products = products,
                    sources = sources,
                    searchActions = searchActions,
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

private data class AdvisorTextNormalization(
    val text: String,
    val boundaryMap: IntArray,
    val leadingTrim: Int,
    val trailingBoundary: Int,
)

private fun remapAdvisorSourceRange(
    rawLength: Int,
    startIndex: Int?,
    endIndex: Int?,
    normalized: AdvisorTextNormalization,
): Pair<Int, Int>? {
    if (
        startIndex == null ||
        endIndex == null ||
        startIndex !in 0..rawLength ||
        endIndex !in 0..rawLength ||
        endIndex <= startIndex
    ) {
        return null
    }

    val start = normalized.boundaryMap[startIndex]
    val end = normalized.boundaryMap[endIndex]
    if (
        start < normalized.leadingTrim ||
        end > normalized.trailingBoundary ||
        end <= start
    ) {
        return null
    }

    return (start - normalized.leadingTrim) to
        (end - normalized.leadingTrim)
}

private fun normalizeAdvisorText(
    raw: String,
): AdvisorTextNormalization {
    val removed = BooleanArray(raw.length)

    fun removeRange(start: Int, endExclusive: Int) {
        for (
            index in start until
                endExclusive.coerceAtMost(raw.length)
        ) {
            removed[index] = true
        }
    }

    MARKDOWN_HEADING.findAll(raw).forEach { match ->
        removeRange(
            start = match.range.first,
            endExclusive = match.range.last + 1,
        )
    }

    listOf("**", "__", "`").forEach { marker ->
        var searchFrom = 0
        while (searchFrom < raw.length) {
            val index = raw.indexOf(
                marker,
                startIndex = searchFrom,
            )
            if (index < 0) break
            removeRange(index, index + marker.length)
            searchFrom = index + marker.length
        }
    }

    listOf(
        MARKDOWN_BRACKET_LINK,
        MARKDOWN_PAREN_LINK,
    ).forEach { pattern ->
        pattern.findAll(raw).forEach matchLoop@ { match ->
            val labelRange =
                match.groups[1]?.range
                    ?: return@matchLoop
            removeRange(
                match.range.first,
                labelRange.first,
            )
            removeRange(
                labelRange.last + 1,
                match.range.last + 1,
            )
        }
    }

    val boundaryMap = IntArray(raw.length + 1)
    val untrimmed = StringBuilder(raw.length)
    var outputIndex = 0
    for (index in raw.indices) {
        boundaryMap[index] = outputIndex
        if (!removed[index]) {
            untrimmed.append(raw[index])
            outputIndex += 1
        }
    }
    boundaryMap[raw.length] = outputIndex

    val untrimmedText = untrimmed.toString()
    val text = untrimmedText.trim()
    val leadingTrim =
        untrimmedText.length -
            untrimmedText.trimStart().length
    val trailingBoundary = leadingTrim + text.length

    return AdvisorTextNormalization(
        text = text,
        boundaryMap = boundaryMap,
        leadingTrim = leadingTrim,
        trailingBoundary = trailingBoundary,
    )
}

private val MARKDOWN_BRACKET_LINK = Regex(
    pattern = """\[([^\]\n]+)]\((https://[^)\s]+)\)""",
)

private val MARKDOWN_PAREN_LINK = Regex(
    pattern = """\(([^()\n]+)\)\((https://[^)\s]+)\)""",
)

private val MARKDOWN_HEADING = Regex(
    pattern = """(?m)^\s*#{1,6}\s+""",
)
