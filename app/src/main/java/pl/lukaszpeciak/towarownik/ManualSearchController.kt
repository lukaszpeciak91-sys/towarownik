package pl.lukaszpeciak.towarownik

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.ManualProductSearchResult
import pl.lukaszpeciak.towarownik.product.ProductLookupFailure
import pl.lukaszpeciak.towarownik.product.ProductLookupRepository
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchCandidate
import pl.lukaszpeciak.towarownik.product.ProductSearchInput
import pl.lukaszpeciak.towarownik.product.ProductSearchRepository
import pl.lukaszpeciak.towarownik.product.classifyProductSearchInput
import pl.lukaszpeciak.towarownik.product.normalizeProductSearchInput

internal const val MANUAL_RESULTS_PAGE_SIZE = 5

internal sealed interface ManualResultEnrichment {
    data object Pending : ManualResultEnrichment
    data object Loading : ManualResultEnrichment

    data class Verified(
        val product: VerifiedProductUiModel,
    ) : ManualResultEnrichment

    data object Unavailable : ManualResultEnrichment
}

internal data class ManualSearchResultItem(
    val obik: String,
    val name: String?,
    val storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    val enrichment: ManualResultEnrichment =
        ManualResultEnrichment.Pending,
)

internal sealed interface ManualSearchUiState {
    data object Idle : ManualSearchUiState
    data object Loading : ManualSearchUiState

    data class SearchResults(
        val items: List<ManualSearchResultItem>,
        val reportedTotalCount: Int,
        val visibleCount: Int = minOf(MANUAL_RESULTS_PAGE_SIZE, items.size),
    ) : ManualSearchUiState {
        val visibleItems: List<ManualSearchResultItem>
            get() = items.take(visibleCount)

        val canShowMore: Boolean
            get() = visibleCount < items.size

        fun showMore(): SearchResults =
            copy(
                visibleCount = minOf(
                    visibleCount + MANUAL_RESULTS_PAGE_SIZE,
                    items.size,
                ),
            )
    }

    data class Product(
        val item: VerifiedProductUiModel,
    ) : ManualSearchUiState

    data class Error(val error: SearchUiError) : ManualSearchUiState
}

