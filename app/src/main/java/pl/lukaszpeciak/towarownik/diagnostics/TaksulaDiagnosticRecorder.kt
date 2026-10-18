package pl.lukaszpeciak.towarownik.diagnostics

import java.time.Instant

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
)

internal class SafeDiagnosticRecorder(
    private val maxEvents: Int = 64,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init { require(maxEvents in 1..256) }

    private val events = ArrayDeque<Pair<Long, SafeDiagnosticEvent>>()
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
        )
        events.addLast(clock() to safe)
        while (events.size > maxEvents) events.removeFirst()
    }

    @Synchronized fun hasEvents(): Boolean = events.isNotEmpty()
    @Synchronized fun clear() = events.clear()

    @Synchronized
    fun report(): String = buildString {
        appendLine("Taksula diagnostics - safe session events")
        appendLine("capture=automatic metadata-only")
        appendLine("scope=session (all providers); retention=last ${events.size}/$maxEvents events")
        appendLine("not_captured=prompts, product identifiers, attachments, raw HTTP, cookies, secrets, Worker internals")
        if (events.isEmpty()) appendLine("No captured events; unavailable is not evidence of success.")
        events.forEachIndexed { index, (time, event) ->
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

internal object TaksulaDiagnostics {
    val recorder = SafeDiagnosticRecorder()
}
