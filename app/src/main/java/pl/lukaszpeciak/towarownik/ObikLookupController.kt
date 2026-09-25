package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.product.ProductLookupFailure
import pl.lukaszpeciak.towarownik.product.ProductLookupRepository
import pl.lukaszpeciak.towarownik.product.ProductLookupResult

internal const val INVALID_OBIK_MESSAGE = "OBIK musi mieć dokładnie 7 cyfr."
internal const val NETWORK_ERROR_MESSAGE = "Nie udało się połączyć z OBI. Sprawdź internet i spróbuj ponownie."
internal const val NOT_FOUND_MESSAGE = "Nie znaleziono produktu dla tego OBIK."
internal const val DATA_ERROR_MESSAGE = "Nie udało się odczytać danych produktu z OBI."
internal const val LOOKUP_ERROR_MESSAGE = "Nie udało się wyszukać produktu."

internal sealed interface ObikLookupUiState {
    data object Idle : ObikLookupUiState
    data object Loading : ObikLookupUiState

    data class Success(
        val name: String,
        val stock: Int?,
        val grossPrice: BigDecimal?,
    ) : ObikLookupUiState

    data class Error(val message: String) : ObikLookupUiState
}

internal fun validateObikInput(input: String): String? =
    if (OBIK_PATTERN.matches(input)) null else INVALID_OBIK_MESSAGE

internal class ObikLookupController(
    private val lookup: (String) -> ProductLookupResult = ProductLookupRepository()::lookupObik,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun submit(
        input: String,
        onState: (ObikLookupUiState) -> Unit,
    ) {
        val validationError = validateObikInput(input)
        if (validationError != null) {
            onState(ObikLookupUiState.Error(validationError))
            return
        }

        onState(ObikLookupUiState.Loading)

        val result = try {
            withContext(ioDispatcher) {
                lookup(input)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            onState(ObikLookupUiState.Error(LOOKUP_ERROR_MESSAGE))
            return
        }

        onState(result.toUiState())
    }
}

private fun ProductLookupResult.toUiState(): ObikLookupUiState = when (this) {
    is ProductLookupResult.Found -> ObikLookupUiState.Success(
        name = product.name,
        stock = product.stock,
        grossPrice = product.grossPrice,
    )

    is ProductLookupResult.InvalidObik -> ObikLookupUiState.Error(INVALID_OBIK_MESSAGE)

    is ProductLookupResult.Unavailable -> ObikLookupUiState.Error(
        when (failure) {
            ProductLookupFailure.NETWORK -> NETWORK_ERROR_MESSAGE
            ProductLookupFailure.NOT_FOUND -> NOT_FOUND_MESSAGE
            ProductLookupFailure.DATA -> DATA_ERROR_MESSAGE
        },
    )
}

private val OBIK_PATTERN = Regex("[0-9]{7}")
