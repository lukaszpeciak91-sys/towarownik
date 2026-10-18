package pl.lukaszpeciak.towarownik.agent

import java.text.Normalizer
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchResolver
import pl.lukaszpeciak.towarownik.product.provider.BranchResolution
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderId

/** Android-only authority. None of these fields is deserialized from a model tool call. */
internal sealed interface AdvisorInventoryAuthorization {
    data class Discovery(val provider: ProviderId, val query: String) : AdvisorInventoryAuthorization

    data class Inventory(
        val provider: ProviderId,
        val product: VerifiedProductSnapshot,
        val currentProduct: VerifiedProductSnapshot?,
        val branches: List<BranchId>,
        val fullNetwork: Boolean,
        val directory: List<ProviderBranch>,
        val productProvenance: ProductProvenance,
        val locationProvenance: LocationProvenance,
        val currentUserProductEvidence: Set<String>,
        val originalUserText: String,
        val workingBranch: BranchId,
    ) : AdvisorInventoryAuthorization

    data class ClarificationRequired(val reason: String) : AdvisorInventoryAuthorization
    data class Denied(val reason: String) : AdvisorInventoryAuthorization

    enum class ProductProvenance { CURRENT_USER, CURRENT_USER_REFERENCE, BOUNDED_USER_HISTORY, CURRENT_TURN_VERIFICATION }
    enum class LocationProvenance { CURRENT_USER, BOUNDED_USER_HISTORY, WORKING_PROFILE }
}

