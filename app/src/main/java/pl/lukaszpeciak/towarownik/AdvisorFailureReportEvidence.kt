package pl.lukaszpeciak.towarownik

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import pl.lukaszpeciak.towarownik.agent.AdvisorLocationEvidence
import pl.lukaszpeciak.towarownik.product.provider.LocationFailure

/**
 * Bounded, transient identity for a failed Advisor turn. No prompts, product facts,
 * attachment bytes, access tokens or raw exceptions are captured.
 */
internal data class AdvisorFailureReportEvidence(
    val conversationId: Long,
    val turnId: String,
    val diagnostic: String?,
) {
    init {
        require(conversationId > 0L)
        require(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(turnId))
        require(diagnostic == null || diagnostic.length <= REPORT_ADVISOR_FAILURE_DIAGNOSTIC_MAX_CHARS)
    }
}

/** The report editor pins an immutable snapshot independently of transient Advisor UI state. */
internal class AdvisorFailureReportEvidenceStore {
    var current by mutableStateOf<AdvisorFailureReportEvidence?>(null)
        private set
    var selected by mutableStateOf<AdvisorFailureReportEvidence?>(null)
        private set

    fun record(evidence: AdvisorFailureReportEvidence) {
        current = evidence
        selected = null
    }

    fun select(conversationId: Long): AdvisorFailureReportEvidence? {
        val value = current?.takeIf { it.conversationId == conversationId }
        selected = value
        return value
    }

    fun clear() {
        current = null
        selected = null
    }

    fun clearConversation(conversationId: Long) {
        if (current?.conversationId == conversationId || selected?.conversationId == conversationId) clear()
    }
}

/** Session-only reports for completed messages. Never use a global "latest lookup". */
internal class AdvisorResponseReportEvidenceStore {
    private val messages = linkedMapOf<Pair<Long, Long>, String>()

    @Synchronized
    fun record(conversationId: Long, messageId: Long, evidence: List<AdvisorLocationEvidence>) {
        if (conversationId <= 0L || messageId <= 0L) return
        val text = evidence.take(3).mapNotNull(::kwantLocationReportValue).joinToString("\n\n")
        if (text.isEmpty()) return
        // A committed message owns one immutable snapshot, even if a caller retries recording.
        messages.putIfAbsent(conversationId to messageId, text)
        while (messages.size > 16) messages.remove(messages.keys.first())
    }

    @Synchronized
    fun get(conversationId: Long, messageId: Long): String? = messages[conversationId to messageId]

    @Synchronized
    fun clearConversation(conversationId: Long) {
        messages.keys.removeAll { it.first == conversationId }
    }
}

/** Project existing result facts only; no upstream names, quantities, headers, bodies or errors. */
private fun kwantLocationReportValue(evidence: AdvisorLocationEvidence): String? {
    if (evidence.providerId != "kwant-pl") return null
    val productId = evidence.productId?.takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: return null
    fun ids(values: List<String>) = values.take(100)
        .filter { it.matches(Regex("[0-9]{1,12}")) }.distinct().joinToString(",")
    fun token(value: String?, allowed: Set<String>) = when {
        value == null -> "none"
        value in allowed -> value
        else -> "unrecognized"
    }
    val reasons = LocationFailure.entries.map { it.name.lowercase() }.toSet() + setOf(
        "untrusted_product", "ambiguous_product", "unknown_location", "ambiguous_location",
        "location_intent_required", "location_not_authorized", "unsupported_provider",
        "local_tool_limit_reached", "location_service_unavailable", "multi_product_tool_contract",
    )
    val rows = evidence.locations.take(100)
    return buildString {
        appendLine("tool=find_product_locations provider=kwant-pl product_id=$productId")
        appendLine("requested_ids=[${ids(evidence.authorizedBranchIds)}]")
        appendLine("status=${token(evidence.status, setOf("verified", "unavailable", "rejected"))} reason=${token(evidence.reason, reasons)}")
        appendLine("coverage=${token(evidence.coverage, setOf("all_public_locations", "all_other_locations", "requested_subset", "partial", "unknown"))}")
        appendLine("checked_ids=[${ids(evidence.checkedIds)}] returned_ids=[${ids(evidence.returnedIds)}] missing_ids=[${ids(evidence.missingIds)}]")
        appendLine("stock_states=positive:${rows.count { (it.stock ?: 0) > 0 }},zero:${rows.count { it.stock == 0 }},unknown:${rows.count { it.stock == null }}")
        appendLine("verified_at_millis=${evidence.verifiedAtMillis?.takeIf { it > 0L } ?: "unavailable"}")
        append("http_status=${evidence.httpDiagnostic?.status ?: "unavailable"} content_type=${evidence.httpDiagnostic?.contentType?.name?.lowercase() ?: "unavailable"}")
    }
}
