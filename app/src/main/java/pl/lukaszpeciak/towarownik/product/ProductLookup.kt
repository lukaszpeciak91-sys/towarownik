package pl.lukaszpeciak.towarownik.product

import java.math.BigDecimal

const val NOWY_SACZ_STORE_NUMBER = "075"

data class LocalProduct(
    val obik: String,
    val name: String,
    val stock: Int?,
    val grossPrice: BigDecimal?,
    val productUrl: String,
    internal val ean: String?,
    val storeNumber: String = NOWY_SACZ_STORE_NUMBER,
)

sealed interface ProductLookupResult {
    data class Found(val product: LocalProduct) : ProductLookupResult
    data class InvalidObik(val input: String) : ProductLookupResult
    data class Unavailable(val reason: String) : ProductLookupResult
}

class ProductLookupRepository(
    private val httpClient: ObiHttpClient = ObiHttpClient(),
    private val parser: ObiPayloadParser = ObiPayloadParser(),
) {
    fun lookupObik(obik: String): ProductLookupResult {
        if (!OBIK_PATTERN.matches(obik)) return ProductLookupResult.InvalidObik(obik)

        return when (val response = httpClient.fetchProduct(obik, NOWY_SACZ_STORE_NUMBER)) {
            is ObiHttpResult.Success -> parser.parse(response.html, obik, NOWY_SACZ_STORE_NUMBER)
                .fold(
                    onSuccess = { ProductLookupResult.Found(it) },
                    onFailure = { ProductLookupResult.Unavailable(it.message ?: "OBI payload could not be parsed") },
                )
            is ObiHttpResult.Failure -> ProductLookupResult.Unavailable(response.reason)
        }
    }

    private companion object {
        val OBIK_PATTERN = Regex("[0-9]{7}")
    }
}
