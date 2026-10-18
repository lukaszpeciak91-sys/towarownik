package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.KwantProductProvider
import pl.lukaszpeciak.towarownik.product.provider.LocationCoverage
import pl.lukaszpeciak.towarownik.product.provider.LocationCoverageKind
import pl.lukaszpeciak.towarownik.product.provider.LocationFailure
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ObiProductProvider
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsResult
import pl.lukaszpeciak.towarownik.product.provider.ProductLocationsService
import pl.lukaszpeciak.towarownik.product.provider.ProductProviderRegistry
import pl.lukaszpeciak.towarownik.product.provider.ProductRef
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.ProviderLookupResult
import pl.lukaszpeciak.towarownik.product.provider.ProductProviderFailure
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct

/**
 * Fresh inventory executor. Android resolves typed product/scope authority before execution.
 * Historical verified snapshots supply identity only, never fresh stock.
 */
internal class AdvisorLocationsTool(
    private val locationsService: ProductLocationsService = ProductLocationsService.production(),
    private val obiBranches: () -> ProviderBranchResult = ObiProductProvider()::branches,
    private val kwantBranches: () -> ProviderBranchResult = KwantProductProvider()::branches,
    private val scopeExecutionBudgetMillis: Long = 45_000L,
    private val providers: ProductProviderRegistry = ProductProviderRegistry.production(),
    private val now: () -> Long = System::currentTimeMillis,
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
        history: List<AdvisorLocationHistoryMessage> = emptyList(),
    ): AdvisorLocationEvidence {
        val directory = try {
            if (providerId == OBI_PROVIDER_ID.value) (obiBranches() as? ProviderBranchResult.Available)?.branches
            else withContext(Dispatchers.IO) { (kwantBranches() as? ProviderBranchResult.Available)?.branches }
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
        return when (val authority = AdvisorInventoryAuthorizer.resolve(
            arguments, providerId, currentBranchId, userText, currentVerified, historicalVerified,
            directory.orEmpty(), history,
        )) {
            is AdvisorInventoryAuthorization.Inventory -> execute(authority)
            is AdvisorInventoryAuthorization.ClarificationRequired -> empty(providerId, null, "rejected", authority.reason)
            is AdvisorInventoryAuthorization.Denied -> empty(providerId, null,
                if (authority.reason == "directory_unavailable" || authority.reason == "multi_product_tool_contract") "unavailable" else "rejected", authority.reason)
            is AdvisorInventoryAuthorization.Discovery -> empty(providerId, null, "rejected", "untrusted_product")
        }
    }

    /** Executes trusted IDs/scope only. No USER wording or model hint is interpreted here. */
    suspend fun execute(authority: AdvisorInventoryAuthorization.Inventory): AdvisorLocationEvidence {
        val providerId = authority.provider.value
        val selected = authority.product
        val directory = authority.directory
        val exactRef = ProductRef(authority.provider, selected.effectiveProductId)
        fun rejected(reason: String, trustedId: String? = exactRef.productId) =
            empty(providerId, trustedId, "rejected", reason)
        if (!authority.fullNetwork && authority.branches.size == 1) {
            return readSingleBranch(exactRef, authority)
        }
        val trustedContext = ProviderProduct(
            ref = exactRef,
            branchId = BranchId(selected.effectiveBranchId),
            name = selected.name,
            stock = selected.stock,
            // An old product card only authorizes ID, not current central stock.
            centralStock = authority.currentProduct?.centralStock,
            grossPrice = selected.grossPrice,
            priceScope = selected.priceScope,
            productUrl = selected.productUrl,
            ean = null,
            articleNumber = selected.articleNumber,
            stockUnit = selected.stockUnit,
        )
        if (providerId == OBI_PROVIDER_ID.value) {
            return readObiScope(exactRef, trustedContext, authority, directory)
        }
        // One product-bound GET. The adapter returns only the authorized
        // subset despite fetching the full public product-bound branch list.
        val result = try {
            locationsService.read(
                ref = exactRef,
                requested = if (authority.fullNetwork) emptyList() else authority.branches,
                trustedProduct = trustedContext,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            ProductLocationsResult.Unavailable(LocationFailure.TRANSPORT)
        }
        return when (result) {
            is ProductLocationsResult.Available -> {
                val requested = authority.branches
                val covered = result.coverage
                if (result.ref != exactRef || covered.requestedIds != requested ||
                    covered.returnedIds.any { it !in requested } ||
                    result.locations.any { it.branch.branchId !in requested } ||
                    result.locations.map { it.branch.branchId }.distinct().size !=
                        result.locations.size
                ) return rejected("malformed_response", exactRef.productId)
                val rows = result.locations.associateBy { it.branch.branchId }
                AdvisorLocationEvidence(
                providerId = providerId,
                productId = exactRef.productId,
                status = "verified",
                reason = null,
                coverage = result.coverage.kind.asWire(),
                checkedIds = result.coverage.requestedIds.map { it.value },
                returnedIds = result.coverage.returnedIds.map { it.value },
                missingIds = result.coverage.missingIds.map { it.value },
                locations = requested.map { id ->
                    val row = rows[id]
                    val branch = directory.single { it.branchId == id }
                    AdvisorLocationEntry(id.value, branch.name, row?.stock)
                }.sortedWith(compareByDescending<AdvisorLocationEntry> {
                    (it.stock ?: 0) > 0
                }.thenByDescending { it.stock ?: -1 }),
                verifiedAtMillis = result.verifiedAtMillis,
                centralStock = result.centralStock,
                stockUnit = result.stockUnit,
                httpDiagnostic = result.httpDiagnostic,
            )
            }
            is ProductLocationsResult.Unavailable -> empty(
                providerId, exactRef.productId, "unavailable",
                result.failure.name.lowercase(), result.coverage,
                // The Worker requires a canonical null-stock row for EVERY
                // checked ID, including failed, malformed and empty reads.
                directory,
            ).copy(httpDiagnostic = result.httpDiagnostic)
            is ProductLocationsResult.Invalid ->
                rejected(result.failure.name.lowercase(), exactRef.productId)
            ProductLocationsResult.UnsupportedProvider ->
                rejected("unsupported_provider", exactRef.productId)
        }.copy(authorizedBranchIds = authority.branches.map { it.value })
    }

    /** Reuse ordinary provider verification; historical facts supply identity only. */
    private suspend fun readSingleBranch(
        ref: ProductRef,
        authority: AdvisorInventoryAuthorization.Inventory,
    ): AdvisorLocationEvidence {
        val target = authority.branches.single()
        val branch = authority.directory.singleOrNull { it.branchId == target }
            ?: return empty(ref.providerId.value, ref.productId, "unavailable", "directory_unavailable")
                .copy(authorizedBranchIds = listOf(target.value))
        val name = if (ref.providerId == OBI_PROVIDER_ID) {
            listOfNotNull(branch.name, branch.address?.takeIf { it.isNotBlank() })
                .joinToString(" — ")
        } else branch.name
        val unknownScope = LocationCoverage(
            LocationCoverageKind.UNKNOWN, listOf(target), emptyList(), 1,
        )
        fun unknown(reason: String) = empty(
            ref.providerId.value, ref.productId, "unavailable", reason,
            unknownScope, authority.directory,
        ).copy(locations = listOf(AdvisorLocationEntry(target.value, name.take(100), null)))
        val result = try {
            withContext(Dispatchers.IO) {
                providers.resolve(ref.providerId).lookup(ref, target)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            return unknown("transport")
        }
        if (result !is ProviderLookupResult.Found) {
            return unknown(when (result) {
                is ProviderLookupResult.InvalidBranch -> "directory_unavailable"
                is ProviderLookupResult.Unavailable -> when (result.failure) {
                    ProductProviderFailure.NETWORK -> "transport"
                    ProductProviderFailure.DATA -> "malformed_response"
                    ProductProviderFailure.NOT_FOUND -> "no_trusted_rows"
                }
                else -> "untrusted_product"
            })
        }
        val product = result.product
        if (product.ref != ref || product.branchId != target ||
            product.stock?.let { it < 0 } == true
        ) return unknown("malformed_response")
        val stock = product.stock ?: return unknown("no_trusted_rows")
        return AdvisorLocationEvidence(
            providerId = ref.providerId.value, productId = ref.productId,
            status = "verified", reason = null, coverage = "requested_subset",
            checkedIds = listOf(target.value), returnedIds = listOf(target.value),
            missingIds = emptyList(),
            locations = listOf(AdvisorLocationEntry(target.value, name.take(100), stock)),
            verifiedAtMillis = now(),
            centralStock = product.centralStock?.takeIf { ref.providerId == KWANT_PROVIDER_ID && it >= 0 },
            stockUnit = product.stockUnit,
        )
    }

    /** The 20-market budget belongs to EACH service invocation, never a nationwide truncation.
     *  Sequential batches: 61 other stores => 4 reads => 7 bounded HTTP GETs.
     */
    private suspend fun readObiScope(
        ref: ProductRef,
        trustedProduct: ProviderProduct,
        scope: AdvisorInventoryAuthorization.Inventory,
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

    private fun LocationCoverageKind.asWire(): String =
        name.lowercase()

    private fun empty(
        providerId: String,
        productId: String?,
        status: String,
        reason: String,
        scope: LocationCoverage? = null,
        canonicalBranches: List<ProviderBranch> = emptyList(),
    ): AdvisorLocationEvidence {
        val ids = scope?.requestedIds.orEmpty()
        val directory = canonicalBranches.associateBy { it.branchId }
        // No fabricated display names. If the entire canonical mapping
        // cannot be proven, send no checked IDs (valid unknown evidence).
        val checked = ids.takeIf { requested ->
            requested.size <= 100 && requested.distinct().size == requested.size &&
                requested.all { it in directory }
        }.orEmpty()
        return AdvisorLocationEvidence(
            providerId = providerId,
            productId = productId,
            status = status,
            reason = reason,
            coverage = if (checked.size == ids.size) scope?.kind?.asWire() ?: "unknown"
                else "unknown",
            checkedIds = checked.map { it.value },
            returnedIds = emptyList(),
            missingIds = checked.map { it.value },
            locations = checked.map { id ->
                val branch = requireNotNull(directory[id])
                AdvisorLocationEntry(id.value, branch.name, null)
            },
            verifiedAtMillis = null,
            centralStock = null,
        )
    }
}

/** Only deterministic canonical name inflections: no fuzzy city guesses. */
internal fun advisorCityLocative(canonical: String): String? = when (canonical) {
    // Only exact normalized canonical city names from the verified public
    // directory. IDs are NEVER embedded here: aliases inherit the dynamic
    // runtime ProviderBranch.branchId of the matching canonical name.
    "bialystok" -> "bialymstoku"
    "bochnia" -> "bochni"
    "brzesko" -> "brzesku"
    "bydgoszcz" -> "bydgoszczy"
    "chelm" -> "chelmie"
    "dabrowa tarnowska" -> "dabrowie tarnowskiej"
    "kielce" -> "kielcach"
    "krakow" -> "krakowie"
    "krosno" -> "krosnie"
    "lublin" -> "lublinie"
    "lodz" -> "lodzi"
    "mielec" -> "mielcu"
    "nowy sacz" -> "nowym saczu"
    "ostroleka" -> "ostrolece"
    "piaseczno" -> "piasecznie"
    "rzeszow" -> "rzeszowie"
    "suwalki" -> "suwalkach"
    "tarnow" -> "tarnowie"
    "warszawa" -> "warszawie"
    "wloclawek" -> "wloclawku"
    "zamosc" -> "zamosciu"
    // Existing non-KWANT/OBI aliases stay compatible.
    "miejsce piastowe" -> "miejscu piastowym"
    "wroclaw" -> "wroclawiu"
    "gdansk" -> "gdansku"
    "poznan" -> "poznaniu"
    "torun" -> "toruniu"
    "dabrowa gornicza" -> "dabrowie gorniczej"
    "gorzow wielkopolski" -> "gorzowie wielkopolskim"
    else -> when {
        canonical.endsWith("ow") -> canonical.dropLast(2) + "owie"
        canonical.endsWith("awa") -> canonical.dropLast(1) + "ie"
        canonical.endsWith("ansk") -> canonical + "u"
        else -> null
    }
}
