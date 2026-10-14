package pl.lukaszpeciak.towarownik.agent

import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.BranchResolution
import pl.lukaszpeciak.towarownik.product.provider.BranchResolver
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
    val isContextualQuantityRequest: Boolean = false,
    val failureReason: String? = null,
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
    providerId: String? = null,
    currentBranchId: String? = null,
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
        if (providerId == "kwant-pl") kwantCanonicalBranchAliases(name)
        else listOfNotNull(name, advisorCityLocative(name))
    }.filter { it.isNotBlank() }.toSet()
    // Explicitly bounded same-product quantity questions, not generic short
    // phrases. The city must be present in the current verified branch directory.
    fun quantityFollowUpLocation(text: String): String? {
        val destination =
            Regex("""^a ile tego samego produktu jest w (.+)$""")
                .matchEntire(text)?.groupValues?.get(1)
                ?: Regex("""^a w (.+) ile jest tego samego$""")
                    .matchEntire(text)?.groupValues?.get(1)
        return destination?.takeIf { it in canonicalLocations }
    }
    fun isCanonicalLocationWithOptionalMarketNumber(value: String): Boolean {
        val match = Regex("""^(.+?)(?: ([0-9]{3})(?: bodajze)?)?$""")
            .matchEntire(value) ?: return false
        return match.groupValues[1] in canonicalLocations
    }
    val selectedBranch = branches.singleOrNull { it.branchId.value == currentBranchId }
    fun naturalShortLocation(s: String): BranchResolution? {
        val t = normalized(s).removePrefix("a ")
        if (t == "u nas") return selectedBranch?.let { BranchResolution.CurrentBranch(it) }
        if (quantityFollowUpLocation(normalized(s)) != null) return null
        val legacyCityNumber = t.removePrefix("w ").removePrefix("we ")
        if (Regex(""" [0-9]{3}(?: bodajze)?$""").containsMatchIn(legacyCityNumber) &&
            isCanonicalLocationWithOptionalMarketNumber(legacyCityNumber)
        ) return null // Preserve the existing precise city/market-conflict boundary.
        if (t == "tam") return null // Requires an uninterrupted USER-selected referent below.
        val isPlaceExpression = t in canonicalLocations ||
            Regex("""^(?:w|we) .+$""").matches(t)
        if (!isPlaceExpression || Regex(
                """\b(?:cena|ceny|cene|cen|koszt|kosztuje|pokaz|podaj|montaz|zamontowac|podlaczyc|dobierz|kompatybilnosc)\b""",
            ).containsMatchIn(t)
        ) return null
        val place = if (t in canonicalLocations) "w $t" else t
        return BranchResolver.resolve(
            place, branches, selectedBranch?.branchId ?: BranchId("unselected"),
            allowBareBranchIds = false,
        )
    }
    fun shortLocationCandidate(s: String): Boolean {
        val t = normalized(s).removePrefix("a ")
        return t in setOf("u nas", "tam") || naturalShortLocation(s) != null
    }
    fun isClarification(s: String): Boolean {
        val t = normalized(s)
        if (t in setOf("obu", "oba")) return true
        if (t.removePrefix("a ") == "tam") return true
        if (naturalShortLocation(s).let {
                it is BranchResolution.Resolved || it is BranchResolution.CurrentBranch ||
                    it is BranchResolution.Ambiguous
            }
        ) return true
        if (quantityFollowUpLocation(t) != null) return true
        if (t in canonicalLocations) return true
        val preposition = Regex(
            """^a (?:w|we) (?:(?:oddziale|markecie|sklepie)(?: obi)? )?(.+)$""",
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
        val availabilityQuestion = Regex(
            """\bczy\s+(?:jest|sa)\s+(?:(?:w|we)\s+(?:obi|kwant)|u\s+nas)\b""",
        ).containsMatchIn(t)
        return (inventory || explicitStoreCheck || availabilityQuestion) &&
            !Regex("""\b(zamontowac|montaz|podlaczyc|odpowiednik|zamiennik|dobierz)\b""")
                .containsMatchIn(t)
    }
    val anchor = userHistory.indexOfLast { isInventoryRequest(it.text) }
    val continuation = anchor >= 0 &&
        userHistory.drop(anchor + 1).all { isClarification(it.text) }
    val currentIsShortFollowUp = isClarification(input)
    val pending = continuation && currentIsShortFollowUp && candidates.isNotEmpty()
    // Only current/preceding USER place expressions bind "tam". Neither a
    // model hint, an ASSISTANT suggestion nor a historical card's branch does.
    val isThere = current.removePrefix("a ") == "tam"
    val previousUserLocation = if (isThere && continuation) {
        val lastSelection = userHistory.drop(anchor).asReversed().filterNot { normalized(it.text).removePrefix("a ") == "tam" }.map { item ->
            BranchResolver.resolve(
                item.text, branches, selectedBranch?.branchId ?: BranchId("unselected"),
                allowBareBranchIds = false,
            )
        }.firstOrNull { it != BranchResolution.NotMentioned }
        when (lastSelection) {
            is BranchResolution.CurrentBranch -> lastSelection.branch
            is BranchResolution.Resolved -> lastSelection.branch
            else -> null // Never skip an ambiguous/unknown selection to an older branch.
        }
    } else null
    val currentLocation = naturalShortLocation(input)
    val anchorText = userHistory.getOrNull(anchor)?.text.orEmpty()
    val anchorNumbers = AdvisorProductIdentityReferences.fromUserText(anchorText).referencedNumericIds
    val anchorArticle = advisorExplicitArticleId(anchorText)
    val anchorShortId = advisorExplicitShortProductId(anchorText)
    val unknownAnchorIdentity = anchorNumbers.any { number -> candidates.none {
        it.effectiveProductId == number || it.articleNumber?.equals(number, ignoreCase = true) == true
    } } || anchorShortId?.let { id -> candidates.none { it.effectiveProductId == id } } == true ||
        anchorArticle?.let { id -> candidates.none {
            it.effectiveProductId.equals(id, ignoreCase = true) || advisorMatchesArticle(it.articleNumber, id)
        } } == true
    val followUpFailure = if (shortLocationCandidate(input)) {
        when {
            !continuation -> "location_intent_required"
            candidates.isEmpty() || unknownAnchorIdentity -> "untrusted_product"
            branches.isEmpty() || branches.size > 100 ||
                branches.map { it.branchId }.distinct().size != branches.size ||
                branches.any { it.name.isBlank() } -> "directory_unavailable"
            isThere && previousUserLocation == null -> "ambiguous_location"
            current.removePrefix("a ") == "u nas" && selectedBranch == null -> "directory_unavailable"
            currentLocation is BranchResolution.Ambiguous -> "ambiguous_location"
            currentLocation == BranchResolution.UnknownMention -> "unknown_location"
            else -> null
        }
    } else null

    // A current explicit inventory request can still use a previous unambiguous
    // user-selected product. No product is selected from a model hint.
    val recentSelection = userHistory.asReversed().map { item ->
        val references = AdvisorProductIdentityReferences.fromUserText(item.text)
        candidates.filter { candidate ->
            references.mentions(candidate.effectiveProductId) ||
                candidate.effectiveProductId == advisorExplicitShortProductId(item.text) ||
                advisorMatchesArticle(candidate.articleNumber, advisorExplicitArticleId(item.text)) ||
                (candidate.articleNumber?.let(references::mentions) == true)
        }.map { it.effectiveProductId }.distinct()
    }.firstOrNull { it.isNotEmpty() }.orEmpty()
    val selectedId = recentSelection.singleOrNull()
    // Both the present request and older USER selections use the exact same
    // numeric-ID safety boundary as the inventory tool. "1000 W" may describe
    // a measurement, never an explicit request for verified product 1000.
    val currentReferences = AdvisorProductIdentityReferences.fromUserText(input)
    val currentMatches = candidates.filter { candidate ->
        currentReferences.mentions(candidate.effectiveProductId) ||
            candidate.effectiveProductId == advisorExplicitShortProductId(input) ||
            advisorMatchesArticle(candidate.articleNumber, advisorExplicitArticleId(input)) ||
            (candidate.articleNumber?.let(currentReferences::mentions) == true)
    }.distinctBy { it.providerId to it.effectiveProductId }
    val newestCard = recent.asReversed()
        .firstOrNull { it.role == "ASSISTANT" && it.products.isNotEmpty() }
        ?.products?.distinctBy { it.effectiveProductId }
        ?.singleOrNull()?.effectiveProductId
    val provenId = when (currentMatches.size) {
        1 -> currentMatches.single().effectiveProductId
        0 -> if (recentSelection.size > 1) null else selectedId ?: newestCard
        else -> null // Explicitly named multiple candidates remain ambiguous.
    }
    // A NEW explicit product identity (including an as-yet unverified numeric
    // ID) supersedes any older "obu". The model never decides this: the
    // shared USER-text identity policy is the only source. Current "obu" and
    // location-only clarifications can still carry a bounded prior "obu".
    val currentSelectsProduct = currentMatches.isNotEmpty() ||
        currentReferences.referencedNumericIds.isNotEmpty() || advisorExplicitShortProductId(input) != null ||
        advisorExplicitArticleId(input) != null
    val currentRequestsBoth = Regex("""\b(?:obu|oba)\b""").containsMatchIn(current)
    val inheritedBoth = !currentSelectsProduct &&
        userHistory.drop(anchor.coerceAtLeast(0)).any {
            Regex("""\b(?:obu|oba)\b""").containsMatchIn(normalized(it.text))
        }
    val both = if ((currentRequestsBoth || inheritedBoth) &&
        continuation && candidates.size == 2
    ) {
        candidates.map { it.effectiveProductId }.toSet()
    } else emptySet()
    val selectedHistory = if (both.isEmpty() && currentMatches.size <= 1 && provenId != null) {
        candidates.filter { it.effectiveProductId == provenId }
            .takeIf { it.isNotEmpty() } ?: candidates
    } else candidates
    val resolvedShortBranch = when (currentLocation) {
        is BranchResolution.CurrentBranch -> currentLocation.branch
        is BranchResolution.Resolved -> currentLocation.branch
        else -> previousUserLocation
    }
    val effective = if (pending && !isInventoryRequest(input) && followUpFailure == null) {
        if (resolvedShortBranch != null && current.removePrefix("a ") in setOf("u nas", "tam")) {
            // Canonical scope is derived independently from USER + directory;
            // the user never has to type an internal numeric command.
            if (providerId != "obi-pl") "Sprawdź stan produktu w oddziale ${resolvedShortBranch.branchId.value}"
            else "Sprawdź stan produktu w market OBI ${resolvedShortBranch.branchId.value}"
        } else {
            val quantityDestination = quantityFollowUpLocation(current)
            if (quantityDestination != null) {
                // Rewrite only the bounded location expression from USER text.
                // Product identity still comes exclusively from verified history.
                "Sprawdź stan produktu w $quantityDestination"
            } else if (current == "obu" || current == "oba") {
                "Sprawdź stan obu produktów"
            } else if (current.startsWith("w ") || current.startsWith("we ") ||
                current.startsWith("a w ") || current.startsWith("a we ") ||
                current.startsWith("no jak market ") ||
                current.startsWith("ok podaj markety ")
            ) {
                "Sprawdź stan produktu: $input"
            } else {
                "Sprawdź stan produktu w $input"
            }
        }
    } else input
    return AdvisorLocationFollowUp(
        authorizedText = effective,
        historicalProducts = selectedHistory,
        bothProductIds = both,
        hasPendingInventoryRequest = continuation,
        isContextualQuantityRequest = quantityFollowUpLocation(current) != null,
        failureReason = followUpFailure,
    )
}

