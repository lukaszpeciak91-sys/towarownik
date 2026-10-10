package pl.lukaszpeciak.towarownik.agent

import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch

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
    branches: List<ProviderBranch> = emptyList(),
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
    // A follow-up inherits inventory intent only if its COMPLETE location
    // expression matches a canonical provider city (or deterministic locative).
    // A price, installation or compatibility suffix is never a clarification.
    val canonicalLocations = branches.flatMap { branch ->
        val name = normalized(branch.name)
        listOfNotNull(name, advisorCityLocative(name))
    }.filter { it.isNotBlank() }.toSet()
    fun isCanonicalLocationWithOptionalMarketNumber(value: String): Boolean {
        val match = Regex("""^(.+?)(?: ([0-9]{3})(?: bodajze)?)?$""")
            .matchEntire(value) ?: return false
        return match.groupValues[1] in canonicalLocations
    }
    fun isClarification(s: String): Boolean {
        val t = normalized(s)
        if (t in setOf("obu", "oba")) return true
        if (t in canonicalLocations) return true
        val preposition = Regex(
            """^a w (?:(?:oddziale|markecie|sklepie)(?: obi)? )?(.+)$""",
        ).matchEntire(t)
        if (preposition != null) {
            return isCanonicalLocationWithOptionalMarketNumber(preposition.groupValues[1])
        }
        val market = Regex("""^no jak market w (.+)$""").matchEntire(t)
        if (market != null) {
            return isCanonicalLocationWithOptionalMarketNumber(market.groupValues[1])
        }
        return Regex(
            """^ok podaj markety w ktorych (?:jest|sa) dostepn(?:y|e)(?: ten produkt)?$""",
        ).matches(t)
    }
    // Explicit inventory must mention a requested stock/availability check or
    // a requested store; merely mentioning a city in technical advice is not one.
    fun isInventoryRequest(s: String): Boolean {
        val t = normalized(s)
        val inventory = Regex(
            """\b(stan|stany|dostepnosc|dostepny|dostepna|dostepne|gdzie jeszcze)\b""",
        ).containsMatchIn(t)
        val explicitStoreCheck = Regex(
            """\bsprawdz\b.*\b(?:w|we|market|markety|sklep|obi|oddzial)\b""",
        ).containsMatchIn(t)
        return (inventory || explicitStoreCheck) &&
            !Regex("""\b(zamontowac|montaz|podlaczyc|odpowiednik|zamiennik|dobierz)\b""")
                .containsMatchIn(t)
    }
    val anchor = userHistory.indexOfLast { isInventoryRequest(it.text) }
    val continuation = anchor >= 0 &&
        userHistory.drop(anchor + 1).all { isClarification(it.text) }
    val currentIsShortFollowUp = isClarification(input)
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
    // A current explicit product ID or article always takes precedence over
    // prior selections. It must match an already verified snapshot; unknown
    // numbers are still rejected by the downstream trusted-product guard.
    val currentMatches = candidates.filter { candidate ->
        listOfNotNull(candidate.effectiveProductId, candidate.articleNumber)
            .any { identifier ->
                Regex("""(?<![a-z0-9])""" + Regex.escape(normalized(identifier)) +
                    """(?![a-z0-9])""").containsMatchIn(current)
            }
    }.distinctBy { it.providerId to it.effectiveProductId }
    val newestCard = recent.asReversed()
        .firstOrNull { it.role == "ASSISTANT" && it.products.isNotEmpty() }
        ?.products?.distinctBy { it.effectiveProductId }
        ?.singleOrNull()?.effectiveProductId
    val provenId = when (currentMatches.size) {
        1 -> currentMatches.single().effectiveProductId
        0 -> selectedId ?: newestCard
        else -> null // Explicitly named multiple candidates remain ambiguous.
    }
    val bothSelectedByUser = Regex("""\b(?:obu|oba)\b""").containsMatchIn(current) ||
        userHistory.drop(anchor.coerceAtLeast(0)).any {
            Regex("""\b(?:obu|oba)\b""").containsMatchIn(normalized(it.text))
        }
    val both = if (bothSelectedByUser && continuation && candidates.size == 2) {
        candidates.map { it.effectiveProductId }.toSet()
    } else emptySet()
    val selectedHistory = if (both.isEmpty() && currentMatches.size <= 1 && provenId != null) {
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
