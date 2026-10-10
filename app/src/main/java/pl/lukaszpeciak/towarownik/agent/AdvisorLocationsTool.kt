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
        return (multiLocation && (stockOrAvailability || locationNoun || where)) ||
            (locationNoun && (check || where) && stockOrAvailability) ||
            (check && explicitlyLocated) ||
            (stockOrAvailability && explicitlyLocated) ||
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
     * User-specified cities authorize ALL canonical market IDs in that city.
     * Explicit market numbers authorize only those exact canonical IDs.
     * No user-requested restriction => ALL OTHER canonical markets.
     *
     * Model hints may be checked for contradictions but never select the subset.
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
        val normalized = norm(userText)
        // Only identifiers explicitly preceded by store/market/branch
        // wording are location references. "listwa 100 cm" is NOT store 100.
        val numericIds = Regex(
            """\b(?:market\w*|sklep\w*|oddzial\w*|obi)(?:\s+obi)?(?:\s+(?:nr|numer))?\s+([0-9]{3})\b""",
        ).findAll(normalized).map { it.groupValues[1] }.toSet()
        if (numericIds.any { id -> directory.none { it.branchId.value == id } }) {
            return AuthorizedLocationScope.Rejected("unknown_location")
        }
        val discovered = linkedSetOf<BranchId>()
        directory.filter { it.branchId.value in numericIds }
            .forEach { discovered.add(it.branchId) }
        val groupedCities = directory.groupBy { norm(it.name) }
        for ((city, matches) in groupedCities) {
            if (!cityMention(normalized, city)) continue
            // An explicit street permits market-specific scoping; the bare city
            // intentionally includes every store in that city.
            val streetMatches = matches.filter { branch ->
                val words = norm(branch.address.orEmpty()).split(" ")
                    .filter { it.length >= 5 && it !in setOf("ulica", "aleja") }
                words.any { boundedMention(normalized, it) }
            }
            val selectedInCity = if (streetMatches.isNotEmpty()) streetMatches else matches
            selectedInCity.forEach { discovered.add(it.branchId) }
        }
        // Explicitly including the selected store in a broad all-other
        // request must not turn the entire operation into a one-store check.
        val includeCurrentWithOthers =
            selected.value in numericIds &&
                (boundedMention(normalized, "rowniez") ||
                    boundedMention(normalized, "takze") ||
                    normalized.contains("razem z")) &&
                (normalized.contains("inne") ||
                    normalized.contains("pozostal") ||
                    normalized.contains("wszystk"))
        if (includeCurrentWithOthers) {
            return AuthorizedLocationScope.Accepted(
                directory.map { it.branchId }, fullNetwork = true,
            )
        }
        val restricted = discovered.isNotEmpty()
        // Explicit unknown city scopes must fail closed even when a model
        // deliberately sends locations=[]; never infer a nationwide request.
        if (!restricted && hasUnresolvedCityScope(normalized, groupedCities.keys)) {
            return AuthorizedLocationScope.Rejected("unknown_location")
        }
        if (restricted) {
            // A multi-city request must not silently drop an unrecognized
            // second city just because one of the cities is canonical.
            val knownCityPhrases = groupedCities.keys.flatMap { city ->
                listOfNotNull(city, cityLocative(city))
            }.toSet()
            val joinedLocations = Regex("""\bi (?:w |we )?([a-z0-9]+)\b""")
                .findAll(normalized).map { it.groupValues[1] }.toList()
            if (joinedLocations.any { next ->
                next !in knownCityPhrases &&
                    next !in numericIds &&
                    directory.none { it.branchId.value == next }
            }) {
                return AuthorizedLocationScope.Rejected("unknown_location")
            }
            // Model-provided cities/IDs are NOT permission to expand scope.
            for (hint in modelHints) {
                val name = norm(hint)
                val candidates = directory.filter { branch ->
                    branch.branchId.value == hint ||
                        norm(branch.name) == name ||
                        norm(branch.address.orEmpty()) == name
                }
                if (candidates.isEmpty() || candidates.any { it.branchId !in discovered }) {
                    return AuthorizedLocationScope.Rejected("location_not_authorized")
                }
            }
            return AuthorizedLocationScope.Accepted(
                directory.map { it.branchId }.filter { it in discovered },
                fullNetwork = false,
            )
        }
        // A full-network scan requires user wording that actually
        // requests other/all stores; model hints can neither authorize
        // nor silently narrow it.
        val explicitOtherScope = listOf(
            "jeszcze", "inne", "innych", "innym", "pozostal",
            "wszystk", "ktore market", "jakich market",
        ).any { normalized.contains(it) }
        if (!explicitOtherScope) {
            return AuthorizedLocationScope.Rejected("unknown_location")
        }
        val allOther = directory.map { it.branchId }.filterNot { it == selected }
        return AuthorizedLocationScope.Accepted(allOther, fullNetwork = true)
    }

    /**
     * Restriction-only parser: looks for an explicit locality after
     * "sprawdź w/we", "dostępny w" or "sprawdź [city]".
     * Unknown or unsupported localities are rejected even if the model
     * supplies an empty locations[] array. No general intent inference.
     */
    private fun hasUnresolvedCityScope(
        text: String,
        knownCities: Set<String>,
    ): Boolean {
        val knownAliases = knownCities.flatMap { city ->
            listOfNotNull(city, cityLocative(city))
        }
        val generic = setOf(
            "inne", "innych", "innym", "pozostalych", "wszystkich",
            "marketach", "markecie", "market", "sklepie", "sklepach",
            "oddzialach", "oddziale", "lokalizacjach", "obi",
            "jakich", "ktorych", "tych",
        )
        // Restriction syntax only; the preceding intent gate already
        // establishes that this is an availability operation.
        val afterPreposition = Regex("""\b(?:w|we|dla)\s+([a-z][a-z0-9]*)\b""")
            .findAll(text).map { it.groupValues[1] }
        val afterCheck = Regex("""\b(?:sprawdz|sprawdzcie)\s+([a-z][a-z0-9]*)\b""")
            .findAll(text).map { it.groupValues[1] }
            .filterNot { it in setOf(
                "w", "we", "inne", "innych", "stany", "stan", "dostepnosc",
                "produkt", "produktu", "ten", "tego", "ta", "te",
            ) }
        return (afterPreposition + afterCheck).any { candidate ->
            candidate !in generic &&
                knownAliases.none { alias ->
                    alias == candidate || alias.startsWith(candidate + " ")
                }
        }
    }

    /** Polish locative forms are only for scope matching, not a global
     *  Advisor intent classifier or branch picker.
     */
    private fun cityMention(user: String, canonical: String): Boolean =
        boundedMention(user, canonical) ||
            cityLocative(canonical)?.let { boundedMention(user, it) } == true

    private fun cityLocative(canonical: String): String? = when {
        canonical.endsWith("ow") -> canonical.dropLast(2) + "owie"
        canonical.endsWith("awa") -> canonical.dropLast(1) + "ie"
        canonical.endsWith("ansk") -> canonical + "u"
        else -> null
    }

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
