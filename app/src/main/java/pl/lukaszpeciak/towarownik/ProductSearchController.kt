package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.product.ProductLookupFailure
import pl.lukaszpeciak.towarownik.product.ProductLookupRepository
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchCandidate
import pl.lukaszpeciak.towarownik.product.ProductSearchInput
import pl.lukaszpeciak.towarownik.product.ProductSearchRepository
import pl.lukaszpeciak.towarownik.product.ProductSearchResult
import pl.lukaszpeciak.towarownik.product.classifyProductSearchInput

internal const val INVALID_SEARCH_MESSAGE = "Wpisz 7-cyfrowy OBIK, EAN lub nazwę produktu."
internal const val NETWORK_ERROR_MESSAGE = "Nie udało się połączyć z OBI. Sprawdź internet i spróbuj ponownie."
internal const val NOT_FOUND_MESSAGE = "Nie znaleziono produktu."
internal const val DATA_ERROR_MESSAGE = "Nie udało się odczytać danych produktu z OBI."
internal const val SEARCH_DATA_ERROR_MESSAGE = "Nie udało się odczytać wyników wyszukiwania z OBI."
internal const val LOOKUP_ERROR_MESSAGE = "Nie udało się wyszukać produktu."

internal data class SearchResultItem(
    val obik: String,
    val name: String?,
)

internal sealed interface ProductSearchUiState {
    data object Idle : ProductSearchUiState
    data object Loading : ProductSearchUiState

    data class SearchResults(
        val items: List<SearchResultItem>,
    ) : ProductSearchUiState

    data class Success(
        val name: String,
        val stock: Int?,
        val grossPrice: BigDecimal?,
    ) : ProductSearchUiState

    data class Error(val message: String) : ProductSearchUiState
}

internal class ProductSearchController(
    private val lookupObik: (String) -> ProductLookupResult = ProductLookupRepository()::lookupObik,
    private val searchProducts: (String) -> ProductSearchResult = ProductSearchRepository()::search,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun submit(
        input: String,
        onState: (ProductSearchUiState) -> Unit,
    ) {
        val classified = classifyProductSearchInput(input)
        if (classified is ProductSearchInput.Invalid) {
            onState(ProductSearchUiState.Error(INVALID_SEARCH_MESSAGE))
            return
        }

        onState(ProductSearchUiState.Loading)

        val result = try {
            withContext(ioDispatcher) {
                when (classified) {
                    is ProductSearchInput.Obik -> lookupObik(classified.value).toUiState()
                    is ProductSearchInput.Ean -> resolveEan(classified.value)
                    is ProductSearchInput.Text -> resolveSearch(classified.value)
                    ProductSearchInput.Invalid -> ProductSearchUiState.Error(INVALID_SEARCH_MESSAGE)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ProductSearchUiState.Error(LOOKUP_ERROR_MESSAGE)
        }

        onState(result)
    }

    suspend fun select(
        item: SearchResultItem,
        onState: (ProductSearchUiState) -> Unit,
    ) {
        onState(ProductSearchUiState.Loading)

        val result = try {
            withContext(ioDispatcher) {
                lookupObik(item.obik).toUiState()
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ProductSearchUiState.Error(LOOKUP_ERROR_MESSAGE)
        }

        onState(result)
    }

    private fun resolveEan(ean: String): ProductSearchUiState {
        return when (val search = searchProducts(ean)) {
            is ProductSearchResult.Candidates -> {
                if (search.items.size != 1) {
                    search.items.toSearchResults()
                } else {
                    val candidate = search.items.single()
                    when (val lookup = lookupObik(candidate.obik)) {
                        is ProductLookupResult.Found -> {
                            if (lookup.product.ean == ean) {
                                lookup.toUiState()
                            } else {
                                search.items.toSearchResults()
                            }
                        }
                        is ProductLookupResult.InvalidObik -> ProductSearchUiState.Error(INVALID_SEARCH_MESSAGE)
                        is ProductLookupResult.Unavailable -> lookup.toUiState()
                    }
                }
            }
            ProductSearchResult.NotFound -> ProductSearchUiState.Error(NOT_FOUND_MESSAGE)
            is ProductSearchResult.Unavailable -> search.toUiState()
        }
    }

    private fun resolveSearch(query: String): ProductSearchUiState {
        return when (val search = searchProducts(query)) {
            is ProductSearchResult.Candidates -> search.items.toSearchResults()
            ProductSearchResult.NotFound -> ProductSearchUiState.Error(NOT_FOUND_MESSAGE)
            is ProductSearchResult.Unavailable -> search.toUiState()
        }
    }
}

private fun List<ProductSearchCandidate>.toSearchResults(): ProductSearchUiState =
    ProductSearchUiState.SearchResults(
        map { candidate ->
            SearchResultItem(
                obik = candidate.obik,
                name = candidate.name,
            )
        },
    )

private fun ProductLookupResult.toUiState(): ProductSearchUiState = when (this) {
    is ProductLookupResult.Found -> ProductSearchUiState.Success(
        name = product.name,
        stock = product.stock,
        grossPrice = product.grossPrice,
    )
    is ProductLookupResult.InvalidObik -> ProductSearchUiState.Error(INVALID_SEARCH_MESSAGE)
    is ProductLookupResult.Unavailable -> ProductSearchUiState.Error(
        when (failure) {
            ProductLookupFailure.NETWORK -> NETWORK_ERROR_MESSAGE
            ProductLookupFailure.NOT_FOUND -> NOT_FOUND_MESSAGE
            ProductLookupFailure.DATA -> DATA_ERROR_MESSAGE
        },
    )
}

private fun ProductSearchResult.Unavailable.toUiState(): ProductSearchUiState =
    ProductSearchUiState.Error(
        when (failure) {
            ProductLookupFailure.NETWORK -> NETWORK_ERROR_MESSAGE
            ProductLookupFailure.NOT_FOUND -> NOT_FOUND_MESSAGE
            ProductLookupFailure.DATA -> SEARCH_DATA_ERROR_MESSAGE
        },
    )
