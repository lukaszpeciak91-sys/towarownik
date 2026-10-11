package pl.lukaszpeciak.towarownik.product.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl

/**
 * One request per exact product, never a per-department fanout.
 *
 * 2026-10-11 live research #111 confirmed the single query extended=1 for
 * exact products 580/7027. Keep the one-product, one-GET, fail-closed contract.
 */
internal class KwantProductLocationsAdapter(
    private val fetcher: LocationsHttpFetcher = OkHttpLocationsFetcher(),
    private val directory: () -> ProviderBranchResult = KwantProductProvider()::branches,
    private val directoryDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : ProductLocationsAdapter {
    override val providerId: ProviderId = KWANT_PROVIDER_ID

    override suspend fun read(
        ref: ProductRef,
        requested: List<BranchId>,
        trustedProduct: ProviderProduct?,
    ): ProductLocationsResult {
        if (ref.providerId != providerId) {
            return ProductLocationsResult.Invalid(LocationFailure.WRONG_PROVIDER)
        }
        if (!ref.productId.matches(PRODUCT_ID)) {
            return ProductLocationsResult.Invalid(LocationFailure.INVALID_PRODUCT_ID)
        }
        if (requested.distinct().size != requested.size || requested.size > 100) {
            return ProductLocationsResult.Invalid(LocationFailure.INVALID_LOCATION_IDS)
        }

        // KwantProductProvider.branches() performs synchronous network I/O.
        // Never execute it on the caller/UI thread. Do not swallow cancellation.
        val branches = try {
            withContext(directoryDispatcher) {
                canonicalDirectoryOrNull(directory(), providerId)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } ?: return ProductLocationsResult.Unavailable(LocationFailure.DIRECTORY_UNAVAILABLE)
        if (branches.keys.any { !it.value.matches(DEPARTMENT_ID) } ||
            requested.any { it !in branches }) {
            return ProductLocationsResult.Invalid(LocationFailure.INVALID_LOCATION_IDS)
        }

        val url = HttpUrl.Builder()
            .scheme("https").host("services.kwant.net.pl")
            .addPathSegments("api/front/products")
            .addPathSegment(ref.productId)
            .addPathSegment("departments")
            .addQueryParameter("extended", "1")
            .build()

        return when (val response = fetcher.get(url)) {
            is LocationsHttpResult.Failure ->
                ProductLocationsResult.Unavailable(
                    response.failure,
                    coverage = unknownCoverage(branches.keys.toList()),
                )
            is LocationsHttpResult.Success -> {
                val parsed = parseKwantDepartmentResponse(
                    response.json, ref.productId, branches,
                ) ?: return ProductLocationsResult.Unavailable(
                    LocationFailure.MALFORMED_RESPONSE,
                    coverage = unknownCoverage((if (requested.isEmpty()) branches.keys.toList() else requested)),
                )
                val wanted = if (requested.isEmpty()) branches.keys.toList() else requested
                val mapped = parsed.rows.associateBy { it.branch.branchId }
                val visible = wanted.mapNotNull(mapped::get)
                val returned = visible.filter { it.stock != null }
                    .map { it.branch.branchId }
                val coverage = LocationCoverage(
                    kind = when {
                        returned.size != wanted.size -> LocationCoverageKind.PARTIAL
                        requested.isNotEmpty() -> LocationCoverageKind.REQUESTED_SUBSET
                        else -> LocationCoverageKind.ALL_PUBLIC_LOCATIONS
                    },
                    requestedIds = wanted,
                    returnedIds = returned,
                    requestCount = 1,
                )
                if (returned.isEmpty()) {
                    ProductLocationsResult.Unavailable(
                        LocationFailure.NO_TRUSTED_ROWS, coverage,
                    )
                } else {
                    val trustedUnit = trustedProduct?.stockUnit
                    val unit = parsed.unit?.takeIf {
                        trustedUnit == null || trustedUnit == it
                    }
                    ProductLocationsResult.Available(
                        ref = ref,
                        locations = visible,
                        coverage = coverage,
                        verifiedAtMillis = now(),
                        centralStock = separateTrustedCentralStock(ref, trustedProduct),
                        stockUnit = unit,
                    )
                }
            }
        }
    }

    private fun unknownCoverage(ids: List<BranchId>) = LocationCoverage(
        kind = LocationCoverageKind.UNKNOWN,
        requestedIds = ids,
        returnedIds = emptyList(),
        requestCount = 1,
        failedRequests = 1,
    )

    private companion object {
        val PRODUCT_ID = Regex("""[0-9]+""")
        val DEPARTMENT_ID = Regex("""[0-9]+""")
        // Safety of URL encoding only. NOT a claim about the real query value.

    }
}

/** Strictly product-bound /departments response; never use total_stock as central. */
internal data class KwantDepartmentResponse(
    val rows: List<LocationStock>,
    val unit: String?,
)

internal fun parseKwantDepartmentResponse(
    jsonText: String,
    exactProductId: String,
    branches: Map<BranchId, ProviderBranch>,
): KwantDepartmentResponse? {
    val root = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull()
        as? JsonObject ?: return null
    if ("list" !in root || root.keys.any {
        it !in setOf("list", "total_stock", "unit", "product_id")
    }) return null
    if ("product_id" in root &&
        nonnegativeJsonInteger(root["product_id"])?.toString() != exactProductId) return null
    val list = root["list"] as? JsonArray ?: return null
    if (list.size > branches.size || list.size > 100) return null
    val unitValue = root["unit"]
    val unit = if (unitValue == null || unitValue is kotlinx.serialization.json.JsonNull) {
        null
    } else {
        val raw = unitValue as? JsonPrimitive ?: return null
        if (!raw.isString) return null
        raw.content.trim().takeIf {
            it.matches(Regex("""(?:szt\\.|m|m²|m³|kg|l|opak\\.)"""))
        }
    }
    val seen = mutableSetOf<BranchId>()
    val rows = mutableListOf<LocationStock>()
    for (entry in list) {
        val row = entry as? JsonObject ?: return null
        if (row.keys.any { it !in setOf("department_id", "stock", "product_id") }) return null
        if ("product_id" in row &&
            nonnegativeJsonInteger(row["product_id"])?.toString() != exactProductId) return null
        val idRaw = row["department_id"] as? JsonPrimitive ?: return null
        if (idRaw.isString || !idRaw.content.matches(Regex("""[0-9]+"""))) return null
        val id = BranchId(idRaw.content)
        val branch = branches[id] ?: return null
        if (!seen.add(id)) return null
        val rawStock = row["stock"]
        val stock = if (rawStock == null || rawStock is kotlinx.serialization.json.JsonNull) {
            null
        } else nonnegativeJsonInteger(rawStock) ?: return null
        rows += LocationStock(branch, stock)
    }
    return KwantDepartmentResponse(rows, unit)
}

/** Backward-compatible strict row parser used by existing unit tests. */
internal fun parseKwantDepartmentRows(
    jsonText: String,
    exactProductId: String,
    branches: Map<BranchId, ProviderBranch>,
): List<LocationStock>? =
    parseKwantDepartmentResponse(jsonText, exactProductId, branches)?.rows
