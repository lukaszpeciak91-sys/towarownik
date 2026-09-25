package pl.lukaszpeciak.towarownik.product

sealed interface ProductSearchInput {
    data class Obik(val value: String) : ProductSearchInput
    data class Ean(val value: String) : ProductSearchInput
    data class Text(val value: String) : ProductSearchInput
    data object Invalid : ProductSearchInput
}

data class ProductSearchCandidate(
    val obik: String,
    val name: String?,
)

sealed interface ProductSearchResult {
    data class Candidates(val items: List<ProductSearchCandidate>) : ProductSearchResult
    data object NotFound : ProductSearchResult
    data class Unavailable(
        val failure: ProductLookupFailure,
        internal val reason: String,
    ) : ProductSearchResult
}

fun classifyProductSearchInput(rawInput: String): ProductSearchInput {
    val input = rawInput.trim()
    if (input.isEmpty()) return ProductSearchInput.Invalid

    if (input.all(Char::isDigit)) {
        return when {
            input.length == OBIK_LENGTH -> ProductSearchInput.Obik(input)
            input.length in EAN_LENGTHS -> ProductSearchInput.Ean(input)
            else -> ProductSearchInput.Invalid
        }
    }

    return ProductSearchInput.Text(input)
}

class ProductSearchRepository(
    private val httpClient: ObiHttpClient = ObiHttpClient(),
    private val parser: ObiSearchParser = ObiSearchParser(),
) {
    fun search(query: String): ProductSearchResult {
        return when (val response = httpClient.fetchSearch(query)) {
            is ObiHttpResult.Success -> parser.parse(response.html)
                .fold(
                    onSuccess = { parsed ->
                        when (parsed) {
                            is ObiSearchParseResult.Results ->
                                ProductSearchResult.Candidates(parsed.items)
                            ObiSearchParseResult.NoResults ->
                                ProductSearchResult.NotFound
                        }
                    },
                    onFailure = { exception ->
                        ProductSearchResult.Unavailable(
                            failure = ProductLookupFailure.DATA,
                            reason = exception.message ?: "OBI search payload could not be parsed",
                        )
                    },
                )

            is ObiHttpResult.Failure -> when (response.kind) {
                ObiHttpFailureKind.NOT_FOUND -> ProductSearchResult.NotFound
                ObiHttpFailureKind.TRANSPORT,
                ObiHttpFailureKind.SERVER -> ProductSearchResult.Unavailable(
                    failure = ProductLookupFailure.NETWORK,
                    reason = response.reason,
                )
                ObiHttpFailureKind.DATA -> ProductSearchResult.Unavailable(
                    failure = ProductLookupFailure.DATA,
                    reason = response.reason,
                )
            }
        }
    }

    private companion object {
        const val OBIK_LENGTH = 7
        val EAN_LENGTHS = setOf(8, 12, 13, 14)
    }
}
