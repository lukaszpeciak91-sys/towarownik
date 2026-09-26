package pl.lukaszpeciak.towarownik

import androidx.compose.runtime.saveable.Saver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal enum class ChatMessageRole {
    USER,
    ASSISTANT,
}

internal data class AdvisorChatMessage(
    val role: ChatMessageRole,
    val text: String,
    val createdAt: Long,
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

internal val AdvisorCaseUiStateSaver = Saver<AdvisorCaseUiState, String>(
    save = ::encodeAdvisorCase,
    restore = ::decodeAdvisorCase,
)

internal fun normalizeAdvisorDisplayText(raw: String): String =
    raw
        .replace(MARKDOWN_HEADING, "")
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
        .trim()

private fun encodeAdvisorCase(state: AdvisorCaseUiState): String =
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
                        },
                    )
                }
            },
        )
    }.toString()

private fun decodeAdvisorCase(raw: String): AdvisorCaseUiState =
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
                AdvisorChatMessage(
                    role = role,
                    text = text,
                    createdAt = createdAt,
                )
            }
            .orEmpty()

        AdvisorCaseUiState(
            draft = draft,
            messages = messages,
        )
    }.getOrDefault(AdvisorCaseUiState())

private val MARKDOWN_HEADING = Regex(
    pattern = """(?m)^\s*#{1,6}\s+""",
)
