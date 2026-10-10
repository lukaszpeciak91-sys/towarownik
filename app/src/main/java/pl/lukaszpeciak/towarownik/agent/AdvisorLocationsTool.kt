package pl.lukaszpeciak.towarownik.agent

import java.text.Normalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.LocationCoverage
import pl.lukaszpeciak.towarownik.product.provider.LocationCoverageKind
import pl.lukaszpeciak.towarownik.product.provider.LocationFailure
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ObiProductProvider
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsResult
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsService
import pl.lukaszpeciak.towarownik.product.provider.ProductRef
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct

/**
 * Authorization boundary for the location tool. Model IDs are only equality hints.
 * The CURRENT USER text independently authorizes OBI branch scope.
 * Prior assistant cards authorize exact identity, never historical stock.
 */
internal class AdvisorLocationsTool(
    private val locationsService: ProductLocationsService = ProductLocationsService.production(),
    private val obiBranches: () -> ProviderBranchResult = ObiProductProvider()::branches,
    private val scopeExecutionBudgetMillis: Long = 45_000L,
) {
    init {
        require(scopeExecutionBudgetMillis in 1L..60_000L)
    }
    suspend fun execute(
        arguments: AdvisorLocationArguments,
        providerId: String,
        currentBranchId: String,
        userText: String,
        currentVerified: Collection<VerifiedProductSnapshot>,
        historicalVerified: List<VerifiedProductSnapshot>,
    ): AdvisorLocationEvidence {
        fun rejected(reason: String, trustedId: String? = null) = empty(
            providerId, trustedId, "rejected", reason,
        )
        if (arguments.providerId != providerId) return rejected("wrong_provider")
        // A hallucinated model tool call can never authorize a costly
        // network-wide read for ordinary advice, mounting or product selection.
        if (!hasExplicitLocationsIntent(userText)) return rejected("location_intent_required")
        if (providerId != OBI_PROVIDER_ID.value && providerId != KWANT_PROVIDER_ID.value) {
            return rejected("unsupported_provider")
        }
        val known = (currentVerified + historicalVerified)
            .filter { it.providerId == providerId }
            .distinctBy { it.effectiveProductId }
            .take(20)
        val selected = resolveTrustedProduct(
            userText = userText,
            modelProductId = arguments.productId,
            candidates = known,
            currentIds = currentVerified.map { it.effectiveProductId }.toSet(),
        ) ?: return rejected(
            if (known.size > 1) "ambiguous_product" else "untrusted_product",
        )
        val exactRef = ProductRef(ProviderId(providerId), selected.effectiveProductId)
        val directory = if (providerId == OBI_PROVIDER_ID.value) {
            (obiBranches() as? ProviderBranchResult.Available)?.branches
                ?: return empty(providerId, exactRef.productId, "unavailable", "directory_unavailable")
        } else {
            emptyList()
        }
        val authorizedScope = if (providerId == OBI_PROVIDER_ID.value) {
            when (val scope = authorizedObiLocations(
                userText, arguments.locations, directory, BranchId(currentBranchId),
            )) {
                is AuthorizedLocationScope.Rejected ->
                    return rejected(scope.reason, exactRef.productId)
                is AuthorizedLocationScope.Accepted -> scope
            }
        } else {
            null
        }
        val trustedContext = ProviderProduct(
            ref = exactRef,
            branchId = BranchId(selected.effectiveBranchId),
            name = selected.name,
            stock = selected.stock,
            // An old product card only authorizes ID, not current central stock.
            centralStock = currentVerified.firstOrNull {
                it.providerId == providerId && it.effectiveProductId == exactRef.productId
            }?.centralStock,
            grossPrice = selected.grossPrice,
            priceScope = selected.priceScope,
            productUrl = selected.productUrl,
            ean = null,
            articleNumber = selected.articleNumber,
        )
        if (authorizedScope != null) {
            return readObiScope(exactRef, trustedContext, authorizedScope, directory)
        }
        // KWANT: one verified-product-bound request, still disabled by the
        // unresolved real 'extended' query value; never guess or omit it.
        val result = try {
            locationsService.read(
                ref = exactRef,
                requested = emptyList(),
                trustedProduct = trustedContext,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            ProductLocationsResult.Unavailable(LocationFailure.TRANSPORT)
        }
        return when (result) {
            is ProductLocationsResult.Available -> AdvisorLocationEvidence(
                providerId = providerId,
                productId = exactRef.productId,
                status = "verified",
                reason = null,
                coverage = result.coverage.kind.asWire(),
                checkedIds = result.coverage.requestedIds.map { it.value },
                returnedIds = result.coverage.returnedIds.map { it.value },
                missingIds = result.coverage.missingIds.map { it.value },
                locations = result.locations
                    .sortedWith(compareByDescending<pl.lukaszpeciak.towarownik.product.provider.LocationStock> {
                        (it.stock ?: 0) > 0
                    }.thenByDescending { it.stock ?: -1 })
                    .map { AdvisorLocationEntry(it.branch.branchId.value, it.branch.name, it.stock) },
                verifiedAtMillis = result.verifiedAtMillis,
                centralStock = result.centralStock,
            )
            is ProductLocationsResult.Unavailable -> empty(
                providerId, exactRef.productId, "unavailable",
                result.failure.name.lowercase(), result.coverage,
            )
            is ProductLocationsResult.Invalid ->
                rejected(result.failure.name.lowercase(), exactRef.productId)
            ProductLocationsResult.UnsupportedProvider ->
                rejected("unsupported_provider", exactRef.productId)
        }
    }

    /** The 20-market budget belongs to EACH service invocation, never a nationwide truncation.
     *  Sequential batches: 61 other stores => 4 reads => 7 bounded HTTP GETs.
     */
    private suspend fun readObiScope(
        ref: ProductRef,
        trustedProduct: ProviderProduct,
        scope: AuthorizedLocationScope.Accepted,
        directory: List<ProviderBranch>,
    ): AdvisorLocationEvidence {
        val requested = scope.branches
        val directoryById = directory.associateBy { it.branchId }
        val trusted = linkedMapOf<BranchId, Int?>()
        var failedRequests = 0
        var earliestVerified: Long? = null
        var firstFailure: String? = null
        val completedWithinBudget = withTimeoutOrNull(scopeExecutionBudgetMillis) {
        for (batch in requested.chunked(20)) {
            val result = try {
                locationsService.read(
                    ref = ref,
                    requested = batch,
                    trustedProduct = trustedProduct,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                ProductLocationsResult.Unavailable(LocationFailure.TRANSPORT)
            }
            when (result) {
                is ProductLocationsResult.Available -> {
                    val returned = result.coverage.returnedIds
                    // A provider result must not claim locations outside its own
                    // explicitly authorized, bounded batch or mix product IDs.
                    val valid = result.ref == ref &&
                        result.coverage.requestedIds == batch &&
                        returned.distinct().size == returned.size &&
                        returned.all { it in batch } &&
                        result.locations.size == returned.size &&
                        result.locations.map { it.branch.branchId }.toSet() == returned.toSet() &&
                        result.locations.all { it.stock != null && it.stock >= 0 }
                    if (!valid) {
                        failedRequests++
                        firstFailure = firstFailure ?: "malformed_response"
                        continue
                    }
                    result.locations.forEach { trusted[it.branch.branchId] = it.stock }
                    if (result.coverage.failedRequests > 0 || returned.size != batch.size) {
                        failedRequests += result.coverage.failedRequests
                        firstFailure = firstFailure ?: "partial_inventory"
                    }
                    earliestVerified = minOf(
                        earliestVerified ?: result.verifiedAtMillis,
                        result.verifiedAtMillis,
                    )
                }
                is ProductLocationsResult.Unavailable -> {
                    failedRequests++
                    firstFailure = firstFailure ?: result.failure.name.lowercase()
                }
                is ProductLocationsResult.Invalid -> {
                    failedRequests++
                    firstFailure = firstFailure ?: result.failure.name.lowercase()
                }
                ProductLocationsResult.UnsupportedProvider -> {
                    failedRequests++
                    firstFailure = firstFailure ?: "unsupported_provider"
                }
            }
        }
            true
        } ?: false
        if (!completedWithinBudget) {
            failedRequests++
            firstFailure = firstFailure ?: "execution_timeout"
        }
        val missing = requested.filterNot { it in trusted }
        val returned = requested.filter { it in trusted }
        val complete = missing.isEmpty() && failedRequests == 0
        val kind = when {
            trusted.isEmpty() -> "unknown"
            !complete -> "partial"
            scope.fullNetwork && requested.size == directory.size -> "all_public_locations"
            scope.fullNetwork -> "all_other_locations"
            else -> "requested_subset"
        }
        val entries = requested.map { id ->
            val branch = requireNotNull(directoryById[id])
            AdvisorLocationEntry(
                branchId = id.value,
                name = listOfNotNull(
                    branch.name,
                    branch.address?.takeIf { it.isNotBlank() },
                ).joinToString(" — ").take(100),
                stock = trusted[id],
            )
        }.sortedWith(
            compareByDescending<AdvisorLocationEntry> { (it.stock ?: 0) > 0 }
                .thenByDescending { it.stock ?: -1 },
        )
        return AdvisorLocationEvidence(
            providerId = ref.providerId.value,
            productId = ref.productId,
            status = if (trusted.isEmpty()) "unavailable" else "verified",
            reason = if (complete) null else firstFailure ?: "incomplete_inventory",
            coverage = kind,
            checkedIds = requested.map { it.value },
            returnedIds = returned.map { it.value },
            missingIds = missing.map { it.value },
            locations = entries, // Includes missing markets with stock=null.
            verifiedAtMillis = earliestVerified,
            centralStock = null, // OBI has no verified central stock contract.
        )
    }

    private fun resolveTrustedProduct(
        userText: String,
        modelProductId: String,
        candidates: List<VerifiedProductSnapshot>,
        currentIds: Set<String>,
    ): VerifiedProductSnapshot? {
        if (candidates.isEmpty()) return null
        // An explicit new exact identifier must never silently resolve to an
        // unrelated older card just because the model supplied that card's ID.
        val normalized = userText.lowercase()
        val explicit = candidates.filter { candidate ->
            boundedMention(normalized, candidate.effectiveProductId.lowercase()) ||
                (candidate.articleNumber?.let { boundedMention(
                    normalized, it.lowercase(),
                ) } == true)
        }
        val allNumericIds = Regex("""(?<![0-9])[0-9]{7,13}(?![0-9])""")
            .findAll(normalized).map { it.value }.toList()
        if (allNumericIds.isNotEmpty() &&
            allNumericIds.any { number -> candidates.none {
                it.effectiveProductId == number || it.articleNumber == number
            } }) return null

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

    /**
     * Narrow *authorization* guard for location-inventory operations only.
     * Not a global advisor intent classifier: it never chooses a product,
     * provider, or location, and is deliberately conservative.
     */
    private fun hasExplicitLocationsIntent(userText: String): Boolean {
        val text = norm(userText)
        val locationText = normLocationScope(userText)
        // Advice plus a place name is NOT an inventory request.
        val adviceTopic = Regex(
            """\b(zamontowac|montaz|montazu|podlaczyc|podlaczenie|odpowiednik|zamiennik|polec|dobierz|kompatybilnosc|kompatybilny)\b""",
        ).containsMatchIn(text)
        val explicitInventoryTerms = Regex(
            """\b(stan|stany|dostepnosc|dostepny|dostepna|dostepne|zapasy|zapas|magazynach)\b""",
        ).containsMatchIn(text)
        if (adviceTopic && !explicitInventoryTerms) return false
        val multiLocation = Regex(
            """\b(jeszcze|inne|innych|innym|pozostale|pozostalych|wszystkie|wszystkich|oddzialach|marketach)\b""",
        ).containsMatchIn(text)
        val stockOrAvailability = Regex(
            """\b(stan|stany|stanow|zapas|zapasy|dostepnosc|dostepny|dostepne|dostepna|dostepnych|magazynach|maja|jest|sprawdz|sprawdzcie)\b""",
        ).containsMatchIn(text)
        val locationNoun = Regex(
            """\b(market|markety|marketach|sklep|sklepy|sklepach|obi|oddzial|oddzialy|oddzialach|lokalizacjach)\b""",
        ).containsMatchIn(text)
        val check = Regex("""\b(sprawdz|sprawdzcie|zweryfikuj|weryfikuj|podaj|pokaz)\b""")
            .containsMatchIn(text)
        val where = Regex("""\b(gdzie|ktore|w ktorych|jakich)\b""")
            .containsMatchIn(text)
        val explicitlyLocated = Regex("""\b(?:w|we|dla)\s+[a-z0-9]""")
            .containsMatchIn(text)
        // A short "Sprawdź Kraków [i Tarnów]" is a location-scoped check.
        // It is NOT broad-network permission: authorization still has to
        // resolve each locality against the canonical directory.
        val explicitCityPhrase = Regex(
            """^(?:sprawdz|sprawdzcie)\s+[a-z]{4,}(?:\s+[a-z]{3,})?(?:\s*(?:,|i|oraz)\s+[a-z]{4,}(?:\s+[a-z]{3,})?)*\s*$""",
        ).containsMatchIn(locationText)
        return (multiLocation && (stockOrAvailability || locationNoun || where)) ||
            (locationNoun && (check || where) && stockOrAvailability) ||
            (check && explicitlyLocated) ||
            (stockOrAvailability && explicitlyLocated) ||
            (check && explicitCityPhrase) ||
            (where && stockOrAvailability && locationNoun)
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
     * market ID BEFORE inventory; model hints can only contradict, not grant.
     * A broad query is chosen only when no restricted locations were named.
     */
    private fun authorizedObiLocations(
        userText: String,
        modelHints: List<String>,
        directory: List<ProviderBranch>,
        selected: BranchId,
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
            """\b(?:market\w*|obi)\s+w\s+([a-z ]+?)\s+([0-9]{3})(?:\s+bodajze)?\s*$""",
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
            val cityAliases = obiCityAliases(directory)
            for (hint in modelHints) {
                // Hints are untrusted; normalizing a city or labelled market
                // only establishes equivalence to the *already* user-authorized
                // canonical branches. It never adds or removes requested IDs.
                val candidates = resolveModelLocationHint(hint, directory, cityAliases)
                if (candidates.isNullOrEmpty() || !requested.containsAll(candidates)) {
                    return AuthorizedLocationScope.Rejected("location_not_authorized")
                }
            }
            return AuthorizedLocationScope.Accepted(
                directory.map { it.branchId }.filter { it in requested },
                fullNetwork = false,
            )
        }
        if (!broadRequested) return AuthorizedLocationScope.Rejected("unknown_location")
        return AuthorizedLocationScope.Accepted(
            directory.map { it.branchId }.filterNot { it == selected },
            fullNetwork = true,
        )
    }

    /**
     * Single canonical-city alias source, reused by user restriction parsing
     * and model-hint compatibility checks. Never inferred from the model.
     */
    private fun obiCityAliases(
        directory: List<ProviderBranch>,
    ): Map<String, List<ProviderBranch>> {
        val cities = directory.groupBy { norm(it.name) }
        val aliases = linkedMapOf<String, List<ProviderBranch>>()
        for ((name, branches) in cities) {
            aliases[name] = branches
            cityLocative(name)?.let { aliases[it] = branches }
        }
        return aliases
    }

    private fun resolveModelLocationHint(
        rawHint: String,
        directory: List<ProviderBranch>,
        cityAliases: Map<String, List<ProviderBranch>>,
    ): Set<BranchId>? {
        val hint = norm(rawHint)
        // A city hint refers to every canonical store in that city, whether
        // canonical or inflected; this does not narrow the user scope.
        cityAliases[hint]?.let { return it.mapTo(linkedSetOf()) { branch -> branch.branchId } }
        val byId = directory.associateBy { it.branchId.value }
        byId[hint]?.let { return setOf(it.branchId) }
        // Use the *same* explicit market-label grammar as user-scope parsing.
        // "OBI 003" is a store hint, whereas a product's "100 cm" is not.
        val marketLabel = marketLabelPattern.find(hint)
        if (marketLabel != null && marketLabel.range.first == 0) {
            val id = hint.substring(marketLabel.range.last + 1).trim()
            if (!Regex("""[0-9]{3}""").matches(id)) return null
            return byId[id]?.let { setOf(it.branchId) }
        }
        // Keep previous exact-address compatibility; no substring guessing.
        val matchingAddresses = directory.filter { norm(it.address.orEmpty()) == hint }
        return matchingAddresses.takeIf { it.isNotEmpty() }
            ?.mapTo(linkedSetOf()) { it.branchId }
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
            """^(?:[a-z]+\s+){0,2}(?:marketach|markecie|markety|sklepach|sklepie|oddzialach|oddziale|lokalizacjach|obi)\b""",
        )
        val prepositions = Regex("""\b(?:w|we|dla)\s+""")
        for (match in prepositions.findAll(text)) {
            val suffix = text.substring(match.range.last + 1)
            if (placeNoun.containsMatchIn(suffix) ||
                Regex("""^ktorych\b""").containsMatchIn(suffix)
            ) continue
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

    /** Only deterministic canonical name inflections: no fuzzy city guesses. */
    private fun cityLocative(canonical: String): String? = when (canonical) {
        "nowy sacz" -> "nowym saczu"
        "miejsce piastowe" -> "miejscu piastowym"
        "lodz" -> "lodzi"
        "wroclaw" -> "wroclawiu"
        "gdansk" -> "gdansku"
        "poznan" -> "poznaniu"
        "torun" -> "toruniu"
        "lublin" -> "lublinie"
        "dabrowa gornicza" -> "dabrowie gorniczej"
        "gorzow wielkopolski" -> "gorzowie wielkopolskim"
        else -> when {
            canonical.endsWith("ow") -> canonical.dropLast(2) + "owie"
            canonical.endsWith("awa") -> canonical.dropLast(1) + "ie"
            canonical.endsWith("ansk") -> canonical + "u"
            else -> null
        }
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

    private fun boundedMention(haystack: String, phrase: String): Boolean {
        if (phrase.isBlank()) return false
        val found = haystack.indexOf(phrase)
        if (found < 0) return false
        val before = found == 0 || !haystack[found - 1].isLetterOrDigit()
        val after = found + phrase.length == haystack.length ||
            !haystack[found + phrase.length].isLetterOrDigit()
        return before && after
    }

    private fun LocationCoverageKind.asWire(): String =
        name.lowercase()

    private fun empty(
        providerId: String,
        productId: String?,
        status: String,
        reason: String,
        scope: LocationCoverage? = null,
    ) = AdvisorLocationEvidence(
        providerId = providerId,
        productId = productId,
        status = status,
        reason = reason,
        coverage = scope?.kind?.asWire() ?: "unknown",
        checkedIds = scope?.requestedIds?.map { it.value } ?: emptyList(),
        returnedIds = emptyList(),
        missingIds = scope?.requestedIds?.map { it.value } ?: emptyList(),
        locations = emptyList(),
        verifiedAtMillis = null,
        centralStock = null,
    )
}
