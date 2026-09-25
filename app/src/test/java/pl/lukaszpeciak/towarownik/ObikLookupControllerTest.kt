package pl.lukaszpeciak.towarownik

import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.ProductLookupFailure
import pl.lukaszpeciak.towarownik.product.ProductLookupResult

class ObikLookupControllerTest {
    @Test
    fun `validation accepts exactly seven numeric digits`() {
        assertNull(validateObikInput("7313810"))
    }

    @Test
    fun `validation rejects invalid OBIK inputs`() {
        listOf("", "123456", "12345678", "12345A7", " 7313810").forEach { input ->
            assertEquals(INVALID_OBIK_MESSAGE, validateObikInput(input))
        }
    }

    @Test
    fun `invalid input is rejected without calling repository lookup`() = runBlocking {
        val states = mutableListOf<ObikLookupUiState>()
        val controller = ObikLookupController(
            lookup = { error("Lookup must not run for invalid input") },
            ioDispatcher = Dispatchers.Unconfined,
        )

        controller.submit("123") { states += it }

        assertEquals(
            listOf(ObikLookupUiState.Error(INVALID_OBIK_MESSAGE)),
            states,
        )
    }

    @Test
    fun `successful lookup transitions through loading to success`() = runBlocking {
        val states = mutableListOf<ObikLookupUiState>()
        val product = LocalProduct(
            obik = "7313810",
            name = "Test product",
            stock = 4,
            grossPrice = BigDecimal("19.99"),
            productUrl = "https://www.obi.pl/p/7313810",
            ean = null,
        )
        val controller = ObikLookupController(
            lookup = { ProductLookupResult.Found(product) },
            ioDispatcher = Dispatchers.Unconfined,
        )

        controller.submit("7313810") { states += it }

        assertEquals(
            listOf(
                ObikLookupUiState.Loading,
                ObikLookupUiState.Success(
                    name = "Test product",
                    stock = 4,
                    grossPrice = BigDecimal("19.99"),
                ),
            ),
            states,
        )
    }

    @Test
    fun `unknown stock and price remain unknown in success state`() = runBlocking {
        val states = mutableListOf<ObikLookupUiState>()
        val product = LocalProduct(
            obik = "7313810",
            name = "Test product",
            stock = null,
            grossPrice = null,
            productUrl = "https://www.obi.pl/p/7313810",
            ean = null,
        )
        val controller = ObikLookupController(
            lookup = { ProductLookupResult.Found(product) },
            ioDispatcher = Dispatchers.Unconfined,
        )

        controller.submit("7313810") { states += it }

        assertEquals(
            ObikLookupUiState.Success(
                name = "Test product",
                stock = null,
                grossPrice = null,
            ),
            states.last(),
        )
    }

    @Test
    fun `lookup failures map to short user facing messages`() = runBlocking {
        val cases = listOf(
            ProductLookupFailure.NETWORK to NETWORK_ERROR_MESSAGE,
            ProductLookupFailure.NOT_FOUND to NOT_FOUND_MESSAGE,
            ProductLookupFailure.DATA to DATA_ERROR_MESSAGE,
        )

        cases.forEach { (failure, expectedMessage) ->
            val states = mutableListOf<ObikLookupUiState>()
            val controller = ObikLookupController(
                lookup = {
                    ProductLookupResult.Unavailable(
                        failure = failure,
                        reason = "internal detail that must not be shown",
                    )
                },
                ioDispatcher = Dispatchers.Unconfined,
            )

            controller.submit("7313810") { states += it }

            assertEquals(ObikLookupUiState.Loading, states.first())
            assertEquals(ObikLookupUiState.Error(expectedMessage), states.last())
        }
    }
}
