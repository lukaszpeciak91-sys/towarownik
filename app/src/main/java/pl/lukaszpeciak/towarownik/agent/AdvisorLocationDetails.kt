package pl.lukaszpeciak.towarownik.agent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Render every checked canonical market deterministically, independently of
 * the model's short final-answer token budget. No new inventory lookup.
 */
internal fun renderAdvisorLocationDetails(
    evidence: AdvisorLocationEvidence,
): String {
    if (evidence.locations.isEmpty()) return ""
    val checked = evidence.checkedIds.toSet()
    if (checked.size != evidence.checkedIds.size ||
        evidence.locations.size != checked.size ||
        evidence.locations.map { it.branchId }.toSet() != checked) return ""
    val positives = evidence.locations.count { (it.stock ?: 0) > 0 }
    val zeros = evidence.locations.count { it.stock == 0 }
    val unknown = evidence.locations.count { it.stock == null }
    val heading = when (evidence.coverage) {
        "all_other_locations" -> "Wszystkie pozostałe markety OBI"
        "all_public_locations" -> "Wszystkie markety OBI"
        else -> "Wskazane markety OBI"
    }
    val timestamp = evidence.verifiedAtMillis?.let {
        val formatted = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(it))
        " (stan zweryfikowany nie wcześniej niż $formatted)"
    }.orEmpty()
    return buildString {
        append("$heading: sprawdzono ${checked.size}, dodatni stan: $positives, zero: $zeros, niepotwierdzone: $unknown")
        append(timestamp)
        append("\n")
        evidence.locations.forEach { item ->
            append(item.branchId)
            append(" — ")
            append(item.name)
            append(" — ")
            append(if (item.stock == null) "brak potwierdzenia (stan nieznany)"
                else "${item.stock} szt.")
            append("\n")
        }
        if (unknown > 0) append("Brak potwierdzenia nie oznacza stanu 0.")
    }.trimEnd()
}
