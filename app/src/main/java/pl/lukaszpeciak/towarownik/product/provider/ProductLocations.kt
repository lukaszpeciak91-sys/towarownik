package pl.lukaszpeciak.towarownik.product.provider

/**
 * Read-only inventory facts for an exact provider-owned product.
 *
 * Product trust/previous-turn authorization belongs to the future Advisor integration.
 * This service never discovers products, changes branches, or writes WorkingProfile.
 */
internal data class LocationStock(
    val branch: ProviderBranch,
    val stock: Int?,
)

internal enum class LocationCoverageKind {
    ALL_PUBLIC_LOCATIONS,
    REQUESTED_SUBSET,
    PARTIAL,
    UNKNOWN,
}

internal data class LocationCoverage(
    val kind: LocationCoverageKind,
    val requestedIds: List<BranchId>,
    val returnedIds: List<BranchId>,
    val requestCount: Int,
    val failedRequests: Int = 0,
) {
    val missingIds: List<BranchId>
        get() = requestedIds.filterNot { it in returnedIds }
}

internal enum class LocationFailure {
    WRONG_PROVIDER,
    INVALID_PRODUCT_ID,
    INVALID_LOCATION_IDS,
    DIRECTORY_UNAVAILABLE,
    UNVERIFIED_REQUEST_CONTRACT,
    TRANSPORT,
    MALFORMED_RESPONSE,
    NO_TRUSTED_ROWS,
}

internal sealed interface ProductLocationsResult {
    data class Available(
        val ref: ProductRef,
        val locations: List<LocationStock>,
        val coverage: LocationCoverage,
        val verifiedAtMillis: Long,
        /** Only separately verified exact-product context may supply this; never departments.total_stock. */
        val centralStock: Int? = null,
    ) : ProductLocationsResult

    data class Invalid(val failure: LocationFailure) : ProductLocationsResult

    data class Unavailable(
        val failure: LocationFailure,
        val coverage: LocationCoverage? = null,
    ) : ProductLocationsResult

    data object UnsupportedProvider : ProductLocationsResult
}

internal interface ProductLocationsAdapter {
    val providerId: ProviderId
    suspend fun read(
        ref: ProductRef,
        requested: List<BranchId>,
        trustedProduct: ProviderProduct? = null,
    ): ProductLocationsResult
}

/** Not registered in Advisor/Worker in PR 1. */
internal class ProductLocationsService(
    adapters: List<ProductLocationsAdapter>,
) {
    private val byProvider = adapters.associateBy { it.providerId }

    init {
        require(byProvider.size == adapters.size) { "Duplicate location provider" }
    }

    suspend fun read(
        ref: ProductRef,
        requested: List<BranchId> = emptyList(),
        trustedProduct: ProviderProduct? = null,
    ): ProductLocationsResult =
        byProvider[ref.providerId]?.read(ref, requested, trustedProduct)
            ?: ProductLocationsResult.UnsupportedProvider

    companion object {
        fun production(): ProductLocationsService = ProductLocationsService(
            listOf(ObiProductLocationsAdapter(), KwantProductLocationsAdapter()),
        )
    }
}

internal fun separateTrustedCentralStock(
    ref: ProductRef,
    trustedProduct: ProviderProduct?,
): Int? = trustedProduct
    ?.takeIf { it.ref == ref && it.centralStock != null && it.centralStock >= 0 }
    ?.centralStock

internal fun canonicalDirectoryOrNull(
    branches: ProviderBranchResult,
    expectedProvider: ProviderId,
): Map<BranchId, ProviderBranch>? {
    // The argument's provider is determined by the provider-specific directory supplier.
    // A missing, duplicate or malformed directory cannot authorize inventory identities.
    @Suppress("UNUSED_VARIABLE")
    val provider = expectedProvider
    val list = (branches as? ProviderBranchResult.Available)?.branches ?: return null
    if (list.isEmpty() || list.size > 100 || list.any {
            it.branchId.value.isBlank() || it.name.isBlank()
        }) return null
    val map = list.associateBy { it.branchId }
    return map.takeIf { it.size == list.size }
}
