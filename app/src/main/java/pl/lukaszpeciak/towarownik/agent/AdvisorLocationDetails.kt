package pl.lukaszpeciak.towarownik.agent

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import pl.lukaszpeciak.towarownik.product.OBI_STORES

/**
 * Render every checked canonical market deterministically, independently of
 * the model's short final-answer token budget. No new inventory lookup.
 */
internal fun renderAdvisorLocationDetails(
    evidence: AdvisorLocationEvidence,
): String {
    if (evidence.providerId == "kwant-pl" &&
        evidence.status == "unavailable" &&
        evidence.reason == "unverified_request_contract"
    ) {
        return "Nie mogę potwierdzić stanów w innych oddziałach KWANT: " +
            "sposób pobierania tych danych nie jest jeszcze zweryfikowany. " +
            "Możesz wybrać konkretny oddział w profilu pracy i sprawdzić " +
            "produkt osobno albo skontaktować się z hurtownią. " +
            "Nie wykonano sprawdzenia stanów w innych oddziałach."
    }
    // An exact city/ID mismatch requires confirmation, not a substituted
    // inventory read. Append this grounded clarification independently of
    // how the model phrases its final answer.
    if (evidence.status == "rejected" && evidence.providerId == "obi-pl") {
        val conflict = Regex("""^location_conflict_([0-9]{3})_vs_([0-9]{3})$""")
            .matchEntire(evidence.reason.orEmpty())
        if (conflict != null) {
            val supplied = conflict.groupValues[1]
            val canonical = conflict.groupValues[2]
            val branch = OBI_STORES.singleOrNull { it.storeNumber == canonical }
            if (branch != null) {
                return "Podany numer marketu OBI $supplied nie zgadza się z lokalizacją " +
                    "undefined (market OBI $canonical). " +
                    "Potwierdź, czy chodzi o OBI $canonical. " +
                    "Nie wykonano sprawdzenia stanów."
            }
        }
    }
    if (evidence.locations.isEmpty()) return ""
    val checked = evidence.checkedIds.toSet()
    if (checked.size != evidence.checkedIds.size ||
        evidence.locations.size != checked.size ||
        evidence.locations.map { it.branchId }.toSet() != checked) return ""
    val positives = evidence.locations.count { (it.stock ?: 0) > 0 }
    val zeros = evidence.locations.count { it.stock == 0 }
    val unknown = evidence.locations.count { it.stock == null }
    // Coverage "partial" describes evidence quality, not the originally
    // authorized scope. Recover the nationwide scope from canonical IDs;
    // never describe 61 attempted other markets as a chosen subset.
    val canonical = OBI_STORES.map { it.storeNumber }.toSet()
    val allMarkets = checked == canonical
    val allOtherMarkets = checked.size == canonical.size - 1 &&
        canonical.containsAll(checked)
    val incomplete = evidence.coverage == "partial" ||
        evidence.coverage == "unknown" || unknown > 0
    val heading = when {
        evidence.coverage == "all_public_locations" || allMarkets ->
            "Wszystkie markety OBI"
        evidence.coverage == "all_other_locations" || allOtherMarkets ->
            "Wszystkie pozostałe markety OBI"
        else -> "Wskazane markety OBI"
    } + if (incomplete) " — wyniki niepełne" else ""
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
