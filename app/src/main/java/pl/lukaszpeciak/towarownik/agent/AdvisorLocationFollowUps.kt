package pl.lukaszpeciak.towarownik.agent

import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.BranchResolution
import pl.lukaszpeciak.towarownik.product.provider.BranchResolver
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch

/**
 * Bounded, same-conversation evidence supplied by Android; never model text.
 * Only bounded USER-owned evidence can authorize a product/location reference.
 */
internal data class AdvisorLocationHistoryMessage(
    val role: String,
    val text: String,
    val products: List<VerifiedProductSnapshot> = emptyList(),
)

internal data class AdvisorLocationFollowUp(
    val userText: String,
    val resolvedBranch: ProviderBranch? = null,
    val historicalProducts: List<VerifiedProductSnapshot>,
    val bothProductIds: Set<String> = emptySet(),
    val hasPendingInventoryRequest: Boolean = false,
    val hasBoundedProductReferent: Boolean = false,
    val isContextualQuantityRequest: Boolean = false,
    val isShortLocationFollowUp: Boolean = false,
    val failureReason: String? = null,
    val inheritsInventoryContext: Boolean = false,
)

/**
 * Strictly previous messages only (not the current USER message).
 * At most 12 messages and four preceding USER turns are examined. Any
 * unrelated USER turn breaks the referent chain; a neutral price reference
 * retains the same product without authorizing stock. ASSISTANT suggestions
 * cannot establish permission. The model's productId is never consulted here.
 */
