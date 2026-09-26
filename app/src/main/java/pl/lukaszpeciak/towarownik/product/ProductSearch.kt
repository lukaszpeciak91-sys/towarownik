package pl.lukaszpeciak.towarownik.product

import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticInputType
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticRecorder
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnostics

private const val OBIK_LENGTH = 7
private val EAN_LENGTHS = setOf(8, 12, 13, 14)
private val CONTROL_OR_WHITESPACE = Regex("""[\s\p{Cc}]+""")

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

fun normalizeProductSearchInput(rawInput: String): String =
    rawInput
        .replace(CONTROL_OR_WHITESPACE, " ")
        .trim()

fun classifyProductSearchInput(rawInput: String): ProductSearchInput {
    val input = normalizeProductSearchInput(rawInput)
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
    private val diagnostics: ObiDiagnosticRecorder = ObiDiagnostics.recorder,
) {
    fun search(query: String): ProductSearchResult {
        val normalizedQuery = normalizeProductSearchInput(query)
        val diagnosticInputType = when (classifyProductSearchInput(normalizedQuery)) {
            is ProductSearchInput.Ean -> ObiDiagnosticInputType.EAN
            is ProductSearchInput.Text -> ObiDiagnosticInputType.TEXT
            is ProductSearchInput.Obik -> ObiDiagnosticInputType.OBIK
            ProductSearchInput.Invalid -> ObiDiagnosticInputType.UNKNOWN
        }

        return when (
            val response = httpClient.fetchSearch(
                query = normalizedQuery,
                inputType = diagnosticInputType,
            )
        ) {
            is ObiHttpResult.Success -> {
                val parsed = parser.parse(
                    html = response.html,
                    diagnosticId = response.diagnosticId,
                )
                parsed.fold(
                    onSuccess = { parsedSearch ->
                        when (parsedSearch) {
                            is ObiSearchParseResult.Results -> {
                                diagnostics.finish(response.diagnosticId)
                                ProductSearchResult.Candidates(parsedSearch.items)
                            }
                            ObiSearchParseResult.NoResults -> {
                                diagnostics.mappingTrace(
                                    response.diagnosticId,
                                    "SearchParseResult.NoResults",
                                )
                                diagnostics.mappingTrace(
                                    response.diagnosticId,
                                    "ProductSearchResult.NotFound",
                                )
                                diagnostics.mappingTrace(response.diagnosticId, "UI NOT_FOUND")
                                diagnostics.finish(response.diagnosticId)
                                ProductSearchResult.NotFound
                            }
                        }
                    },
                    onFailure = { exception ->
                        diagnostics.mappingTrace(
                            response.diagnosticId,
                            "ProductLookupFailure.DATA",
                        )
                        diagnostics.mappingTrace(response.diagnosticId, "UI DATA_ERROR")
                        diagnostics.finish(response.diagnosticId)
                        ProductSearchResult.Unavailable(
                            failure = ProductLookupFailure.DATA,
                            reason = exception.message ?: "OBI search payload could not be parsed",
                        )
                    },
                )
            }

            is ObiHttpResult.Failure -> {
                val result = when (response.kind) {
                    ObiHttpFailureKind.NOT_FOUND -> {
                        diagnostics.mappingTrace(
                            response.diagnosticId,
                            "ProductSearchResult.NotFound",
                        )
                        diagnostics.mappingTrace(response.diagnosticId, "UI NOT_FOUND")
                        ProductSearchResult.NotFound
                    }

                    ObiHttpFailureKind.TRANSPORT,
                    ObiHttpFailureKind.SERVER -> {
                        diagnostics.mappingTrace(
                            response.diagnosticId,
                            "ProductLookupFailure.NETWORK",
                        )
                        diagnostics.mappingTrace(response.diagnosticId, "UI NETWORK_ERROR")
                        ProductSearchResult.Unavailable(
                            failure = ProductLookupFailure.NETWORK,
                            reason = response.reason,
                        )
                    }

                    ObiHttpFailureKind.DATA -> {
                        diagnostics.mappingTrace(
                            response.diagnosticId,
                            "ProductLookupFailure.DATA",
                        )
                        diagnostics.mappingTrace(response.diagnosticId, "UI DATA_ERROR")
                        ProductSearchResult.Unavailable(
                            failure = ProductLookupFailure.DATA,
                            reason = response.reason,
                        )
                    }
                }
                diagnostics.finish(response.diagnosticId)
                result
            }
        }
    }
}
