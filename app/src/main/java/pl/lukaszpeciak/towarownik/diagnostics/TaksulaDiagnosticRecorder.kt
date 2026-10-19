package pl.lukaszpeciak.towarownik.diagnostics

import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Safe, session-only operational evidence. Never stores prompts, product IDs,
 * payloads, attachment data, cookies, URLs, headers, or exception messages.
 * The bounded history is independent of the optional detailed OBI recorder.
 */
internal enum class DiagnosticSource { ADVISOR, PROXY, MANUAL }
internal enum class DiagnosticStage {
    TURN_START, TOOL_START, TOOL_RESULT, TURN_SUCCESS, TURN_FAILURE,
    PROXY_RESPONSE, PROXY_FAILURE, SEARCH_START, SEARCH_RESULT, SEARCH_FAILURE,
}

internal data class SafeDiagnosticEvent(
    val source: DiagnosticSource,
    val stage: DiagnosticStage,
    val providerId: String? = null,
    val branchId: String? = null,
    val turnId: String? = null,
    val traceId: String? = null,
    val endpoint: String? = null,
    val httpStatus: Int? = null,
    val failureKind: String? = null,
    val failureClass: String? = null,
    val tool: String? = null,
    val outcome: String? = null,
    val reason: String? = null,
    val elapsedMillis: Long? = null,
    val conversationId: Long? = null,
)

