package pl.lukaszpeciak.towarownik.agent

import java.text.Normalizer
import kotlinx.coroutines.CancellationException
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
) {
    suspend fun execute(
        arguments: AdvisorLocationArguments,
        providerId: String,
        userText: String,
        currentVerified: Collection<VerifiedProductSnapshot>,
        historicalVerified: List<VerifiedProductSnapshot>,
    ): AdvisorLocationEvidence {
        fun rejected(reason: String, trustedId: String? = null) = empty(
            providerId, trustedId, "rejected", reason,
        )
        if (arguments.providerId != providerId) return rejected("wrong_provider")
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
        val requested = if (providerId == OBI_PROVIDER_ID.value) {
            val directory = (obiBranches() as? ProviderBranchResult.Available)?.branches
                ?: return empty(providerId, exactRef.productId, "unavailable", "directory_unavailable")
            when (val scope = authorizedObiLocations(
                userText, arguments.locations, directory,
            )) {
                is AuthorizedLocationScope.Rejected -> return rejected(
                    scope.reason, exactRef.productId,
                )
                is AuthorizedLocationScope.Accepted -> scope.branches
            }
        } else {
            // KWANT's ONE-SHOT all-directory contract is still blocked until
            // the historical 'extended' query value can be reproduced.
            emptyList()
        }
        val trustedContext = ProviderProduct(
            ref = exactRef,
            branchId = BranchId(selected.effectiveBranchId),
            name = selected.name,
            stock = selected.stock,
            centralStock = selected.centralStock,
            grossPrice = selected.grossPrice,
            priceScope = selected.priceScope,
            productUrl = selected.productUrl,
            ean = null,
            articleNumber = selected.articleNumber,
        )
        val result = try {
            locationsService.read(
                ref = exactRef,
                requested = requested,
                trustedProduct = trustedContext,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            ProductLocationsResult.Unavailable(LocationFailure.TRANSPORT)
        }
        return when (result) {
            is ProductLocationsResult.Available -> {
                // Sorting here is presentation ordering, never an adapter policy.
                val sorted = result.locations.sortedWith(
                    compareByDescending<pl.lukaszpeciak.towarownik.product.provider.LocationStock> {
                        (it.stock ?: 0) > 0
                    }.thenByDescending { it.stock ?: -1 },
                )
                AdvisorLocationEvidence(
                    providerId = providerId,
                    productId = exactRef.productId,
                    status = "verified",
                    reason = null,
                    coverage = result.coverage.kind.asWire(),
                    checkedIds = result.coverage.requestedIds.map { it.value },
                    returnedIds = result.coverage.returnedIds.map { it.value },
                    missingIds = result.coverage.missingIds.map { it.value },
                    locations = sorted.map { AdvisorLocationEntry(
                        branchId = it.branch.branchId.value,
                        name = it.branch.name,
                        stock = it.stock,
                    ) },
                    verifiedAtMillis = result.verifiedAtMillis,
                    centralStock = result.centralStock,
                )
            }
            is ProductLocationsResult.Unavailable -> empty(
                providerId, exactRef.productId, "unavailable",
                result.failure.name.lowercase(),
                result.coverage,
            )
            is ProductLocationsResult.Invalid -> rejected(
                result.failure.name.lowercase(), exactRef.productId,
            )
            ProductLocationsResult.UnsupportedProvider -> rejected(
                "unsupported_provider", exactRef.productId,
            )
        }
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

    private sealed interface AuthorizedLocationScope {
        data class Accepted(val branches: List<BranchId>) : AuthorizedLocationScope
        data class Rejected(val reason: String) : AuthorizedLocationScope
    }

    private fun authorizedObiLocations(
        userText: String,
        modelHints: List<String>,
        directory: List<ProviderBranch>,
    ): AuthorizedLocationScope {
        val normalized = norm(userText)
        val numericIds = Regex("""(?<![0-9])[0-9]{3}(?![0-9])""")
            .findAll(userText).map { it.value }.toSet()
        val discovered = linkedSetOf<BranchId>()
        var ambiguity = false
        for (branch in directory) {
            if (branch.branchId.value in numericIds) discovered.add(branch.branchId)
        }
        // A generic city matches ALL same-city markets, thus requires an
        // explicit disambiguating street or canonical ID for each chosen one.
        val cities = directory.groupBy { norm(it.name) }
        for ((city, matches) in cities) {
            if (!boundedMention(normalized, city)) continue
            if (matches.size == 1) {
                discovered.add(matches.single().branchId)
            } else {
                val specific = matches.filter { branch ->
                    val street = norm(branch.address.orEmpty())
                        .split(" ").filter { it.length >= 5 && it !in setOf("ulica","aleja") }
                    street.any { boundedMention(normalized, it) } ||
                        branch.branchId.value in numericIds
                }
                if (specific.isEmpty()) ambiguity = true
                else specific.forEach { discovered.add(it.branchId) }
            }
        }
        if (ambiguity) return AuthorizedLocationScope.Rejected("ambiguous_location")
        if (numericIds.any { id -> directory.none { it.branchId.value == id } }) {
            return AuthorizedLocationScope.Rejected("unknown_location")
        }
        if (discovered.isEmpty()) return AuthorizedLocationScope.Rejected("scope_required")
        if (discovered.size > 20) return AuthorizedLocationScope.Rejected("too_many_locations")
        // Hints may constrain an authorized set but may never expand it.
        for (hint in modelHints) {
            val h = norm(hint)
            val matched = directory.filter {
                it.branchId.value == hint ||
                    norm(it.name) == h ||
                    norm(it.address.orEmpty()) == h
            }
            if (matched.isEmpty() ||
                matched.none { it.branchId in discovered }) {
                return AuthorizedLocationScope.Rejected("location_not_authorized")
            }
        }
        return AuthorizedLocationScope.Accepted(
            directory.map { it.branchId }.filter { it in discovered }.take(20),
        )
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
