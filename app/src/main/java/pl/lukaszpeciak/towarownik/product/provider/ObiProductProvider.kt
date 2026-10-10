package pl.lukaszpeciak.towarownik.product.provider

import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.ManualProductSearchResult
import pl.lukaszpeciak.towarownik.product.ProductLookupFailure
import pl.lukaszpeciak.towarownik.product.ProductLookupRepository
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchRepository
import pl.lukaszpeciak.towarownik.product.ProductSearchResult
import pl.lukaszpeciak.towarownik.product.OBI_STORES

internal val OBI_PROVIDER_ID = ProviderId("obi-pl")

internal val DEFAULT_WORKING_PROFILE = WorkingProfile(
    providerId = OBI_PROVIDER_ID,
    branchId = BranchId(DEFAULT_OBI_STORE_NUMBER),
)

internal class ObiProductProvider(
    private val boundedSearch: (String) -> ProductSearchResult =
        ProductSearchRepository()::search,
    private val manualSearch: (String) -> ManualProductSearchResult =
        ProductSearchRepository()::searchManual,
    private val lookupObik: (String, String) -> ProductLookupResult =
        { obik, storeNumber ->
            ProductLookupRepository().lookupObik(
                obik = obik,
                storeNumber = storeNumber,
            )
        },
) : ProductProvider {
    override val providerId: ProviderId = OBI_PROVIDER_ID

    override fun branches(): ProviderBranchResult =
        ProviderBranchResult.Available(
            branches = OBI_STORES.map { store ->
                ProviderBranch(
                    branchId = BranchId(store.storeNumber),
                    name = store.city,
                    address = store.address,
                )
            },
        )

    override fun search(
        query: String,
        maxResults: Int,
    ): ProviderSearchResult {
        require(maxResults > 0)

        return if (maxResults <= OBI_BOUNDED_SEARCH_LIMIT) {
            boundedSearch(query).toProviderResult(
                maxResults = maxResults,
            )
        } else {
            manualSearch(query).toProviderResult(
                maxResults = maxResults,
            )
        }
    }

    override fun lookup(
        ref: ProductRef,
        branchId: BranchId,
    ): ProviderLookupResult {
        if (ref.providerId != providerId) {
            return ProviderLookupResult.WrongProvider(ref)
        }

        return lookupObik(
            ref.productId,
            branchId.value,
        ).toProviderResult(
            ref = ref,
        )
    }

    private companion object {
        const val OBI_BOUNDED_SEARCH_LIMIT = 5
    }
}

private fun ProductSearchResult.toProviderResult(
    maxResults: Int,
): ProviderSearchResult =
    when (this) {
        is ProductSearchResult.Candidates ->
            ProviderSearchResult.Candidates(
                items = items
                    .take(maxResults)
                    .map { candidate ->
                        ProviderProductCandidate(
                            ref = ProductRef(
                                providerId = OBI_PROVIDER_ID,
                                productId = candidate.obik,
                            ),
                            name = candidate.name,
                            articleNumber = null,
                        )
                    },
                reportedTotalCount = reportedTotalCount,
            )

        ProductSearchResult.NotFound ->
            ProviderSearchResult.NotFound

        is ProductSearchResult.Unavailable ->
            ProviderSearchResult.Unavailable(
                failure = failure.toProviderFailure(),
                reason = reason,
            )
    }

private fun ManualProductSearchResult.toProviderResult(
    maxResults: Int,
): ProviderSearchResult =
    when (this) {
        is ManualProductSearchResult.Candidates ->
            ProviderSearchResult.Candidates(
                items = items
                    .take(maxResults)
                    .map { candidate ->
                        ProviderProductCandidate(
                            ref = ProductRef(
                                providerId = OBI_PROVIDER_ID,
                                productId = candidate.obik,
                            ),
                            name = candidate.name,
                        )
                    },
                reportedTotalCount = reportedTotalCount,
            )

        ManualProductSearchResult.NotFound ->
            ProviderSearchResult.NotFound

        is ManualProductSearchResult.Unavailable ->
            ProviderSearchResult.Unavailable(
                failure = failure.toProviderFailure(),
                reason = reason,
            )
    }

private fun ProductLookupResult.toProviderResult(
    ref: ProductRef,
): ProviderLookupResult =
    when (this) {
        is ProductLookupResult.Found ->
            ProviderLookupResult.Found(
                product = product.toProviderProduct(),
            )

        is ProductLookupResult.InvalidObik ->
            ProviderLookupResult.InvalidProductId(ref)

        is ProductLookupResult.InvalidStore ->
            ProviderLookupResult.InvalidBranch(
                branchId = BranchId(storeNumber),
            )

        is ProductLookupResult.Unavailable ->
            ProviderLookupResult.Unavailable(
                failure = failure.toProviderFailure(),
                reason = reason,
            )
    }

private fun LocalProduct.toProviderProduct(): ProviderProduct =
    ProviderProduct(
        ref = ProductRef(
            providerId = OBI_PROVIDER_ID,
            productId = obik,
        ),
        branchId = BranchId(storeNumber),
        name = name,
        stock = stock,
        stockUnit = stockUnit,
        grossPrice = grossPrice,
        priceScope = grossPrice?.let { ProviderPriceScope.BRANCH },
        productUrl = productUrl,
        ean = ean,
        brand = brand,
        shortDescription = shortDescription,
        technicalFacts = technicalFacts,
        primaryImageUrl = primaryImageUrl,
    )

private fun ProductLookupFailure.toProviderFailure():
    ProductProviderFailure =
    when (this) {
        ProductLookupFailure.NETWORK ->
            ProductProviderFailure.NETWORK
        ProductLookupFailure.NOT_FOUND ->
            ProductProviderFailure.NOT_FOUND
        ProductLookupFailure.DATA ->
            ProductProviderFailure.DATA
    }