internal fun resolveAdvisorLocationFollowUp(
    input: String,
    history: List<AdvisorLocationHistoryMessage>,
    historicalProducts: List<VerifiedProductSnapshot>,
    branches: List<ProviderBranch> = emptyList(),
    providerId: String? = null,
    currentBranchId: String? = null,
    currentVerifiedIds: Set<String> = emptySet(),
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
    val locationRequestPrefix = Regex("""^(?:nie )?sprawdzisz(?: mi)? (?=(?:w|we) |u nas\b)""")
    fun locationClause(s: String): String {
        val withoutFiller = normalized(s).replace(Regex("""^(?:dobrze|ok|no|prosze) (?:a )?"""), "")
            .removePrefix("a ")
        // Only a complete location clause can borrow intent. A retry is not
        // a new unrestricted inventory anchor, and advice/price tails fail below.
        return withoutFiller.replace(locationRequestPrefix, "")
    }
    fun naturalShortLocation(s: String): BranchResolution? {
        // A complete current product/stock request is NOT a short inherited
        // location clarification. Its authority is resolved independently by
        // AdvisorInventoryAuthorizer, never by an older conversation anchor.
        if (advisorHasCurrentProductEvidence(s) || advisorHasStockEvidence(s)) return null
        val t = locationClause(s)
        if (t == "u nas") return selectedBranch?.let { BranchResolution.CurrentBranch(it) }
        if (quantityFollowUpLocation(normalized(s)) != null) return null
        val legacyCityNumber = t.removePrefix("w ").removePrefix("we ")
        if (Regex(""" [0-9]{3}(?: bodajze)?$""").containsMatchIn(legacyCityNumber) &&
            isCanonicalLocationWithOptionalMarketNumber(legacyCityNumber)
        ) return null // Preserve the existing precise city/market-conflict boundary.
        if (t == "tam" || t in setOf("ile tego maja w tym markecie", "ile tego jest w tym markecie")) {
            return null // Requires an uninterrupted USER-selected referent below.
        }
        if (Regex(
                """\b(?:cena|ceny|cene|cen|koszt|kosztuje|pokaz|podaj|montaz|zamontowac|podlaczyc|dobierz|kompatybilnosc)\b""",
            ).containsMatchIn(t)
        ) return null
        if (providerId == "obi-pl") {
            // A short USER location reference may inherit a selected product.
            // Do NOT reinterpret arbitrary full questions ("gdzie jest...",
            // "no jak market...") as clarifications: their complete syntax
            // still belongs to the inventory authorizer and conflict checks.
            // "A ile tego samego produktu mają w OBI 003?" is a bounded
            // same-product quantity follow-up; its destination still has to
            // pass the canonical market resolver.
            val quantityPlace = Regex("""^ile tego samego produktu (?:maja|ma|jest|sa) (?:w|we) (.+)$""")
                .matchEntire(t)?.groupValues?.get(1)
            // A natural question such as "a ile mają na Wielickiej w Krakowie?"
            // is a bounded location-only follow-up, not a NEW product search.
            val naturalQuantityPlace = Regex("""^ile (?:maja|ma|jest|sa) ((?:w|we|na) .+)$""")
                .matchEntire(t)?.groupValues?.get(1)
            val contextualQuantityPlace = Regex("""^ile tego (?:jest|maja|ma) ((?:w|we|na) .+)$""")
                .matchEntire(t)?.groupValues?.get(1)
            val placeText = quantityPlace?.let { "w $it" } ?: naturalQuantityPlace ?: contextualQuantityPlace ?: t
            val canonical = placeText in canonicalLocations ||
                canonicalLocations.any { placeText.startsWith("$it ") }
            val labelled = Regex("""^(?:w|we|na|obi|market|markecie|sklep|sklepie) .+$""").matches(placeText)
            if (!canonical && !labelled && !Regex("""[0-9]{3}""").matches(placeText)) return null
            return BranchResolver.resolveObiMarketScope(
                placeText, branches, selectedBranch?.branchId ?: BranchId("unselected"),
            ).takeUnless { it == BranchResolution.NotMentioned }
        }
        val isPlaceExpression = t in canonicalLocations ||
            Regex("""^(?:w|we) .+$""").matches(t)
        if (!isPlaceExpression) return null
        val place = if (t in canonicalLocations) "w $t" else t
        return BranchResolver.resolve(
            place, branches, selectedBranch?.branchId ?: BranchId("unselected"),
            allowBareBranchIds = false,
        )
    }
    fun shortLocationCandidate(s: String): Boolean {
        val t = locationClause(s)
        return t in setOf("u nas", "tam", "ile tego maja w tym markecie", "ile tego jest w tym markecie") ||
            naturalShortLocation(s) != null
    }
    fun isClarification(s: String): Boolean {
        val t = normalized(s)
        if (t in setOf("obu", "oba")) return true
        if (t.removePrefix("a ") in setOf("tam", "ile tego maja w tym markecie", "ile tego jest w tym markecie")) return true
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
    fun verifiedForUser(item: AdvisorLocationHistoryMessage): List<String> {
        val index = recent.indexOfLast { it === item }
        if (index < 0) return emptyList()
        return recent.drop(index + 1).takeWhile { it.role != "USER" }
            .filter { it.role == "ASSISTANT" }.flatMap { it.products }
            .filter { product -> candidates.any { it.providerId == product.providerId && it.effectiveProductId == product.effectiveProductId } }
            .map { it.effectiveProductId }.distinct()
    }
    val anchor = userHistory.indexOfLast {
        advisorHasCurrentProductEvidence(it.text) || advisorHasStockEvidence(it.text) ||
            verifiedForUser(it).isNotEmpty() && !advisorNonInventoryRequest(it.text) ||
            Regex("""\bczy (?:jest|sa) (?:(?:w|we) (?:obi|kwant)|u nas)\b""").containsMatchIn(normalized(it.text))
    }
    val intervening = userHistory.drop(anchor + 1)
    val explicitRetry = locationRequestPrefix.containsMatchIn(
        normalized(input).replace(Regex("""^(?:dobrze|ok|no|prosze) (?:a )?"""), "").removePrefix("a "),
    ) || quantityFollowUpLocation(current) != null || advisorHasStockEvidence(input) ||
        (providerId == "obi-pl" && Regex("""^(?:a )?ile (?:tego )?(?:maja|ma|jest|sa) (?:w|we|na) .+$""")
            .matches(current))
    fun retainsProductReference(text: String): Boolean = advisorNeutralPriceReference(text) ||
        advisorNonInventoryRequest(text) && advisorIsBoundedHistoricalScopeReference(text, branches)
    val boundedReferent = anchor >= 0 && intervening.all { isClarification(it.text) || retainsProductReference(it.text) }
    val continuation = anchor >= 0 && (intervening.all { isClarification(it.text) } ||
        explicitRetry && intervening.all { isClarification(it.text) || retainsProductReference(it.text) })
    val currentIsShortFollowUp = isClarification(input)
    val pending = continuation && currentIsShortFollowUp && candidates.isNotEmpty()
    // Only current/preceding USER place expressions bind "tam". Neither a
    // model hint, an ASSISTANT suggestion nor a historical card's branch does.
    val isThere = current.removePrefix("a ") in setOf(
        "tam", "ile tego maja w tym markecie", "ile tego jest w tym markecie",
    )
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
    val anchorCards = userHistory.getOrNull(anchor)?.let(::verifiedForUser).orEmpty().ifEmpty {
        // A new, previously unverified descriptive USER request can be repaired by
        // unique same-turn verification. It cannot replace an already selected card/ID.
        currentVerifiedIds.takeIf { anchor >= 0 && !advisorHasCurrentProductEvidence(anchorText) }
            ?.singleOrNull()?.let(::listOf).orEmpty()
    }
    val unverifiedNewDescription = anchor >= 0 && !advisorHasCurrentProductEvidence(anchorText) &&
        anchorCards.isEmpty() && !advisorIsBoundedHistoricalScopeReference(anchorText, branches) &&
        !Regex("""\b(?:obu|oba|dwoch)\b""").containsMatchIn(normalized(anchorText)) &&
        !userHistory.drop(anchor.coerceAtLeast(0)).any {
            Regex("""\b(?:obu|oba)\b""").containsMatchIn(normalized(it.text))
        } && !Regex("""\b(?:obu|oba)\b""").containsMatchIn(current)
    val unknownAnchorIdentity = advisorHasUnknownProductEvidence(anchorText, candidates) || unverifiedNewDescription
    val followUpFailure = if (shortLocationCandidate(input)) {
        when {
            !continuation -> "location_intent_required"
            candidates.isEmpty() || unknownAnchorIdentity -> "untrusted_product"
            branches.isEmpty() || branches.size > 100 ||
                branches.map { it.branchId }.distinct().size != branches.size ||
                branches.any { it.name.isBlank() } -> "directory_unavailable"
            isThere && previousUserLocation == null -> "ambiguous_location"
            current.removePrefix("a ") == "u nas" && selectedBranch == null -> "directory_unavailable"
            currentLocation is BranchResolution.Ambiguous && !(providerId == "obi-pl" && currentLocation.isCityWideScope) -> "ambiguous_location"
            currentLocation == BranchResolution.UnknownMention -> "unknown_location"
            else -> null
        }
    } else null

    // A current explicit inventory request can still use a previous unambiguous
    // user-selected product. No product is selected from a model hint.
    val identityHistory = if (anchorCards.isNotEmpty()) userHistory.drop(anchor.coerceAtLeast(0)) else userHistory
    val latestIdentityTurn = identityHistory.asReversed().firstOrNull {
        advisorHasCurrentProductEvidence(it.text)
    }
    val recentSelection = latestIdentityTurn?.let { item ->
        advisorMatchingProducts(item.text, candidates).map { it.effectiveProductId }.distinct()
    } ?: anchorCards
    val unknownLatestIdentity = latestIdentityTurn?.let {
        advisorHasUnknownProductEvidence(it.text, candidates)
    } == true
    val selectedId = recentSelection.singleOrNull()
    // Both the present request and older USER selections use the exact same
    // numeric-ID safety boundary as the inventory tool. "1000 W" may describe
    // a measurement, never an explicit request for verified product 1000.
    val currentMatches = advisorMatchingProducts(input, candidates)
        .distinctBy { it.providerId to it.effectiveProductId }
    val newestCard = recent.asReversed()
        .firstOrNull { it.role == "ASSISTANT" && it.products.isNotEmpty() }
        ?.products?.distinctBy { it.effectiveProductId }
        ?.singleOrNull()?.effectiveProductId
    val provenId = when (currentMatches.size) {
        1 -> currentMatches.single().effectiveProductId
        0 -> if (recentSelection.size > 1 || unknownLatestIdentity) null else selectedId ?: newestCard?.takeIf { continuation && !unverifiedNewDescription }
        else -> null // Explicitly named multiple candidates remain ambiguous.
    }
    // A NEW explicit product identity (including an as-yet unverified numeric
    // ID) supersedes any older "obu". The model never decides this: the
    // shared USER-text identity policy is the only source. Current "obu" and
    // location-only clarifications can still carry a bounded prior "obu".
    val currentSelectsProduct = advisorHasCurrentProductEvidence(input)
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
    val quantityBranch = quantityFollowUpLocation(current)?.let { city ->
        branches.filter { branch ->
            val name = normalized(branch.name)
            city in if (providerId == "kwant-pl") kwantCanonicalBranchAliases(name)
                else listOfNotNull(name, advisorCityLocative(name))
        }.singleOrNull()
    }
    val resolvedShortBranch = when (currentLocation) {
        is BranchResolution.CurrentBranch -> currentLocation.branch
        is BranchResolution.Resolved -> currentLocation.branch
        else -> quantityBranch ?: previousUserLocation
    }
    return AdvisorLocationFollowUp(
        userText = input,
        resolvedBranch = resolvedShortBranch,
        historicalProducts = selectedHistory,
        bothProductIds = both,
        hasPendingInventoryRequest = continuation,
        hasBoundedProductReferent = boundedReferent && !unknownAnchorIdentity,
        isContextualQuantityRequest = quantityFollowUpLocation(current) != null,
        // Only location-bearing clarifications: "obu" and other product-only
        // short turns must not be mistaken for one-market identity inheritance.
        isShortLocationFollowUp = currentIsShortFollowUp &&
            (currentLocation != null || quantityFollowUpLocation(current) != null || isThere),
        failureReason = followUpFailure ?: if (unknownLatestIdentity && !currentSelectsProduct) "untrusted_product" else if (pending && !currentSelectsProduct &&
            both.isEmpty() && selectedHistory.size > 1
        ) "ambiguous_product" else null,
        inheritsInventoryContext = pending && !currentSelectsProduct,
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

/** Exact USER identifier slots, including catalog codes before a stock command. */
internal fun advisorExplicitArticleIds(input: String): Set<String> {
    val labelled = Regex(
        """(?i)\b(?:sprawd[zź]\s+(?:stan|dost[eę]pno[sś][cć])|produkt(?:u)?|kod(?:u)?|artyku[lł](?:u)?)\s+([a-z0-9][a-z0-9/_-]*[0-9][a-z0-9/_-]*)\b""",
    ).findAll(input).map { it.groupValues[1] }
    val catalog = Regex("""(?i)(?<![a-z0-9/_-])[a-z0-9][a-z0-9/_-]{2,63}(?![a-z0-9/_-])""")
        .findAll(input).map { it.value }.filter { token ->
            token.any(Char::isDigit) && token.any(Char::isLetter) &&
                (token.first().isLetter() || input.trim().equals(token, ignoreCase = true)) &&
                (input.trim().equals(token, ignoreCase = true) || token.any { it in "/_-" } ||
                    Regex("""(?i)[a-z][0-9]+[a-z]""").containsMatchIn(token)) &&
                !Regex("""(?i)[0-9]+(?:[x×][0-9]+)?(?:mm|cm|m2|m3|m|kg|kw|kwh|v|a|l|ml|szt|opak|w)""").matches(token)
        }
    return (labelled + catalog).filter { it.length <= 64 && it.any(Char::isLetter) }
        .map { it.uppercase() }.toSet()
}

/** A standalone identifier is discovery intent, independently of conversation history. */
internal fun advisorBareProductQuery(input: String, providerId: String?): String? {
    val token = input.trim()
    if (Regex("""[0-9]+""").matches(token)) return token.takeIf {
        if (providerId == "obi-pl") it.length in setOf(7, 8, 12, 13, 14)
        else providerId == "kwant-pl" && it.length in 4..14
    }
    return token.takeIf {
        providerId in setOf("obi-pl", "kwant-pl") && it.length in 3..64 &&
            Regex("""(?i)[a-z0-9][a-z0-9/_-]+""").matches(it) &&
            it.any(Char::isDigit) && it.any(Char::isLetter) &&
            !Regex("""(?i)[0-9]+(?:[x×][0-9]+)?(?:mm|cm|m2|m3|m|kg|kw|kwh|v|a|l|ml|szt|opak|w)""").matches(it)
    }
}

/** Exact verified article, optionally USER omits its canonical manufacturer suffix. */
internal fun advisorMatchesArticle(verifiedArticle: String?, requested: String?): Boolean =
    verifiedArticle != null && requested != null &&
        (verifiedArticle.equals(requested, ignoreCase = true) ||
            '/' !in requested && verifiedArticle.substringBefore('/').equals(requested, ignoreCase = true))
