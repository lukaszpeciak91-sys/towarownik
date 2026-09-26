package pl.lukaszpeciak.towarownik

import pl.lukaszpeciak.towarownik.product.normalizeProductSearchInput

internal data class SearchSubmission(
    val submittedQuery: String,
    val nextVisibleQuery: String,
)

internal fun prepareSearchSubmission(currentInput: String): SearchSubmission =
    SearchSubmission(
        submittedQuery = normalizeProductSearchInput(currentInput),
        nextVisibleQuery = "",
    )
