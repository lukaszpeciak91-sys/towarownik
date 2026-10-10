package pl.lukaszpeciak.towarownik.agent

import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot

/**
 * Bounded, same-conversation evidence supplied by Android; never model text.
 * Only the user's own preceding inventory request may authorize a clarification.
 */
internal data class AdvisorLocationHistoryMessage(
    val role: String,
    val text: String,
    val products: List<VerifiedProductSnapshot> = emptyList(),
)

internal data class AdvisorLocationFollowUp(
    val authorizedText: String,
    val historicalProducts: List<VerifiedProductSnapshot>,
    val bothProductIds: Set<String> = emptySet(),
    val hasPendingInventoryRequest: Boolean = false,
)

/**
 * Strictly previous messages only (not the current USER message).
 * At most 12 messages and four preceding USER turns are examined. Any
 * unrelated USER turn breaks the authorization chain; ASSISTANT suggestions
 * cannot establish permission. The model's productId is never consulted here.
 */
internal fun resolveAdvisorLocationFollowUp(
    input: String,
    history: List<AdvisorLocationHistoryMessage>,
    historicalProducts: List<VerifiedProductSnapshot>,
): AdvisorLocationFollowUp {
    val recent = history.takeLast(12)
    val candidates = historicalProducts.distinctBy { it.providerId to it.effectiveProductId }
    val userHistory = recent.filter { it.role == "USER" }.takeLast(4)
    fun normalized(s: String): String = java.text.Normalizer
        .normalize(s.lowercase().replace('ł', 'l'), java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
    val current = normalized(input)
    fun isClarification(s: String): Boolean {
        val t = normalized(s)
        return t == "obu" || t == "oba" ||
            // A user-supplied city, not an untrusted model-provided city.
            (t.length in 3..45 && t.split(" ").size <= 3 &&
                t.split(" ").all { it.length >= 3 } &&
                !Regex("""\b(jak|dlaczego|polec|montaz|zamontowac|dobierz)\b""")
                    .containsMatchIn(t)) ||
            t.startsWith("a w ") || t.startsWith("no jak market ") ||
            t.startsWith("ok podaj markety ")
    }
    // Explicit inventory must mention a requested stock/availability check or
    // a requested store; merely mentioning a city in technical advice is not one.
    fun isInventoryRequest(s: String): Boolean {
        val t = normalized(s)
        return Regex("""\b(stan|stany|dostepnosc|dostepny|dostepna|dostepne|sprawdz|gdzie jeszcze)\b""")
            .containsMatchIn(t) &&
            !Regex("""\b(zamontowac|montaz|podlaczyc|odpowiednik|zamiennik|dobierz)\b""")
                .containsMatchIn(t)
    }
    val anchor = userHistory.indexOfLast { isInventoryRequest(it.text) }
    val continuation = anchor >= 0 &&
        userHistory.drop(anchor + 1).all { isClarification(it.text) }
    val currentIsShortFollowUp =
        current == "obu" || current == "oba" ||
            current.startsWith("a w ") || current.startsWith("no jak market ") ||
            current.startsWith("ok podaj markety ") ||
            (current.length in 3..45 && current.split(" ").size <= 3 &&
                current.split(" ").all { it.length >= 3 } &&
                !isInventoryRequest(input) &&
                !Regex("""\b(jak|dlaczego|polec|montaz|zamontowac|dobierz)\b""")
                    .containsMatchIn(current))
    val pending = continuation && currentIsShortFollowUp &&
        candidates.isNotEmpty()

    // A current explicit inventory request can still use a previous unambiguous
    // user-selected product. No product is selected from a model hint.
    val recentSelections = userHistory.asReversed().flatMap { item ->
        val txt = normalized(item.text)
        candidates.filter { candidate ->
            val id = candidate.effectiveProductId.lowercase()
            Regex("""(?<![a-z0-9])""" + Regex.escape(id) + """(?![a-z0-9])""")
                .containsMatchIn(txt) ||
                candidate.articleNumber?.let { article ->
                    txt.contains(article.lowercase())
                } == true
        }.map { it.effectiveProductId }
    }
    val selectedId = recentSelections.firstOrNull()
    val newestCard = recent.asReversed()
        .firstOrNull { it.role == "ASSISTANT" && it.products.isNotEmpty() }
        ?.products?.distinctBy { it.effectiveProductId }
        ?.singleOrNull()?.effectiveProductId
    val provenId = selectedId ?: newestCard
    val both = if ((current == "obu" || current == "oba" ||
            userHistory.lastOrNull()?.let { normalized(it.text) in setOf("obu", "oba") } == true) &&
        continuation && candidates.size == 2
    ) candidates.map { it.effectiveProductId }.toSet() else emptySet()
    val selectedHistory = if (both.isEmpty() && provenId != null) {
        candidates.filter { it.effectiveProductId == provenId }
            .takeIf { it.isNotEmpty() } ?: candidates
    } else candidates
    val effective = if (pending && !isInventoryRequest(input)) {
        if (current == "obu" || current == "oba") {
            "Sprawdź stan obu produktów"
        } else if (current.startsWith("a w ") || current.startsWith("no jak market ") ||
            current.startsWith("ok podaj markety ")
        ) {
            "Sprawdź stan produktu: $input"
        } else {
            "Sprawdź stan produktu w $input"
        }
    } else input
    return AdvisorLocationFollowUp(
        authorizedText = effective,
        historicalProducts = selectedHistory,
        bothProductIds = both,
        hasPendingInventoryRequest = continuation,
    )
}