internal fun advisorAuthorityText(value: String): String =
    Normalizer.normalize(value.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[^a-z0-9]+"""), " ").trim()

internal fun advisorUserProductEvidence(text: String): Set<String> =
    AdvisorProductIdentityReferences.fromUserText(text).referencedNumericIds +
        advisorExplicitArticleIds(text) + listOfNotNull(advisorExplicitShortProductId(text))

internal fun advisorHasCurrentProductEvidence(text: String): Boolean =
    advisorUserProductEvidence(text).isNotEmpty()

internal fun advisorMatchingProducts(
    text: String,
    candidates: List<VerifiedProductSnapshot>,
): List<VerifiedProductSnapshot> {
    val evidence = advisorUserProductEvidence(text)
    return candidates.filter { product -> evidence.any {
        product.effectiveProductId.equals(it, ignoreCase = true) || advisorMatchesArticle(product.articleNumber, it)
    } }
}

internal fun advisorHasUnknownProductEvidence(text: String, candidates: List<VerifiedProductSnapshot>): Boolean =
    advisorUserProductEvidence(text).any { requested -> candidates.none {
        it.effectiveProductId.equals(requested, ignoreCase = true) || advisorMatchesArticle(it.articleNumber, requested)
    } }

/** Stock nouns are evidence, not an inventory-verb allowlist. The model proposes the operation. */
internal fun advisorHasStockEvidence(text: String): Boolean = Regex(
    """\b(?:stan|stany|stanow|dostepnosc|dostepny|dostepna|dostepne|dostepnych|zapas|zapasy|magazynach)\b""",
).containsMatchIn(advisorAuthorityText(text))

/** A complete reference to the same price can retain identity, never grant inventory permission. */
internal fun advisorNeutralPriceReference(text: String): Boolean = Regex(
    """^(?:(?:pokaz|podaj) )?(?:cena|cene|ceny)(?: tego(?: samego)? produktu)?$""",
).matches(advisorAuthorityText(text))

/** Keep explicit non-inventory negatives; no positive stock-verb vocabulary is required. */
internal fun advisorNonInventoryRequest(text: String): Boolean = !advisorHasStockEvidence(text) && Regex(
    """\b(?:cena|ceny|cene|cen|koszt|kosztuje|zamontowac|montaz|montazu|podlaczyc|podlaczenie|odpowiednik|zamiennik|polec|dobierz|kompatybilnosc|kompatybilny)\b""",
).containsMatchIn(advisorAuthorityText(text))

/** One pure admission/identity/scope decision shared by Controller and direct tool callers. */
internal object AdvisorInventoryAuthorizer {
    fun resolve(
        arguments: AdvisorLocationArguments,
        providerId: String,
        currentBranchId: String,
        userText: String,
        currentVerified: Collection<VerifiedProductSnapshot>,
        historicalVerified: List<VerifiedProductSnapshot>,
        directory: List<ProviderBranch>,
        history: List<AdvisorLocationHistoryMessage>,
    ): AdvisorInventoryAuthorization {
        fun denied(reason: String) = AdvisorInventoryAuthorization.Denied(reason)
        fun clarify(reason: String) = AdvisorInventoryAuthorization.ClarificationRequired(reason)
        if (arguments.providerId != providerId) return denied("wrong_provider")
        if (providerId !in setOf("obi-pl", "kwant-pl")) return denied("unsupported_provider")
        advisorBareProductQuery(userText, providerId)?.let {
            return AdvisorInventoryAuthorization.Discovery(ProviderId(providerId), it)
        }
        if (advisorNonInventoryRequest(userText)) return clarify("location_intent_required")
        val known = (currentVerified + historicalVerified).filter { it.providerId == providerId }
            .distinctBy { it.effectiveProductId }.take(20)
        val followUp = resolveAdvisorLocationFollowUp(
            userText, history, known, directory, providerId, currentBranchId,
            currentVerified.filter { it.providerId == providerId }.map { it.effectiveProductId }.toSet(),
        )
        followUp.failureReason?.let { return clarify(it) }
        if (known.isEmpty()) return clarify("untrusted_product")
        if (followUp.bothProductIds.size == 2) return denied("multi_product_tool_contract")
        if (followUp.isContextualQuantityRequest && !followUp.hasPendingInventoryRequest) return clarify("untrusted_product")
        val candidates = if (followUp.inheritsInventoryContext) {
            known.filter { fresh -> followUp.historicalProducts.any { it.effectiveProductId == fresh.effectiveProductId } }
        } else (currentVerified.filter { it.providerId == providerId } + followUp.historicalProducts)
            .distinctBy { it.effectiveProductId }
        val selected = resolveTrustedProduct(userText, arguments.productId, candidates,
            currentVerified.map { it.effectiveProductId }.filter { id -> candidates.any { it.effectiveProductId == id } }.toSet())
            ?: return if (candidates.size > 1) clarify("ambiguous_product") else denied("untrusted_product")
        if (directory.isEmpty() || directory.size > 100 ||
            directory.map { it.branchId }.distinct().size != directory.size ||
            directory.any { it.name.isBlank() } || directory.none { it.branchId.value == currentBranchId }
        ) return denied("directory_unavailable")
        val current = BranchId(currentBranchId)
        val userPlace = followUp.resolvedBranch?.let {
            BranchResolution.Resolved(it)
        } ?: if (providerId == "obi-pl") {
            BranchResolver.resolveObiMarketScope(userText, directory, current, false)
        } else BranchResolver.resolve(userText, directory, current, false)
        val explicitlyMultiple = Regex("""\b(?:wszystk[a-z]*|inne|innych|pozostale|pozostalych|markety|marketach|oddzialy|oddzialach)\b""")
            .containsMatchIn(norm(userText))
        if (userPlace is BranchResolution.Ambiguous &&
            userPlace.candidates.map { norm(it.name) }.distinct().size == 1 &&
            !userPlace.isCityWideScope && !explicitlyMultiple
        ) return clarify("ambiguous_location")
        val direct = when (userPlace) {
            is BranchResolution.Resolved -> listOf(userPlace.branch.branchId)
            is BranchResolution.CurrentBranch -> listOf(userPlace.branch.branchId)
            is BranchResolution.Ambiguous ->
                userPlace.candidates.takeIf { userPlace.isCityWideScope }?.map { it.branchId }
            else -> null
        }
        val scope = if (direct != null) AuthorizedLocationScope.Accepted(direct, false)
        else if (providerId == "obi-pl") authorizedObiLocations(userText, directory, current,
            userPlace == BranchResolution.NotMentioned)
        else when (val result = authorizeKwantLocations(userText, emptyList(), directory, current)) {
            is KwantAuthorizedLocations.Allowed -> AuthorizedLocationScope.Accepted(
                if (result.nationwide) directory.map { it.branchId } else result.requested, result.nationwide)
            is KwantAuthorizedLocations.Denied -> AuthorizedLocationScope.Rejected(result.reason)
        }
        if (scope is AuthorizedLocationScope.Rejected) return clarify(scope.reason)
        scope as AuthorizedLocationScope.Accepted
        if (userPlace == BranchResolution.UnknownMention) {
            // The complete scope parser handles comma chains/broad requests; canonical validation
            // must still reject an unresolved singular city/street/market pairing.
            val chainText = if (scope.fullNetwork) normLocationScope(userText).replace(
                Regex("""\b(?:w|we) (?:(?:wszystkich|innych|pozostalych|jakich|ktorych|calym|calej) )?(?:marketach|oddzialach|hurtowniach|sieci)(?=$|[ ,]+(?:w|we|na|przy) )"""), " ",
            ).replace(Regex("""(?<=markety )w ktorych (?:jest|sa) dostepn(?:y|e)(?: ten produkt)?$"""), "") else userText
            val chainPlace = if (providerId == "obi-pl") BranchResolver.resolveObiMarketScope(
                chainText.replace(",", " i "), directory, current, false,
            ) else BranchResolver.resolve(chainText.replace(",", " i "), directory, current, false)
            if (chainPlace == BranchResolution.UnknownMention) return clarify("unknown_location")
        }
        val explicit = advisorHasCurrentProductEvidence(userText)
        val userSelectedHistory = followUp.hasBoundedProductReferent
        val userReference = Regex("""\b(?:ten|tego|tym) (?:sam[a-z]* )?produkt[a-z]*\b""").containsMatchIn(norm(userText))
        // No stock verb allowlist: identity and bounded USER-owned location/referent evidence
        // authorize the model's chosen operation. Ordinary acknowledgement grants nothing.
        val scopeEvidence = direct != null || scope.fullNetwork || userPlace is BranchResolution.Ambiguous ||
            scope.branches != listOf(current)
        if (!explicit && !advisorHasStockEvidence(userText) && !followUp.inheritsInventoryContext &&
            !(currentVerified.isNotEmpty() && scopeEvidence) &&
            !(userReference && scopeEvidence) && !(userSelectedHistory && scopeEvidence)
        ) return clarify("location_intent_required")
        val currentProduct = currentVerified.firstOrNull {
            it.providerId == providerId && it.effectiveProductId == selected.effectiveProductId
        }
        if (!explicit && currentProduct == null && !followUp.inheritsInventoryContext &&
            (!(userSelectedHistory || userReference && history.none { it.role == "USER" }) ||
                !advisorIsBoundedHistoricalScopeReference(userText, directory))
        ) return clarify("untrusted_product")
        return AdvisorInventoryAuthorization.Inventory(
            ProviderId(providerId), selected,
            currentProduct,
            scope.branches, scope.fullNetwork, directory,
            when {
                explicit -> AdvisorInventoryAuthorization.ProductProvenance.CURRENT_USER
                currentVerified.any { it.effectiveProductId == selected.effectiveProductId } ->
                    AdvisorInventoryAuthorization.ProductProvenance.CURRENT_TURN_VERIFICATION
                userReference -> AdvisorInventoryAuthorization.ProductProvenance.CURRENT_USER_REFERENCE
                else -> AdvisorInventoryAuthorization.ProductProvenance.BOUNDED_USER_HISTORY
            },
            when {
                norm(userText).removePrefix("a ") == "tam" -> AdvisorInventoryAuthorization.LocationProvenance.BOUNDED_USER_HISTORY
                userPlace == BranchResolution.NotMentioned ->
                    AdvisorInventoryAuthorization.LocationProvenance.WORKING_PROFILE
                else -> AdvisorInventoryAuthorization.LocationProvenance.CURRENT_USER
            }, advisorUserProductEvidence(userText), userText, current,
        )
    }
    private fun resolveTrustedProduct(
        userText: String,
        modelProductId: String,
        candidates: List<VerifiedProductSnapshot>,
        currentIds: Set<String>,
    ): VerifiedProductSnapshot? {
        if (candidates.isEmpty()) return null
        if (advisorHasUnknownProductEvidence(userText, candidates)) return null
        val explicit = advisorMatchingProducts(userText, candidates)
        val trusted = when {
            explicit.size == 1 -> explicit.single()
            explicit.size > 1 -> return null
            currentIds.isNotEmpty() -> candidates
                .filter { it.effectiveProductId in currentIds }
                .singleOrNull()
            candidates.size == 1 -> candidates.single()
            else -> return null
        } ?: return null
        return trusted.takeIf { it.effectiveProductId == modelProductId }
    }

    private sealed interface AuthorizedLocationScope {
        data class Accepted(
            val branches: List<BranchId>,
            val fullNetwork: Boolean,
        ) : AuthorizedLocationScope
        data class Rejected(val reason: String) : AuthorizedLocationScope
    }

    /**
     * User-owned location scope. Parse every explicit city or contextualized
     * market ID BEFORE inventory; model hints do not participate.
     * A broad query is chosen only when no restricted locations were named.
     */
    private fun authorizedObiLocations(
        userText: String,
        directory: List<ProviderBranch>,
        selected: BranchId,
        allowCurrentBranchFallback: Boolean,
    ): AuthorizedLocationScope {
        if (directory.map { it.branchId }.distinct().size != directory.size ||
            directory.none { it.branchId == selected }) {
            return AuthorizedLocationScope.Rejected("directory_unavailable")
        }
        val text = normLocationScope(userText)
        val requested = when (val scope = parseLocationScope(text, directory)) {
            is ParsedLocationScope.Rejected ->
                return AuthorizedLocationScope.Rejected("unknown_location")
            is ParsedLocationScope.Restricted -> scope.branches
            ParsedLocationScope.Broad -> emptySet()
        }
        // "Podaj markety w których jest dostępny" is a relative-clause
        // network request, not a city beginning with "w których".
        val broadRequested = listOf(
            "jeszcze", "inne", "innych", "innym", "pozostal",
            "wszystk", "ktore market", "jakich market",
            "markety w ktorych", "marketach jest dostepny",
        ).any { norm(userText).contains(it) }
        // A city + an adjacent purported market number must agree exactly.
        // "market w Miejscu Piastowym 054" cannot silently resolve to 052.
        // Require the market context AND adjacent numeric suffix: free product
        // measurements such as "listwa 100 cm" are not location identifiers.
        val cityAliases = obiCityAliases(directory)
        val marketCityNumber = Regex(
            """\b(?:a\s+w|(?:market\w*|obi)\s+w)\s+([a-z ]+?)\s+([0-9]{3})(?:\s+bodajze)?\s*$""",
        ).find(text)
        if (marketCityNumber != null) {
            val city = marketCityNumber.groupValues[1].trim()
            val number = marketCityNumber.groupValues[2]
            val cityIds = cityAliases[city]?.map { it.branchId.value }?.toSet()
            if (cityIds != null && number !in cityIds) {
                return AuthorizedLocationScope.Rejected(
                    "location_conflict_${number}_vs_${cityIds.sorted().joinToString("_")}",
                )
            }
        }
        val explicitCurrent = Regex(
            """\b(?:rowniez|takze|razem z)\s+(?:(?:market\w*|obi)\s+)?""" +
                Regex.escape(selected.value) + """\b""",
        ).containsMatchIn(text)
        if (broadRequested && explicitCurrent) {
            return AuthorizedLocationScope.Accepted(
                directory.map { it.branchId }, fullNetwork = true,
            )
        }
        if (requested.isNotEmpty()) {
            // An explicit city expands to every canonical OBI store in that
            // city; model hints can neither add cities nor reduce the scope.
            return AuthorizedLocationScope.Accepted(
                directory.map { it.branchId }.filter { it in requested },
                fullNetwork = false,
            )
        }
        if (!broadRequested) return if (allowCurrentBranchFallback) {
            AuthorizedLocationScope.Accepted(listOf(selected), fullNetwork = false)
        } else AuthorizedLocationScope.Rejected("unknown_location")
        return AuthorizedLocationScope.Accepted(
            directory.map { it.branchId }.filterNot { it == selected },
            fullNetwork = true,
        )
    }

    /**
     * Single canonical-city alias source for complete USER restriction parsing. Never inferred from the model.
     */
    private fun obiCityAliases(
        directory: List<ProviderBranch>,
    ): Map<String, List<ProviderBranch>> {
        val cities = directory.groupBy { norm(it.name) }
        val aliases = linkedMapOf<String, List<ProviderBranch>>()
        for ((name, branches) in cities) {
            aliases[name] = branches
            advisorCityLocative(name)?.let { aliases[it] = branches }
        }
        return aliases
    }

    private val marketLabelPattern = Regex(
        """\b(?:market\w*|sklep\w*|oddzial\w*|obi)(?:\s+obi)?(?:\s+(?:nr|numer))?\s+""",
    )

    private sealed interface ParsedLocationScope {
        data object Broad : ParsedLocationScope
        data class Restricted(val branches: Set<BranchId>) : ParsedLocationScope
        data object Rejected : ParsedLocationScope
    }

    /**
     * Constrained grammar, not a free-form Advisor intent classifier.
     *
     * 1) A location number must follow an explicit market/OBI label;
     *    commas, "i" and "oraz" repeat numeric IDs in that same list.
     * 2) A city chain follows "w/we/dla" or a short bare-city command.
     *    Aliases come ONLY from verified canonical cities and a small set
     *    of deterministic inflections. The entire chain is consumed.
     * 3) Ordinary "[modifier] marketach/oddzialach" noun phrases are
     *    structurally generic and cannot become guessed city names.
     *
     * One unknown ID/city or unfinished connector rejects the WHOLE scope.
     */
    private fun parseLocationScope(
        text: String,
        directory: List<ProviderBranch>,
    ): ParsedLocationScope {
        val ids = linkedSetOf<BranchId>()
        val byId = directory.associateBy { it.branchId.value }
        val aliases = obiCityAliases(directory)
        val knownNames = aliases.keys.sortedByDescending { it.length }
        fun isBoundary(s: String, length: Int): Boolean =
            length == s.length || !s[length].isLetterOrDigit()

        fun resolveCityChain(source: String): Boolean {
            var remaining = source.trimStart()
            while (true) {
                remaining = remaining.replaceFirst(Regex("""^(?:w|we)\s+"""), "")
                val alias = knownNames.firstOrNull { name ->
                    remaining.startsWith(name) && isBoundary(remaining, name.length)
                } ?: return false
                aliases.getValue(alias).forEach { ids.add(it.branchId) }
                remaining = remaining.drop(alias.length).trimStart()
                val connector = Regex("""^(?:,|\bi\b|\boraz\b)\s*""")
                    .find(remaining)
                if (connector == null) return true // trailing words do not erase earlier scope
                val next = remaining.drop(connector.value.length).trimStart()
                // A final courtesy is not a named locality (", proszę").
                if (next == "prosze" || next.isEmpty() && connector.value.startsWith(",")) {
                    return next.isNotEmpty()
                }
                remaining = next
            }
        }

        // Market IDs require a preceding store label, not an arbitrary 3-digit
        // product measurement. Commas must survive normalization.
        for (label in marketLabelPattern.findAll(text)) {
            var remaining = text.substring(label.range.last + 1).trimStart()
            val first = Regex("""^([0-9]{3})\b""").find(remaining) ?: continue
            var current = first
            while (true) {
                val id = current.groupValues[1]
                val branch = byId[id] ?: return ParsedLocationScope.Rejected
                ids.add(branch.branchId)
                remaining = remaining.drop(current.value.length).trimStart()
                val connector = Regex("""^(?:,|\bi\b|\boraz\b)\s*""")
                    .find(remaining) ?: break
                remaining = remaining.drop(connector.value.length).trimStart()
                // Every joined market number is mandatory.
                current = Regex("""^([0-9]{3})\b""").find(remaining)
                    ?: return ParsedLocationScope.Rejected
            }
        }

        // Common prepositional city syntax, at any point in the request.
        // "w innych marketach" is a generic noun phrase, not a locality.
        val placeNoun = Regex(
            """^(?:(?:innych|wszystkich|pozostalych|naszych|tych)\s+){0,2}(?:market|marketach|markecie|markety|sklep|sklepach|sklepie|oddzial|oddzialach|oddziale|lokalizacjach|obi)\b""",
        )
        val prepositions = Regex("""\b(?:w|we|dla)\s+""")
        for (match in prepositions.findAll(text)) {
            val suffix = text.substring(match.range.last + 1)
            val noun = placeNoun.find(suffix)
            if (noun != null) {
                val tail = suffix.drop(noun.value.length).trim()
                val canonicalIds = Regex("""^(?:obi )?[0-9]{3}(?:\s*(?:,|i|oraz)\s*[0-9]{3})*(?:\s+(?:ten produkt|tego produktu|prosze))?$""").matches(tail)
                if (tail.isNotEmpty() && tail != "obi" && !canonicalIds && !Regex(
                        """^(?:w|we|dla|jest|sa|tego|ten|produktu|produkt|prosze)\b""",
                    ).containsMatchIn(tail) && !Regex("""^[0-9]{7,14}\b""").containsMatchIn(tail)
                ) return ParsedLocationScope.Rejected
                continue
            }
            if (Regex("""^ktorych\b""").containsMatchIn(suffix)) continue
            if (!resolveCityChain(suffix)) return ParsedLocationScope.Rejected
        }

        // Bare "Sprawdź Tarnów" / "Sprawdź Kraków i Nowy Sącz".
        // Bare stock and market noun phrases are NOT locality requests.
        val command = Regex("""^(?:sprawdz|sprawdzcie)\s+""").find(text)
        if (command != null) {
            val suffix = text.substring(command.range.last + 1).trimStart()
            val isInventoryPhrase = Regex(
                """^(?:stany|stan|dostepnosc|inne|wszystkie|pozostale|markety|marketach|sklepy|oddzialy|oddzialach|produkt|produkty|w|we)\b""",
            ).containsMatchIn(suffix)
            val startsWithCity = knownNames.any {
                suffix.startsWith(it) && isBoundary(suffix, it.length)
            }
            if (startsWithCity) {
                if (!resolveCityChain(suffix)) return ParsedLocationScope.Rejected
            } else if (!isInventoryPhrase &&
                Regex("""^[a-z]{4,}(?:\s+[a-z]{3,})?(?:\s*(?:,|\bi\b|\boraz\b)\s+[a-z]{4,}(?:\s+[a-z]{3,})?)*\s*$""")
                    .matches(suffix)
            ) {
                return ParsedLocationScope.Rejected // unknown bare city
            }
        }
        return if (ids.isEmpty()) ParsedLocationScope.Broad
            else ParsedLocationScope.Restricted(ids)
    }


    private fun normLocationScope(text: String): String =
        Normalizer.normalize(text.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^a-z0-9,]+"""), " ")
            .replace(Regex("""\s*,\s*"""), ", ")
            .trim()

    private fun norm(text: String): String =
        Normalizer.normalize(text.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .trim()

}

