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
 * The research observed the "extended" query *name*, but redacted its value.
 * Production must remain disabled until a separately verified value can be
 * supplied. In particular, do not guess true/1/false or omit the query key.
 */
internal class KwantProductLocationsAdapter(
    private val fetcher: LocationsHttpFetcher = OkHttpLocationsFetcher(),
    private val directory: () -> ProviderBranchResult = KwantProductProvider()::branches,
    private val directoryDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val verifiedExtendedValue: String? = null,
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

        // Before even loading the remote directory or sending HTTP, refuse a
        // request shape that research did not retain. Safe PR1 production default.
        val extended = verifiedExtendedValue
            ?.takeIf { it.matches(VERIFIED_VALUE_SHAPE) }
            ?: return ProductLocationsResult.Unavailable(
                LocationFailure.UNVERIFIED_REQUEST_CONTRACT,
            )

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
            .addQueryParameter("extended", extended)
            .build()

        return when (val response = fetcher.get(url)) {
            is LocationsHttpResult.Failure ->
                ProductLocationsResult.Unavailable(
                    response.failure,
                    coverage = unknownCoverage(branches.keys.toList()),
                )
            is LocationsHttpResult.Success -> {
                val rows = parseKwantDepartmentRows(
                    response.json, ref.productId, branches,
                ) ?: return ProductLocationsResult.Unavailable(
                    LocationFailure.MALFORMED_RESPONSE,
                    coverage = unknownCoverage(branches.keys.toList()),
                )
                val coverage = LocationCoverage(
                    kind = if (rows.size == branches.size) {
                        LocationCoverageKind.ALL_PUBLIC_LOCATIONS
                    } else if (rows.isEmpty()) {
                        LocationCoverageKind.UNKNOWN
                    } else {
                        LocationCoverageKind.PARTIAL
                    },
                    requestedIds = branches.keys.toList(),
                    returnedIds = branches.keys.filter { id ->
                        rows.any { it.branch.branchId == id }
                    },
                    requestCount = 1,
                )
                if (rows.isEmpty()) {
                    ProductLocationsResult.Unavailable(
                        LocationFailure.NO_TRUSTED_ROWS,
                        coverage,
                    )
                } else {
                    ProductLocationsResult.Available(
                        ref = ref,
                        locations = rows.associateBy { it.branch.branchId }.let { byId ->
                            branches.keys.mapNotNull(byId::get)
                        },
                        coverage = coverage,
                        verifiedAtMillis = now(),
                        centralStock = separateTrustedCentralStock(ref, trustedProduct),
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
        val VERIFIED_VALUE_SHAPE = Regex("""[A-Za-z0-9_-]{1,32}""")
    }
}

/** Parse only singular-product-bound canonical department_id/stock rows. */
internal fun parseKwantDepartmentRows(
    jsonText: String,
    exactProductId: String,
    branches: Map<BranchId, ProviderBranch>,
): List<LocationStock>? {
    val root = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull()
        as? JsonObject ?: return null
    if ("list" !in root) return null
    if (root.keys.any { it !in setOf("list", "total_stock", "unit", "product_id") }) return null
    if ("product_id" in root &&
        nonnegativeJsonInteger(root["product_id"])?.toString() != exactProductId) return null
    val list = root["list"] as? JsonArray ?: return null
    if (list.size > branches.size || list.size > 100) return null
    val seen = mutableSetOf<BranchId>()
    val result = mutableListOf<LocationStock>()
    for (element in list) {
        val row = element as? JsonObject ?: return null
        if ("product_id" in row &&
            nonnegativeJsonInteger(row["product_id"])?.toString() != exactProductId) return null
        val idValue = row["department_id"] as? JsonPrimitive ?: return null
        if (idValue.isString || !idValue.content.matches(Regex("""[0-9]+"""))) return null
        val id = BranchId(idValue.content)
        val branch = branches[id] ?: return null
        if (!seen.add(id)) return null
        val quantity = nonnegativeJsonInteger(row["stock"]) ?: return null
        result += LocationStock(branch, quantity)
    }
    // "total_stock" is deliberately NOT parsed, summed or treated as centralStock.
    return result
}
