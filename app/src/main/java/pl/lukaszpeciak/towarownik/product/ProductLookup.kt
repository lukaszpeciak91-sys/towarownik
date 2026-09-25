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

enum class ProductLookupFailure {
    NETWORK,
    NOT_FOUND,
    DATA,
}

sealed interface ProductLookupResult {
    data class Found(val product: LocalProduct) : ProductLookupResult
    data class InvalidObik(val input: String) : ProductLookupResult
    data class Unavailable(
        val failure: ProductLookupFailure,
        internal val reason: String,
    ) : ProductLookupResult
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
                    onFailure = { exception ->
                        ProductLookupResult.Unavailable(
                            failure = ProductLookupFailure.DATA,
                            reason = exception.message ?: "OBI payload could not be parsed",
                        )
                    },
                )
            is ObiHttpResult.Failure -> ProductLookupResult.Unavailable(
                failure = when (response.kind) {
                    ObiHttpFailureKind.TRANSPORT,
                    ObiHttpFailureKind.SERVER -> ProductLookupFailure.NETWORK
                    ObiHttpFailureKind.NOT_FOUND -> ProductLookupFailure.NOT_FOUND
                    ObiHttpFailureKind.DATA -> ProductLookupFailure.DATA
                },
                reason = response.reason,
            )
        }
    }

    private companion object {
        val OBIK_PATTERN = Regex("[0-9]{7}")
    }
}