internal class ManualSearchController(
    private val lookupObik: (String, String) -> ProductLookupResult =
        { obik, storeNumber ->
            ProductLookupRepository().lookupObik(obik, storeNumber)
        },
    private val searchProducts: (String) -> ManualProductSearchResult =
        ProductSearchRepository()::searchManual,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun submit(
        input: String,
        storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
        onState: (ManualSearchUiState) -> Unit,
    ) {
        val normalizedInput = normalizeProductSearchInput(input)
        val classified = classifyProductSearchInput(normalizedInput)
        if (classified is ProductSearchInput.Invalid) {
            onState(ManualSearchUiState.Error(SearchUiError.INVALID_INPUT))
            return
        }

        onState(ManualSearchUiState.Loading)

        val result = try {
            withContext(ioDispatcher) {
                when (classified) {
                    is ProductSearchInput.Obik -> lookupObik(classified.value, storeNumber).toManualUiState()
                    is ProductSearchInput.Ean ->
                        resolveEan(classified.value, storeNumber)
                    is ProductSearchInput.Text ->
                        resolveText(classified.value, storeNumber)
                    ProductSearchInput.Invalid -> ManualSearchUiState.Error(SearchUiError.INVALID_INPUT)
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
                    storeNumber = storeNumber,
                    onState = onState,
                )
            }

            else -> onState(result)
        }
    }

    suspend fun showMore(
        current: ManualSearchUiState.SearchResults,
        storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
        onState: (ManualSearchUiState) -> Unit,
    ) {
        val expanded = current.showMore()
        enrichRange(
            initial = expanded,
            fromIndex = current.visibleCount,
            toIndexExclusive = expanded.visibleCount,
            storeNumber = storeNumber,
            onState = onState,
        )
    }

    suspend fun select(
        item: ManualSearchResultItem,
        storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
        onState: (ManualSearchUiState) -> Unit,
    ) {
        onState(ManualSearchUiState.Loading)

        val result = try {
            withContext(ioDispatcher) {
                lookupObik(item.obik, storeNumber).toManualUiState()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ManualSearchUiState.Error(SearchUiError.LOOKUP)
        }

        onState(result)
    }

    private fun resolveEan(
        ean: String,
        storeNumber: String,
    ): ManualSearchUiState {
        return when (val search = searchProducts(ean)) {
            is ManualProductSearchResult.Candidates -> {
                if (search.items.size != 1) {
                    search.toManualSearchResults(
                        storeNumber = storeNumber,
                    )
                } else {
                    val candidate = search.items.single()
                    when (val lookup = lookupObik(candidate.obik, storeNumber)) {
                        is ProductLookupResult.Found -> {
                            if (lookup.product.ean == ean) {
                                lookup.toManualUiState()
                            } else {
                                search.toManualSearchResults(
                                    storeNumber = storeNumber,
                                    existingVerified =
                                        lookup.product,
                                )
                            }
                        }

                        is ProductLookupResult.InvalidObik,
                        is ProductLookupResult.InvalidStore ->
                            ManualSearchUiState.Error(SearchUiError.INVALID_INPUT)

                        is ProductLookupResult.Unavailable ->
                            lookup.toManualUiState()
                    }
                }
            }

            ManualProductSearchResult.NotFound ->
                ManualSearchUiState.Error(SearchUiError.NOT_FOUND)

            is ManualProductSearchResult.Unavailable ->
                search.toManualUiState()
        }
    }

    private suspend fun enrichRange(
        initial: ManualSearchUiState.SearchResults,
        fromIndex: Int,
        toIndexExclusive: Int,
        storeNumber: String,
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
            if (item.enrichment !is ManualResultEnrichment.Loading) {
                continue
            }
            val enrichment = try {
                withContext(ioDispatcher) {
                    when (
                        val lookup = lookupObik(
                            item.obik,
                            storeNumber,
                        )
                    ) {
                        is ProductLookupResult.Found ->
                            ManualResultEnrichment.Verified(
                                lookup.product
                                    .toVerifiedProductUiModel(),
                            )

                        is ProductLookupResult.InvalidObik,
                        is ProductLookupResult.InvalidStore,
                        is ProductLookupResult.Unavailable ->
                            ManualResultEnrichment.Unavailable
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                ManualResultEnrichment.Unavailable
            }

            current = current.copy(
                items = current.items.mapIndexed {
                        candidateIndex,
                        candidate,
                    ->
                    if (candidateIndex == index) {
                        candidate.copy(
                            enrichment = enrichment,
                        )
                    } else {
                        candidate
                    }
                },
            )
            onState(current)
        }
    }

    private fun resolveText(
        query: String,
        storeNumber: String,
    ): ManualSearchUiState {
        return when (val search = searchProducts(query)) {
            is ManualProductSearchResult.Candidates ->
                search.toManualSearchResults(
                    storeNumber = storeNumber,
                )
            ManualProductSearchResult.NotFound ->
                ManualSearchUiState.Error(SearchUiError.NOT_FOUND)
            is ManualProductSearchResult.Unavailable ->
                search.toManualUiState()
        }
    }
}

private fun ManualProductSearchResult.Candidates.toManualSearchResults(
    storeNumber: String,
    existingVerified: LocalProduct? = null,
): ManualSearchUiState.SearchResults =
    ManualSearchUiState.SearchResults(
        items = items.map { candidate ->
            candidate.toManualResultItem(
                storeNumber = storeNumber,
                existingVerified = existingVerified,
            )
        },
        reportedTotalCount = reportedTotalCount,
    )

private fun ProductSearchCandidate.toManualResultItem(
    storeNumber: String,
    existingVerified: LocalProduct?,
): ManualSearchResultItem =
    ManualSearchResultItem(
        obik = obik,
        name = name,
        storeNumber = storeNumber,
        enrichment = if (
            existingVerified?.obik == obik &&
            existingVerified.storeNumber == storeNumber
        ) {
            ManualResultEnrichment.Verified(
                existingVerified.toVerifiedProductUiModel(),
            )
        } else {
            ManualResultEnrichment.Pending
        },
    )

private fun ProductLookupResult.toManualUiState(): ManualSearchUiState = when (this) {
    is ProductLookupResult.Found -> ManualSearchUiState.Product(
        item = product.toVerifiedProductUiModel(),
    )

    is ProductLookupResult.InvalidObik ->
        ManualSearchUiState.Error(SearchUiError.INVALID_INPUT)

    is ProductLookupResult.InvalidStore ->
        ManualSearchUiState.Error(SearchUiError.LOOKUP)

    is ProductLookupResult.Unavailable ->
        ManualSearchUiState.Error(
            when (failure) {
                ProductLookupFailure.NETWORK -> SearchUiError.NETWORK
                ProductLookupFailure.NOT_FOUND -> SearchUiError.NOT_FOUND
                ProductLookupFailure.DATA -> SearchUiError.PRODUCT_DATA
            },
        )
}

private fun ManualProductSearchResult.Unavailable.toManualUiState():
    ManualSearchUiState.Error =
    ManualSearchUiState.Error(
        when (failure) {
            ProductLookupFailure.NETWORK -> SearchUiError.NETWORK
            ProductLookupFailure.NOT_FOUND -> SearchUiError.NOT_FOUND
            ProductLookupFailure.DATA -> SearchUiError.SEARCH_DATA
        },
    )

internal fun LocalProduct.toVerifiedProductUiModel(): VerifiedProductUiModel =
    VerifiedProductUiModel(
        name = name,
        obik = obik,
        grossPrice = grossPrice,
        stock = stock,
        productUrl = productUrl,
        storeNumber = storeNumber,
    )
