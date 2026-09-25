package pl.lukaszpeciak.towarownik.product

internal sealed interface ObiSearchParseResult {
    data class Results(val items: List<ProductSearchCandidate>) : ObiSearchParseResult
    data object NoResults : ObiSearchParseResult
}

class ObiSearchParser {
    fun parse(html: String): Result<ObiSearchParseResult> = runCatching {
        val candidates = LinkedHashMap<String, String?>()

        PRODUCT_LINK.findAll(html).forEach { match ->
            val href = match.groupValues[2]
            val obik = PRODUCT_PATH.find(href)?.groupValues?.get(1) ?: return@forEach
            val name = match.groupValues[3].plainText().takeIf(String::isNotBlank)

            if (obik !in candidates) {
                candidates[obik] = name
            } else if (candidates[obik] == null && name != null) {
                candidates[obik] = name
            }
        }

        if (candidates.isNotEmpty()) {
            return@runCatching ObiSearchParseResult.Results(
                candidates.entries
                    .take(MAX_RESULTS)
                    .map { (obik, name) -> ProductSearchCandidate(obik, name) },
            )
        }

        val canonicalObik = CANONICAL_PRODUCT.find(html)?.groupValues?.get(1)
        if (canonicalObik != null) {
            return@runCatching ObiSearchParseResult.Results(
                listOf(ProductSearchCandidate(canonicalObik, null)),
            )
        }

        val pageText = html.plainText()
        if (pageText.contains("Wyniki dla", ignoreCase = true) && ZERO_RESULT_COUNT.containsMatchIn(pageText)) {
            return@runCatching ObiSearchParseResult.NoResults
        }
        if (
            pageText.contains("Nie znaleziono produktów", ignoreCase = true) ||
            pageText.contains("Brak wyników", ignoreCase = true)
        ) {
            return@runCatching ObiSearchParseResult.NoResults
        }

        error("OBI search payload has no recognizable product results or explicit empty state")
    }

    private companion object {
        const val MAX_RESULTS = 5

        val PRODUCT_LINK = Regex(
            """<a\b([^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*)>(.*?)</a>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val PRODUCT_PATH = Regex("""(?:https?://(?:www\.)?obi\.pl)?/p/(\d{7})(?:/|\?|#|$)""", RegexOption.IGNORE_CASE)
        val CANONICAL_PRODUCT = Regex(
            """<link\b(?=[^>]*\brel\s*=\s*["']canonical["'])[^>]*\bhref\s*=\s*["'][^"']*/p/(\d{7})(?:/[^"']*)?["'][^>]*>""",
            RegexOption.IGNORE_CASE,
        )
        val ZERO_RESULT_COUNT = Regex("""\(\s*0\s*\)""")
        val TAG = Regex("""<[^>]+>""")
        val WHITESPACE = Regex("""\s+""")
        val DECIMAL_ENTITY = Regex("""&#(\d+);""")
        val HEX_ENTITY = Regex("""&#x([0-9a-fA-F]+);""")
    }

    private fun String.plainText(): String {
        var text = replace(TAG, " ")
            .replace("&nbsp;", " ", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
            .replace("&quot;", """, ignoreCase = true)
            .replace("&#39;", "'", ignoreCase = true)
            .replace("&lt;", "<", ignoreCase = true)
            .replace("&gt;", ">", ignoreCase = true)

        text = DECIMAL_ENTITY.replace(text) { match ->
            match.groupValues[1].toIntOrNull()?.let(::codePointToString) ?: match.value
        }
        text = HEX_ENTITY.replace(text) { match ->
            match.groupValues[1].toIntOrNull(16)?.let(::codePointToString) ?: match.value
        }

        return text.replace(WHITESPACE, " ").trim()
    }

    private fun codePointToString(value: Int): String =
        runCatching { String(Character.toChars(value)) }.getOrDefault("")
}
