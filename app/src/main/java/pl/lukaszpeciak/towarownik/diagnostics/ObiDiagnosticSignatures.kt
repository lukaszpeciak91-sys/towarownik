package pl.lukaszpeciak.towarownik.diagnostics

private val PRODUCT_LINK = Regex("""(?:https?://(?:www\.)?obi\.pl)?/p/\d{7}(?:/|\?|#|$)""", RegexOption.IGNORE_CASE)
private val CANONICAL_LINK = Regex(
    """<link\b(?=[^>]*\brel\s*=\s*["']canonical["'])[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*>""",
    RegexOption.IGNORE_CASE,
)
private val TITLE = Regex("""<title\b[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val RESULT_COUNT = Regex("""Wyniki dla.*?\(\s*(\d+)\s*\)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val TAG = Regex("""<[^>]+>""")
private val WHITESPACE = Regex("""\s+""")

private const val ZERO_NO_RESULTS_FOUND = "Nie znaleźliśmy żadnych wyników"
private const val ZERO_PRODUCTS_NOT_FOUND = "Nie znaleziono produktów"
private const val ZERO_NO_RESULTS = "Brak wyników"

fun productBodySignatures(
    html: String,
    requestedObik: String,
): DiagnosticBodySignatures {
    val canonical = CANONICAL_LINK.find(html)?.groupValues?.get(1)
    return genericBodySignatures(html).copy(
        containsRequestedObik = html.contains(requestedObik),
        containsCanonicalProductUrl = canonical?.contains("/p/", ignoreCase = true) == true,
        canonicalUrl = sanitizeDiagnosticUrl(canonical),
        containsSelectedStore = html.contains("selectedStore"),
        containsStore075 = html.contains("075"),
    )
}

fun searchBodySignatures(html: String): DiagnosticBodySignatures {
    val plain = html.plainText()
    return genericBodySignatures(html).copy(
        containsSearchResultsPhrase = plain.contains("Wyniki dla", ignoreCase = true),
        detectedSearchResultCount = RESULT_COUNT.find(plain)?.groupValues?.get(1)?.toIntOrNull(),
        zeroResultPhraseNoResultsFound = plain.contains(ZERO_NO_RESULTS_FOUND, ignoreCase = true),
        zeroResultPhraseProductsNotFound = plain.contains(ZERO_PRODUCTS_NOT_FOUND, ignoreCase = true),
        zeroResultPhraseNoResults = plain.contains(ZERO_NO_RESULTS, ignoreCase = true),
        containsZeroCountToken = Regex("""\(\s*0\s*\)""").containsMatchIn(plain),
    )
}

internal fun genericBodySignatures(html: String): DiagnosticBodySignatures {
    val lower = html.lowercase()
    val indicators = listOf(
        "access denied",
        "captcha",
        "challenge",
        "cloudflare",
        "cf-chl",
        "verify you are human",
        "robot",
    ).filter(lower::contains)

    return DiagnosticBodySignatures(
        decodedBodyUtf8Bytes = html.toByteArray(Charsets.UTF_8).size,
        looksLikeHtml = html.contains("<html", ignoreCase = true) || html.contains("<!doctype html", ignoreCase = true),
        title = TITLE.find(html)?.groupValues?.get(1)?.plainText()?.take(MAX_TITLE_LENGTH),
        accessDeniedOrChallenge = indicators.isNotEmpty(),
        challengeIndicators = indicators,
        containsNuxtData = html.contains("__NUXT_DATA__"),
        containsRequestedObik = null,
        containsCanonicalProductUrl = null,
        canonicalUrl = null,
        containsSelectedStore = null,
        containsStore075 = null,
        recognizedProductLinkCount = PRODUCT_LINK.findAll(html).count(),
        containsSearchResultsPhrase = null,
        detectedSearchResultCount = null,
        zeroResultPhraseNoResultsFound = null,
        zeroResultPhraseProductsNotFound = null,
        zeroResultPhraseNoResults = null,
        containsZeroCountToken = null,
    )
}

private fun String.plainText(): String =
    replace(TAG, " ")
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace(WHITESPACE, " ")
        .trim()

private const val MAX_TITLE_LENGTH = 200