/** Historical identity needs a complete reference, not merely a stock noun plus an old card.
 * Reuse existing canonical place matching; unknown product-description text cannot be erased.
 * These bounded scope-only forms do not gate current USER IDs or current-turn discovery.
 */
internal fun advisorIsBoundedHistoricalScopeReference(text: String, directory: List<ProviderBranch>): Boolean {
    val normalized = advisorAuthorityText(text.replace(",", " i "))
    val reference = Regex("""\b(?:ten(?: sam)? produkt|tego(?: samego)? produktu)\b""").find(normalized)
    val deicticBody = reference?.let {
        val before = normalized.take(it.range.first).trim()
        // A genitive attachment such as "lampy do tego produktu" identifies another object.
        val attached = Regex("""\b(?:do|dla|od|z|bez|i|oraz)$""").containsMatchIn(before)
        if (attached) null else normalized.drop(it.range.last + 1).trim()
    }
    if (Regex("""^gdzie jeszcze (?:jest|sa)(?: ten produkt)?$""").matches(normalized)) return true
    val prefix = Regex(
        """^(?:(?:sprawdz(?:cie)?|pokaz|podaj|zweryfikuj|weryfikuj)(?: (?:stan|stany|dostepnosc))?|""" +
            """(?:czy|gdzie) (?:jest|sa)) (?:produktu )?""",
    ).find(normalized)
    if (deicticBody == null && prefix == null) return false
    var body = (deicticBody ?: normalized.drop(requireNotNull(prefix).value.length))
        .replaceFirst(Regex("""^(?:ten produkt|tego produktu)\s*"""), "")
        .replace(Regex("""\s+(?:ten produkt|tego produktu)$"""), "")
        .removeSuffix(" i prosze").removeSuffix(" prosze").trim()
    if (reference != null && deicticBody != null && body.isEmpty()) return true
    if (body == "u nas") return true // WorkingProfile was independently validated above.
    body = body.replaceFirst(Regex("""^[0-9]+ (?:w|kw|m|mm|cm|kg|l|szt|opak) (?=(?:w|we) )"""), "")
    if (Regex("""^(?:wszystkie )?(?:inne|pozostale|wszystkie) (?:markety|oddzialy)(?: (?:i )?(?:rowniez|takze|razem z) (?:(?:market(?: obi)?|obi) )?[0-9]{3})?$""").matches(body)) return true
    val broadPrefix = Regex("""^(?:w|we) (?:(?:wszystkich|innych|pozostalych) )?(?:marketach|oddzialach|hurtowniach)\b\s*""").find(body)
    if (broadPrefix != null) {
        body = body.drop(broadPrefix.value.length)
        if (body.isEmpty()) return true
    }
    body = body.replaceFirst(Regex("""^(?:markety|sklepy) (?=[0-9])"""), "market ")
        .replace(Regex("""\b(?:market|markecie|sklep|sklepie) obi """), "market ")
    val clauses = body.split(Regex("""\s*(?:,|\bi\b|\boraz\b)\s*"""))
    var placeFound = false
    for (clause in clauses) {
        if (clause.isBlank()) return false
        // Exact matching to a singleton here proves the COMPLETE clause, not a new
        // scope choice. The full directory/scope/ambiguity decision already ran above.
        if (directory.any { BranchResolver.resolveHint(clause, listOf(it)) != null }) {
            placeFound = true
        } else if (!Regex("""^(?:potrzebuje )?[0-9]+ (?:w|kw|m|mm|cm|kg|l|szt|opak)$""").matches(clause)) {
            return false
        }
    }
    return placeFound
}
