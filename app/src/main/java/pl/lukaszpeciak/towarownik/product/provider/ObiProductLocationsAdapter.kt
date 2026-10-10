package pl.lukaszpeciak.towarownik.product.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl

/**
 * Browser-observed B_ONE_SHOT_SUBSET stock transport. Max 10 canonical IDs per
 * HTTP request; the 20-ID logical budget is our own cost guard, NOT an OBI limit.
 */
internal class ObiProductLocationsAdapter(
    private val fetcher: LocationsHttpFetcher = OkHttpLocationsFetcher(),
    private val directory: () -> ProviderBranchResult = ObiProductProvider()::branches,
    private val now: () -> Long = System::currentTimeMillis,
) : ProductLocationsAdapter {
    override val providerId: ProviderId = OBI_PROVIDER_ID

    override suspend fun read(
        ref: ProductRef,
        requested: List<BranchId>,
        trustedProduct: ProviderProduct?,
    ): ProductLocationsResult {
        if (ref.providerId != providerId) {
            return ProductLocationsResult.Invalid(LocationFailure.WRONG_PROVIDER)
        }
        if (!ref.productId.matches(OBIK)) {
            return ProductLocationsResult.Invalid(LocationFailure.INVALID_PRODUCT_ID)
        }
        if (requested.isEmpty() || requested.size > MAX_LOCATIONS ||
            requested.distinct().size != requested.size) {
            return ProductLocationsResult.Invalid(LocationFailure.INVALID_LOCATION_IDS)
        }
        val branches = canonicalDirectoryOrNull(directory(), providerId)
            ?: return ProductLocationsResult.Unavailable(LocationFailure.DIRECTORY_UNAVAILABLE)
        if (requested.any { it !in branches || !it.value.matches(MARKET_ID) }) {
            return ProductLocationsResult.Invalid(LocationFailure.INVALID_LOCATION_IDS)
        }

        val found = linkedMapOf<BranchId, LocationStock>()
        var failed = 0
        var lastFailure = LocationFailure.NO_TRUSTED_ROWS
        var attempts = 0
        for (batch in requested.chunked(MAX_BATCH)) {
            attempts++
            val url = HttpUrl.Builder()
                .scheme("https").host("www.obi.pl")
                .addPathSegments("api/pdp/v1/stock")
                .addPathSegment(ref.productId)
                .addQueryParameter("storeIds", batch.joinToString(",") { it.value })
                .build()
            val result = fetcher.get(url)
            when (result) {
                is LocationsHttpResult.Failure -> {
                    failed++
                    lastFailure = result.failure
                }
                is LocationsHttpResult.Success -> {
                    val parsed = parseObiStockBatch(result.json, batch.toSet(), branches)
                    if (parsed == null) {
                        failed++
                        lastFailure = LocationFailure.MALFORMED_RESPONSE
                    } else {
                        parsed.forEach { row -> found[row.branch.branchId] = row }
                    }
                }
            }
        }
        val coverage = LocationCoverage(
            kind = if (failed == 0 && found.size == requested.size) {
                LocationCoverageKind.REQUESTED_SUBSET
            } else if (found.isNotEmpty()) {
                LocationCoverageKind.PARTIAL
            } else {
                LocationCoverageKind.UNKNOWN
            },
            requestedIds = requested,
            returnedIds = requested.filter { it in found },
            requestCount = attempts,
            failedRequests = failed,
        )
        if (found.isEmpty()) {
            return ProductLocationsResult.Unavailable(lastFailure, coverage)
        }
        return ProductLocationsResult.Available(
            ref = ref,
            locations = requested.mapNotNull(found::get),
            coverage = coverage,
            verifiedAtMillis = now(),
        )
    }

    private companion object {
        const val MAX_BATCH = 10
        const val MAX_LOCATIONS = 20
        val OBIK = Regex("""[0-9]{7}""")
        val MARKET_ID = Regex("""[0-9]{3}""")
    }
}

/** No partial trust in a malformed batch: duplicate/foreign IDs reject the batch. */
internal fun parseObiStockBatch(
    jsonText: String,
    requestedIds: Set<BranchId>,
    directory: Map<BranchId, ProviderBranch>,
): List<LocationStock>? {
    val root = runCatching { Json.parseToJsonElement(jsonText) }.getOrNull()
        as? JsonArray ?: return null
    if (root.size > requestedIds.size || root.size > 10) return null
    val seen = mutableSetOf<BranchId>()
    val rows = mutableListOf<LocationStock>()
    for (element in root) {
        val record = element as? JsonObject ?: return null
        if (record.keys != setOf("storeId", "availableQuantity")) return null
        val idValue = record["storeId"] as? JsonPrimitive ?: return null
        if (!idValue.isString) return null
        if (idValue.content.length != 3 || idValue.content.any { !it.isDigit() }) return null
        val id = BranchId(idValue.content)
        if (
            id !in requestedIds || !seen.add(id)) return null
        val branch = directory[id] ?: return null
        val stock = nonnegativeJsonInteger(record["availableQuantity"]) ?: return null
        rows += LocationStock(branch, stock)
    }
    return rows
}

internal fun nonnegativeJsonInteger(value: kotlinx.serialization.json.JsonElement?): Int? {
    val p = value as? JsonPrimitive ?: return null
    if (p.isString || !p.content.matches(Regex("""0|[1-9][0-9]*"""))) return null
    return p.content.toIntOrNull()?.takeIf { it >= 0 }
}