internal class SafeDiagnosticRecorder(
    private val maxEvents: Int = 64,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { require(maxEvents in 1..256) }

    private val events = ArrayDeque<Pair<Long, SafeDiagnosticEvent>>()
    private val assistantTurns = LinkedHashMap<Pair<Long, Long>, String>()
    private val invalidatedConversations = LinkedHashSet<Long>()
    private val guid = Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
    private val failureKinds = setOf("NOT_CONFIGURED", "AUTHENTICATION", "NETWORK", "SERVICE", "PROTOCOL", "PRODUCT_PROVIDER")
    private val failureClasses = setOf("DNS", "TLS", "TIMEOUT", "NETWORK_IO", "HTTP_ERROR", "RESPONSE_PARSE", "LOCAL_PROVIDER_FAILURE")
    private val outcomes = setOf("verified", "unavailable", "rejected", "success", "error", "results", "product", "unknown")
    private val tools = setOf("find_obi_products", "find_products", "find_product_locations")
    private val reasons = setOf(
        "untrusted_product", "ambiguous_product", "unknown_location", "ambiguous_location",
        "location_intent_required", "location_not_authorized", "unsupported_provider",
        "local_tool_limit_reached", "location_service_unavailable", "multi_product_tool_contract",
        "directory_unavailable", "transport", "malformed_response", "tool_rejected",
        "not_found", "data_unavailable", "network", "timeout", "invalid_request",
    )

    @Synchronized
    fun record(value: SafeDiagnosticEvent) {
        if (value.conversationId in invalidatedConversations) return
        // Validate every dynamic field at the write boundary; never print arbitrary caller text.
        val safe = value.copy(
            providerId = value.providerId?.takeIf { it in setOf("obi-pl", "kwant-pl") },
            branchId = value.branchId?.takeIf { Regex("^[0-9]{1,3}$").matches(it) },
            turnId = value.turnId?.takeIf(guid::matches)?.lowercase(),
            traceId = value.traceId?.takeIf(guid::matches)?.lowercase(),
            endpoint = value.endpoint?.takeIf { it in setOf("start", "message", "continue") },
            httpStatus = value.httpStatus?.takeIf { it in 100..599 },
            failureKind = value.failureKind?.takeIf(failureKinds::contains),
            failureClass = value.failureClass?.takeIf(failureClasses::contains),
            tool = value.tool?.takeIf(tools::contains),
            outcome = value.outcome?.takeIf(outcomes::contains),
            reason = value.reason?.let { if (it in reasons) it else "unrecognized" },
            elapsedMillis = value.elapsedMillis?.takeIf { it in 0..600_000 },
            conversationId = value.conversationId?.takeIf { it > 0L },
        )
        events.addLast(clock() to safe)
        while (events.size > maxEvents) events.removeFirst()
    }

    /** Link only AFTER the assistant response has been saved and its true ID returned. */
    @Synchronized
    fun linkAssistantMessage(conversationId: Long, turnId: String, messageId: Long) {
        if (conversationId <= 0 || messageId <= 0 || !guid.matches(turnId)) return
        if (events.none { it.second.conversationId == conversationId && it.second.turnId == turnId }) return
        assistantTurns[conversationId to messageId] = turnId.lowercase()
        while (assistantTurns.size > maxEvents) assistantTurns.remove(assistantTurns.keys.first())
    }

    @Synchronized
    fun reportForMessage(conversationId: Long, messageId: Long): String {
        val turn = assistantTurns[conversationId to messageId]
            ?: return missingScopedEvidence()
        return reportForTurn(conversationId, turn)
    }

    @Synchronized
    fun reportForTurn(conversationId: Long, turnId: String): String {
        if (conversationId <= 0 || !guid.matches(turnId)) return missingScopedEvidence()
        val selected = events.filter { (_, e) ->
            e.conversationId == conversationId && e.turnId == turnId.lowercase()
        }
        return if (selected.isEmpty()) missingScopedEvidence()
        else render(selected, "scope=targeted turn; other conversations and turns excluded")
    }

    @Synchronized
    fun clearConversation(conversationId: Long) {
        if (conversationId <= 0) return
        events.removeAll { it.second.conversationId == conversationId }
        assistantTurns.keys.removeAll { it.first == conversationId }
        // A late callback from an already cancelled/deleted conversation
        // must not repopulate the global session record after deletion.
        invalidatedConversations.add(conversationId)
        while (invalidatedConversations.size > 128) {
            invalidatedConversations.remove(invalidatedConversations.first())
        }
    }

    private fun missingScopedEvidence() =
        "Taksula diagnostics - targeted turn\nscope=targeted turn\nevidence=unavailable (not captured, expired or evicted); unrelated session events excluded.\n"

    @Synchronized fun hasEvents(): Boolean = events.isNotEmpty()
    @Synchronized fun clear() { events.clear(); assistantTurns.clear() }

    @Synchronized
    fun report(): String = render(events.toList(),
        "scope=session-wide across all conversations; not attributable to a specific report turn")

    private fun render(selected: List<Pair<Long, SafeDiagnosticEvent>>, scope: String): String = buildString {
        appendLine("Taksula diagnostics - safe session events")
        appendLine("capture=automatic metadata-only")
        appendLine(scope)
        appendLine("retention=last ${selected.size}/$maxEvents events")
        appendLine("not_captured=prompts, product identifiers, attachments, raw HTTP, cookies, secrets, Worker internals")
        if (selected.isEmpty()) appendLine("No captured events; unavailable is not evidence of success.")
        selected.forEachIndexed { index, (time, event) ->
            append("event=${index + 1} at=${Instant.ofEpochMilli(time)} source=${event.source} stage=${event.stage}")
            event.providerId?.let { append(" provider=$it") }
            event.branchId?.let { append(" branch=$it") }
            event.turnId?.let { append(" turn=$it") }
            event.traceId?.let { append(" trace=$it") }
            event.endpoint?.let { append(" endpoint=$it") }
            event.httpStatus?.let { append(" http=$it") }
            event.failureKind?.let { append(" failure=$it") }
            event.failureClass?.let { append(" failureClass=$it") }
            event.tool?.let { append(" tool=$it") }
            event.outcome?.let { append(" outcome=$it") }
            event.reason?.let { append(" reason=$it") }
            event.elapsedMillis?.let { append(" durationMs=$it") }
            appendLine()
        }
    }
}

/** Immutable local correlation carried by each Advisor coroutine, not a global currentTurnId.
 *  The HTTP callback captures this value before leaving the originating coroutine.
 */
internal class AdvisorDiagnosticTurnContext(
    val conversationId: Long,
    val turnId: String,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AdvisorDiagnosticTurnContext>
}

internal object TaksulaDiagnostics {
    val recorder = SafeDiagnosticRecorder()
}
