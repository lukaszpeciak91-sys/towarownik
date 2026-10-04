package pl.lukaszpeciak.towarownik.product.provider

import java.math.BigDecimal
import pl.lukaszpeciak.towarownik.product.TechnicalFact

@JvmInline
internal value class ProviderId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

@JvmInline
internal value class BranchId(
    val value: String,
) {
    init {
        require(value.isNotBlank())
    }
}

internal data class WorkingProfile(
    val providerId: ProviderId,
    val branchId: BranchId,
)

internal data class ProductRef(
    val providerId: ProviderId,
    val productId: String,
) {
    init {
        require(productId.isNotBlank())
    }
}

internal data class ProviderProductCandidate(
    val ref: ProductRef,
    val name: String?,
    val articleNumber: String? = null,
)

internal data class ProviderBranch(
    val branchId: BranchId,
    val name: String,
    val address: String? = null,
)

internal sealed interface ProviderBranchResult {
    data class Available(
        val branches: List<ProviderBranch>,
    ) : ProviderBranchResult

    data class Unavailable(
        val failure: ProductProviderFailure,
        internal val reason: String,
    ) : ProviderBranchResult
}

internal enum class ProductProviderFailure {
    NETWORK,
    NOT_FOUND,
    DATA,
}

internal enum class ProviderPriceScope {
    BRANCH,
    ONLINE,
}

internal sealed interface ProviderSearchResult {
    data class Candidates(
        val items: List<ProviderProductCandidate>,
        val reportedTotalCount: Int? = null,
    ) : ProviderSearchResult

    data object NotFound : ProviderSearchResult

    data class Unavailable(
        val failure: ProductProviderFailure,
        internal val reason: String,
    ) : ProviderSearchResult
}

internal data class ProviderProduct(
    val ref: ProductRef,
    val branchId: BranchId,
    val name: String,
    val stock: Int?,
    val grossPrice: BigDecimal?,
    val priceScope: ProviderPriceScope?,
    val productUrl: String,
    val ean: String?,
    val articleNumber: String? = null,
    val brand: String? = null,
    val shortDescription: String? = null,
    val technicalFacts: List<TechnicalFact> = emptyList(),
    val primaryImageUrl: String? = null,
)

internal sealed interface ProviderLookupResult {
    data class Found(
        val product: ProviderProduct,
    ) : ProviderLookupResult

    data class WrongProvider(
        val ref: ProductRef,
    ) : ProviderLookupResult

    data class InvalidProductId(
        val ref: ProductRef,
    ) : ProviderLookupResult

    data class InvalidBranch(
        val branchId: BranchId,
    ) : ProviderLookupResult

    data class Unavailable(
        val failure: ProductProviderFailure,
        internal val reason: String,
    ) : ProviderLookupResult
}

internal interface ProductProvider {
    val providerId: ProviderId

    fun branches(): ProviderBranchResult

    fun search(
        query: String,
        maxResults: Int,
    ): ProviderSearchResult

    fun lookup(
        ref: ProductRef,
        branchId: BranchId,
    ): ProviderLookupResult
}

internal class UnknownProductProviderException(
    val providerId: ProviderId,
) : IllegalArgumentException(
    "Unknown product provider: " + providerId.value,
)

internal class ProductProviderRegistry(
    providers: Iterable<ProductProvider>,
) {
    private val providersById: Map<ProviderId, ProductProvider>

    init {
        val entries = providers.toList()
        providersById = entries.associateBy(ProductProvider::providerId)
        require(providersById.size == entries.size) {
            "Duplicate product provider id"
        }
    }

    fun resolve(providerId: ProviderId): ProductProvider =
        providersById[providerId]
            ?: throw UnknownProductProviderException(providerId)

    companion object {
        fun production(): ProductProviderRegistry =
            ProductProviderRegistry(
                listOf(
                    ObiProductProvider(),
                    KwantProductProvider(),
                ),
            )
    }
}
