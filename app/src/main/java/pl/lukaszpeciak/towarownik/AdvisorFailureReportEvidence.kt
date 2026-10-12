package pl.lukaszpeciak.towarownik

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