/** Explicit product/check slots distinguish short IDs from branch numbers and units. */
internal fun advisorExplicitShortProductId(input: String): String? {
    val match = Regex("""(?i)\b(?:produkt(?:u)?|id produktu|kod(?:u)?|sprawd[zź]\s+(?:stan|dost[eę]pno[sś][cć]))\s+([0-9]{1,3})(?![a-z0-9])\b""")
        .find(input) ?: return null
    val tail = input.substring(match.range.last + 1)
    if (Regex("""(?i)^\s*(?:m|mm|cm|kg|l|szt|opak|kw|v|a)\b""").containsMatchIn(tail)) return null
    if (Regex("""(?i)^\s*w\b(?:\s+w\b|\s*$|\s*[,.;!?])""").containsMatchIn(tail)) return null
    return match.groupValues[1]
}

/** A bounded explicit article slot, not a product-name or specification classifier. */
internal fun advisorExplicitArticleId(input: String): String? = Regex(
    """(?i)\b(?:sprawd[zź]\s+(?:stan|dost[eę]pno[sś][cć])|produkt(?:u)?|kod(?:u)?|artyku[lł](?:u)?)\s+([a-z0-9][a-z0-9/_-]*[0-9][a-z0-9/_-]*)\b""",
).find(input)?.groupValues?.get(1)?.takeIf { it.length <= 64 && it.any(Char::isLetter) }

/** Exact verified article, optionally USER omits its canonical manufacturer suffix. */
internal fun advisorMatchesArticle(verifiedArticle: String?, requested: String?): Boolean =
    verifiedArticle != null && requested != null &&
        (verifiedArticle.equals(requested, ignoreCase = true) ||
            '/' !in requested && verifiedArticle.substringBefore('/').equals(requested, ignoreCase = true))
