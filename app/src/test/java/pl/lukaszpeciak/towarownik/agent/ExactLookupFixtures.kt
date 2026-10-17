package pl.lukaszpeciak.towarownik.agent

import pl.lukaszpeciak.towarownik.product.provider.*

/** Synthetic ordinary-provider seam for orchestration tests; never calls an inventory adapter. */
internal fun exactLookupProviders(lookup: (ProductRef, BranchId) -> ProviderLookupResult) =
    ProductProviderRegistry(listOf(OBI_PROVIDER_ID, KWANT_PROVIDER_ID).map { id ->
        object : ProductProvider {
            override val providerId = id
            override fun branches(): ProviderBranchResult = error("Authority already supplied the fixture directory")
            override fun search(query: String, maxResults: Int): ProviderSearchResult = error("Exact lookup must not search")
            override fun lookup(ref: ProductRef, branchId: BranchId) = lookup.invoke(ref, branchId)
        }
    })

internal fun exactLookupFound(ref: ProductRef, branch: BranchId, stock: Int?, central: Int? = null) =
    ProviderLookupResult.Found(ProviderProduct(ref, branch, "Fresh synthetic product", stock,
        centralStock = central, grossPrice = null, priceScope = null,
        productUrl = "https://offline.invalid/product", ean = null, stockUnit = "szt."))
