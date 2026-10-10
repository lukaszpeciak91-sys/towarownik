package pl.lukaszpeciak.towarownik

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.ManualProductSearchResult
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchInput
import pl.lukaszpeciak.towarownik.product.classifyProductSearchInput
import pl.lukaszpeciak.towarownik.product.normalizeProductSearchInput
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.DEFAULT_WORKING_PROFILE
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ObiProductProvider
import pl.lukaszpeciak.towarownik.product.provider.ProductProviderFailure
import pl.lukaszpeciak.towarownik.product.provider.ProductProviderRegistry
import pl.lukaszpeciak.towarownik.product.provider.ProductRef
import pl.lukaszpeciak.towarownik.product.provider.ProviderLookupResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct
import pl.lukaszpeciak.towarownik.product.provider.ProviderSearchResult
import pl.lukaszpeciak.towarownik.product.provider.WorkingProfile

internal const val MANUAL_RESULTS_PAGE_SIZE = 5
internal const val MANUAL_RESULTS_MAX = 25

internal sealed interface ManualResultEnrichment {
    data object Pending : ManualResultEnrichment
    data object Loading : ManualResultEnrichment

    data class Verified(
        val product: VerifiedProductUiModel,
    ) : ManualResultEnrichment

    data object Unavailable : ManualResultEnrichment
}

internal data class ManualSearchResultItem(
    val ref: ProductRef,
    val name: String?,
    val branchId: BranchId,
    val branchLabel: String? = null,
    val articleNumber: String? = null,
    val enrichment: ManualResultEnrichment =
        ManualResultEnrichment.Pending,
) {
    constructor(
        obik: String,
        name: String?,
        storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
        enrichment: ManualResultEnrichment =
            ManualResultEnrichment.Pending,
    ) : this(
        ref = ProductRef(OBI_PROVIDER_ID, obik),
        name = name,
        branchId = BranchId(storeNumber),
        enrichment = enrichment,
    )
    val obik: String
        get() = ref.productId

    val storeNumber: String
        get() = branchId.value
}

internal sealed interface ManualSearchUiState {
    data object Idle : ManualSearchUiState
    data object Loading : ManualSearchUiState

    data class SearchResults(
        val items: List<ManualSearchResultItem>,
        val reportedTotalCount: Int?,
        val visibleCount: Int = minOf(MANUAL_RESULTS_PAGE_SIZE, items.size),
    ) : ManualSearchUiState {
        val visibleItems: List<ManualSearchResultItem>
            get() = items.take(visibleCount)

        val hasVisibleEnrichmentInFlight: Boolean
            get() = visibleItems.any {
                it.enrichment is ManualResultEnrichment.Pending ||
                    it.enrichment is ManualResultEnrichment.Loading
            }

        val canShowMore: Boolean
            get() =
                visibleCount < items.size &&
                    !hasVisibleEnrichmentInFlight

        fun showMore(): SearchResults =
            if (!canShowMore) {
                this
            } else {
                copy(
                    visibleCount = minOf(
                        visibleCount + MANUAL_RESULTS_PAGE_SIZE,
                        items.size,
                    ),
                )
            }
    }

    data class Product(
        val item: VerifiedProductUiModel,
    ) : ManualSearchUiState

    data class Error(val error: SearchUiError) : ManualSearchUiState
}

