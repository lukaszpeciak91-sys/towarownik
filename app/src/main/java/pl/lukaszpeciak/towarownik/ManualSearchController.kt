package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

internal data class ManualSearchResultItem(
    val obik: String,
    val name: String?,
)

internal data class VerifiedProductUiModel(
    val name: String,
    val obik: String,
    val grossPrice: BigDecimal?,
    val stock: Int?,
    val productUrl: String,
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

    data class Error(val message: String) : ManualSearchUiState
}

internal class ManualSearchController(
    private val lookupObik: (String) -> ProductLookupResult =
        ProductLookupRepository()::lookupObik,
    private val searchProducts: (String) -> ManualProductSearchResult =
        ProductSearchRepository()::searchManual,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun submit(
        input: String,
        onState: (ManualSearchUiState) -> Unit,
    ) {
        val normalizedInput = normalizeProductSearchInput(input)
        val classified = classifyProductSearchInput(normalizedInput)
        if (classified is ProductSearchInput.Invalid) {
            onState(ManualSearchUiState.Error(INVALID_SEARCH_MESSAGE))
            return
        }

        onState(ManualSearchUiState.Loading)

        val result = try {
            withContext(ioDispatcher) {
                when (classified) {
                    is ProductSearchInput.Obik -> lookupObik(classified.value).toManualUiState()
                    is ProductSearchInput.Ean -> resolveEan(classified.value)
                    is ProductSearchInput.Text -> resolveText(classified.value)
                    ProductSearchInput.Invalid -> ManualSearchUiState.Error(INVALID_SEARCH_MESSAGE)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ManualSearchUiState.Error(LOOKUP_ERROR_MESSAGE)
        }

        onState(result)
    }

    suspend fun select(
        item: ManualSearchResultItem,
        onState: (ManualSearchUiState) -> Unit,
    ) {
        onState(ManualSearchUiState.Loading)

        val result = try {
            withContext(ioDispatcher) {
                lookupObik(item.obik).toManualUiState()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ManualSearchUiState.Error(LOOKUP_ERROR_MESSAGE)
        }

        onState(result)
    }

    private fun resolveEan(ean: String): ManualSearchUiState {
        return when (val search = searchProducts(ean)) {
            is ManualProductSearchResult.Candidates -> {
                if (search.items.size != 1) {
                    search.toManualSearchResults()
                } else {
                    val candidate = search.items.single()
                    when (val lookup = lookupObik(candidate.obik)) {
                        is ProductLookupResult.Found -> {
                            if (lookup.product.ean == ean) {
                                lookup.toManualUiState()
                            } else {
                                search.toManualSearchResults()
                            }
                        }

                        is ProductLookupResult.InvalidObik ->
                            ManualSearchUiState.Error(INVALID_SEARCH_MESSAGE)

                        is ProductLookupResult.Unavailable ->
                            lookup.toManualUiState()
                    }
                }
            }

            ManualProductSearchResult.NotFound ->
                ManualSearchUiState.Error(NOT_FOUND_MESSAGE)

            is ManualProductSearchResult.Unavailable ->
                search.toManualUiState()
        }
    }

    private fun resolveText(query: String): ManualSearchUiState {
        return when (val search = searchProducts(query)) {
            is ManualProductSearchResult.Candidates -> search.toManualSearchResults()
            ManualProductSearchResult.NotFound ->
                ManualSearchUiState.Error(NOT_FOUND_MESSAGE)
            is ManualProductSearchResult.Unavailable ->
                search.toManualUiState()
        }
    }
}

internal fun formatStore075Stock(stock: Int?): String = when (stock) {
    null -> "Stan Nowy Sącz: brak danych"
    0 -> "Stan Nowy Sącz: 0 szt. — brak na stanie"
    else -> "Stan Nowy Sącz: $stock szt."
}

internal fun formatStore075Price(price: BigDecimal?): String =
    price?.let { "Cena Nowy Sącz: ${it.toPlainString()} zł" }
        ?: "Cena Nowy Sącz: brak danych"

private fun ManualProductSearchResult.Candidates.toManualSearchResults():
    ManualSearchUiState.SearchResults =
    ManualSearchUiState.SearchResults(
        items = items.map(ProductSearchCandidate::toManualResultItem),
        reportedTotalCount = reportedTotalCount,
    )

private fun ProductSearchCandidate.toManualResultItem(): ManualSearchResultItem =
    ManualSearchResultItem(
        obik = obik,
        name = name,
    )

private fun ProductLookupResult.toManualUiState(): ManualSearchUiState = when (this) {
    is ProductLookupResult.Found -> ManualSearchUiState.Product(
        item = product.toVerifiedProductUiModel(),
    )

    is ProductLookupResult.InvalidObik ->
        ManualSearchUiState.Error(INVALID_SEARCH_MESSAGE)

    is ProductLookupResult.Unavailable ->
        ManualSearchUiState.Error(
            when (failure) {
                ProductLookupFailure.NETWORK -> NETWORK_ERROR_MESSAGE
                ProductLookupFailure.NOT_FOUND -> NOT_FOUND_MESSAGE
                ProductLookupFailure.DATA -> DATA_ERROR_MESSAGE
            },
        )
}

private fun ManualProductSearchResult.Unavailable.toManualUiState():
    ManualSearchUiState.Error =
    ManualSearchUiState.Error(
        when (failure) {
            ProductLookupFailure.NETWORK -> NETWORK_ERROR_MESSAGE
            ProductLookupFailure.NOT_FOUND -> NOT_FOUND_MESSAGE
            ProductLookupFailure.DATA -> SEARCH_DATA_ERROR_MESSAGE
        },
    )

internal fun LocalProduct.toVerifiedProductUiModel(): VerifiedProductUiModel =
    VerifiedProductUiModel(
        name = name,
        obik = obik,
        grossPrice = grossPrice,
        stock = stock,
        productUrl = productUrl,
    )
