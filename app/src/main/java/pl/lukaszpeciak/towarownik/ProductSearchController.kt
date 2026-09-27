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
import pl.lukaszpeciak.towarownik.product.normalizeProductSearchInput

internal enum class SearchUiError {
    INVALID_INPUT,
    NETWORK,
    NOT_FOUND,
    PRODUCT_DATA,
    SEARCH_DATA,
    LOOKUP,
}

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

    data class Error(val error: SearchUiError) : ProductSearchUiState
}

internal class ProductSearchController(
    private val lookupObik: (String) -> ProductLookupResult = { obik -> ProductLookupRepository().lookupObik(obik) },
    private val searchProducts: (String) -> ProductSearchResult = ProductSearchRepository()::search,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun submit(
        input: String,
        onState: (ProductSearchUiState) -> Unit,
    ) {
        val normalizedInput = normalizeProductSearchInput(input)
        val classified = classifyProductSearchInput(normalizedInput)
        if (classified is ProductSearchInput.Invalid) {
            onState(ProductSearchUiState.Error(SearchUiError.INVALID_INPUT))
            return
        }

        onState(ProductSearchUiState.Loading)

        val result = try {
            withContext(ioDispatcher) {
                when (classified) {
                    is ProductSearchInput.Obik -> lookupObik(classified.value).toUiState()
                    is ProductSearchInput.Ean -> resolveEan(classified.value)
                    is ProductSearchInput.Text -> resolveSearch(classified.value)
                    ProductSearchInput.Invalid -> ProductSearchUiState.Error(SearchUiError.INVALID_INPUT)
                }
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ProductSearchUiState.Error(SearchUiError.LOOKUP)
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
            ProductSearchUiState.Error(SearchUiError.LOOKUP)
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
                        is ProductLookupResult.InvalidObik -> ProductSearchUiState.Error(SearchUiError.INVALID_INPUT)
                        is ProductLookupResult.InvalidStore -> ProductSearchUiState.Error(SearchUiError.LOOKUP)
    is ProductLookupResult.InvalidStore -> ProductSearchUiState.Error(SearchUiError.LOOKUP)
                        is ProductLookupResult.Unavailable -> lookup.toUiState()
                    }
                }
            }
            ProductSearchResult.NotFound -> ProductSearchUiState.Error(SearchUiError.NOT_FOUND)
            is ProductSearchResult.Unavailable -> search.toUiState()
        }
    }

    private fun resolveSearch(query: String): ProductSearchUiState {
        return when (val search = searchProducts(query)) {
            is ProductSearchResult.Candidates -> search.items.toSearchResults()
            ProductSearchResult.NotFound -> ProductSearchUiState.Error(SearchUiError.NOT_FOUND)
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
    is ProductLookupResult.InvalidObik ->
        ProductSearchUiState.Error(SearchUiError.INVALID_INPUT)
    is ProductLookupResult.InvalidStore ->
        ProductSearchUiState.Error(SearchUiError.LOOKUP)
    is ProductLookupResult.Unavailable -> ProductSearchUiState.Error(
        when (failure) {
            ProductLookupFailure.NETWORK -> SearchUiError.NETWORK
            ProductLookupFailure.NOT_FOUND -> SearchUiError.NOT_FOUND
            ProductLookupFailure.DATA -> SearchUiError.PRODUCT_DATA
        },
    )
}

private fun ProductSearchResult.Unavailable.toUiState(): ProductSearchUiState =
    ProductSearchUiState.Error(
        when (failure) {
            ProductLookupFailure.NETWORK -> SearchUiError.NETWORK
            ProductLookupFailure.NOT_FOUND -> SearchUiError.NOT_FOUND
            ProductLookupFailure.DATA -> SearchUiError.SEARCH_DATA
        },
    )