internal class ManualSearchController(
    private val providers: ProductProviderRegistry =
        ProductProviderRegistry.production(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(
        lookupObik: (String, String) -> ProductLookupResult,
        searchProducts: (String) -> ManualProductSearchResult,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        providers = ProductProviderRegistry(
            listOf(
                ObiProductProvider(
                    boundedSearch = {
                        error("Bounded search is not used by manual search")
                    },
                    manualSearch = searchProducts,
                    lookupObik = lookupObik,
                ),
            ),
        ),
        ioDispatcher = ioDispatcher,
    )

    suspend fun submit(
        input: String,
        workingProfile: WorkingProfile = DEFAULT_WORKING_PROFILE,
        branchLabel: String? = null,
        onState: (ManualSearchUiState) -> Unit,
    ) {
        val normalized = normalizeProductSearchInput(input)
        if (normalized.isBlank()) {
            onState(ManualSearchUiState.Error(SearchUiError.INVALID_INPUT))
            return
        }

        onState(ManualSearchUiState.Loading)

        val result = try {
            withContext(ioDispatcher) {
                val provider = runCatching {
                    providers.resolve(workingProfile.providerId)
                }.getOrNull()
                    ?: return@withContext ManualSearchUiState.Error(
                        SearchUiError.INVALID_INPUT,
                    )

                val classified = classifyProductSearchInput(normalized)
                when {
                    workingProfile.providerId == OBI_PROVIDER_ID &&
                        classified is ProductSearchInput.Obik ->
                        provider.lookup(
                            ProductRef(
                                providerId = OBI_PROVIDER_ID,
                                productId = classified.value,
                            ),
                            workingProfile.branchId,
                        ).toManualUiState(branchLabel)

                    workingProfile.providerId == OBI_PROVIDER_ID &&
                        classified is ProductSearchInput.Ean -> {
                        val search = provider.search(
                            query = normalized,
                            maxResults = MANUAL_RESULTS_MAX,
                        )
                        if (
                            search is ProviderSearchResult.Candidates &&
                            search.items.size == 1
                        ) {
                            val candidate = search.items.single()
                            when (
                                val lookup = provider.lookup(
                                    candidate.ref,
                                    workingProfile.branchId,
                                )
                            ) {
                                is ProviderLookupResult.Found ->
                                    if (lookup.product.ean == classified.value) {
                                        ManualSearchUiState.Product(
                                            lookup.product.toManualProductUiModel(
                                                branchLabel,
                                            ),
                                        )
                                    } else {
                                        search.toManualSearchResults(
                                            workingProfile,
                                            branchLabel,
                                        )
                                    }
                                else ->
                                    search.toManualSearchResults(
                                        workingProfile,
                                        branchLabel,
                                    )
                            }
                        } else {
                            search.toManualSearchResults(
                                workingProfile,
                                branchLabel,
                            )
                        }
                    }

                    else ->
                        provider.search(
                            query = normalized,
                            maxResults = MANUAL_RESULTS_MAX,
                        ).toManualSearchResults(
                            workingProfile = workingProfile,
                            branchLabel = branchLabel,
                        )
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ManualSearchUiState.Error(SearchUiError.LOOKUP)
        }

        when (result) {
            is ManualSearchUiState.SearchResults -> {
                enrichRange(
                    initial = result,
                    fromIndex = 0,
                    toIndexExclusive = result.visibleCount,
                    workingProfile = workingProfile,
                    onState = onState,
                )
            }

            else -> onState(result)
        }
    }

    suspend fun submit(
        input: String,
        storeNumber: String,
        onState: (ManualSearchUiState) -> Unit,
    ) = submit(
        input = input,
        workingProfile = WorkingProfile(
            providerId = OBI_PROVIDER_ID,
            branchId = BranchId(storeNumber),
        ),
        onState = onState,
    )

    suspend fun showMore(
        current: ManualSearchUiState.SearchResults,
        workingProfile: WorkingProfile = DEFAULT_WORKING_PROFILE,
        onState: (ManualSearchUiState) -> Unit,
    ) {
        if (!current.canShowMore) {
            onState(current)
            return
        }
        val expanded = current.showMore()
        enrichRange(
            initial = expanded,
            fromIndex = current.visibleCount,
            toIndexExclusive = expanded.visibleCount,
            workingProfile = workingProfile,
            onState = onState,
        )
    }

    suspend fun showMore(
        current: ManualSearchUiState.SearchResults,
        storeNumber: String,
        onState: (ManualSearchUiState) -> Unit,
    ) = showMore(
        current = current,
        workingProfile = WorkingProfile(
            providerId = OBI_PROVIDER_ID,
            branchId = BranchId(storeNumber),
        ),
        onState = onState,
    )

    suspend fun select(
        item: ManualSearchResultItem,
        workingProfile: WorkingProfile = DEFAULT_WORKING_PROFILE,
        onState: (ManualSearchUiState) -> Unit,
    ) {
        if (
            item.ref.providerId != workingProfile.providerId ||
            item.branchId != workingProfile.branchId
        ) {
            onState(ManualSearchUiState.Error(SearchUiError.INVALID_INPUT))
            return
        }

        onState(ManualSearchUiState.Loading)
        val result = try {
            withContext(ioDispatcher) {
                providers.resolve(workingProfile.providerId)
                    .lookup(item.ref, workingProfile.branchId)
                    .toManualUiState(item.branchLabel)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ManualSearchUiState.Error(SearchUiError.LOOKUP)
        }
        onState(result)
    }

    suspend fun select(
        item: ManualSearchResultItem,
        storeNumber: String,
        onState: (ManualSearchUiState) -> Unit,
    ) = select(
        item = item,
        workingProfile = WorkingProfile(
            providerId = OBI_PROVIDER_ID,
            branchId = BranchId(storeNumber),
        ),
        onState = onState,
    )

    private suspend fun enrichRange(
        initial: ManualSearchUiState.SearchResults,
        fromIndex: Int,
        toIndexExclusive: Int,
        workingProfile: WorkingProfile,
        onState: (ManualSearchUiState) -> Unit,
    ) {
        var current = initial.copy(
            items = initial.items.mapIndexed { index, item ->
                if (
                    index in fromIndex until toIndexExclusive &&
                    item.enrichment is ManualResultEnrichment.Pending
                ) {
                    item.copy(
                        enrichment = ManualResultEnrichment.Loading,
                    )
                } else {
                    item
                }
            },
        )
        onState(current)

        for (index in fromIndex until toIndexExclusive) {
            val item = current.items[index]
            if (item.enrichment !is ManualResultEnrichment.Loading) continue

            val enrichment = try {
                withContext(ioDispatcher) {
                    when (
                        val lookup = providers.resolve(
                            workingProfile.providerId,
                        ).lookup(
                            item.ref,
                            workingProfile.branchId,
                        )
                    ) {
                        is ProviderLookupResult.Found ->
                            ManualResultEnrichment.Verified(
                                lookup.product.toManualProductUiModel(
                                    branchLabel = item.branchLabel,
                                ),
                            )

                        is ProviderLookupResult.WrongProvider,
                        is ProviderLookupResult.InvalidProductId,
                        is ProviderLookupResult.InvalidBranch,
                        is ProviderLookupResult.Unavailable ->
                            ManualResultEnrichment.Unavailable
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                ManualResultEnrichment.Unavailable
            }

            current = current.copy(
                items = current.items.mapIndexed { itemIndex, value ->
                    if (itemIndex == index) {
                        value.copy(enrichment = enrichment)
                    } else {
                        value
                    }
                },
            )
            onState(current)
        }
        current = current.sortVisibleByAvailability()
        onState(current)
    }
}

private fun ManualSearchUiState.SearchResults.sortVisibleByAvailability():
    ManualSearchUiState.SearchResults {
    val visible = items.take(visibleCount).withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<ManualSearchResultItem>> { indexed ->
                val product = (
                    indexed.value.enrichment as?
                        ManualResultEnrichment.Verified
                    )?.product
                when {
                    product?.stock != null && product.stock > 0 -> 3
                    product?.stock == 0 -> 2
                    product != null -> 1
                    else -> 0
                }
            }.thenByDescending { indexed ->
                (
                    indexed.value.enrichment as?
                        ManualResultEnrichment.Verified
                    )?.product?.stock ?: Int.MIN_VALUE
            }.thenBy { it.index },
        )
        .map { it.value }
    return copy(items = visible + items.drop(visibleCount))
}

private fun ProviderSearchResult.toManualSearchResults(
    workingProfile: WorkingProfile,
    branchLabel: String?,
): ManualSearchUiState =
    when (this) {
        is ProviderSearchResult.Candidates -> {
            if (items.isEmpty()) {
                ManualSearchUiState.Error(SearchUiError.NOT_FOUND)
            } else {
                ManualSearchUiState.SearchResults(
                    items = items.map { candidate ->
                        ManualSearchResultItem(
                            ref = candidate.ref,
                            name = candidate.name,
                            branchId = workingProfile.branchId,
                            branchLabel = branchLabel,
                            articleNumber = candidate.articleNumber,
                        )
                    },
                    reportedTotalCount = reportedTotalCount,
                )
            }
        }

        ProviderSearchResult.NotFound ->
            ManualSearchUiState.Error(SearchUiError.NOT_FOUND)

        is ProviderSearchResult.Unavailable ->
            ManualSearchUiState.Error(failure.toSearchUiError())
    }

private fun ProviderLookupResult.toManualUiState(
    branchLabel: String?,
): ManualSearchUiState =
    when (this) {
        is ProviderLookupResult.Found ->
            ManualSearchUiState.Product(
                product.toManualProductUiModel(branchLabel),
            )

        is ProviderLookupResult.WrongProvider,
        is ProviderLookupResult.InvalidProductId,
        is ProviderLookupResult.InvalidBranch ->
            ManualSearchUiState.Error(SearchUiError.INVALID_INPUT)

        is ProviderLookupResult.Unavailable ->
            ManualSearchUiState.Error(failure.toSearchUiError())
    }

private fun ProviderProduct.toManualProductUiModel(
    branchLabel: String?,
): VerifiedProductUiModel =
    VerifiedProductUiModel(
        name = name,
        obik = ref.productId,
        grossPrice = grossPrice,
        stock = stock,
        centralStock = centralStock,
        stockUnit = stockUnit,
        productUrl = productUrl,
        storeNumber = branchId.value,
        primaryImageUrl = primaryImageUrl,
        providerId = ref.providerId.value,
        productId = ref.productId,
        branchId = branchId.value,
        articleNumber = articleNumber,
        branchLabel = branchLabel,
    )

private fun ProductProviderFailure.toSearchUiError(): SearchUiError =
    when (this) {
        ProductProviderFailure.NETWORK -> SearchUiError.NETWORK
        ProductProviderFailure.NOT_FOUND -> SearchUiError.NOT_FOUND
        ProductProviderFailure.DATA -> SearchUiError.PRODUCT_DATA
    }

internal fun manualResultDisplayName(
    item: ManualSearchResultItem,
): String? =
    when (val enrichment = item.enrichment) {
        is ManualResultEnrichment.Verified -> enrichment.product.name
        ManualResultEnrichment.Pending,
        ManualResultEnrichment.Loading,
        ManualResultEnrichment.Unavailable -> item.name
    }
