package pl.lukaszpeciak.towarownik.agent

import java.text.Normalizer
import pl.lukaszpeciak.towarownik.product.LocalProduct
import pl.lukaszpeciak.towarownik.product.TechnicalFact
import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct

internal const val ADVISOR_PRODUCT_DESCRIPTION_MAX_CHARS = 220
internal const val ADVISOR_PRODUCT_TECHNICAL_FACTS_MAX = 6
internal const val ADVISOR_PRODUCT_FACT_LABEL_MAX_CHARS = 60
internal const val ADVISOR_PRODUCT_FACT_VALUE_MAX_CHARS = 100

internal fun ProviderProduct.toAdvisorVerifiedProduct(
    query: String,
): AdvisorVerifiedProduct {
    val shapedFacts = shapeAdvisorProductFacts(
        query = query,
        shortDescription = shortDescription,
        technicalFacts = technicalFacts,
    )
    return AdvisorVerifiedProduct(
        obik = ref.productId,
        productId = ref.productId,
        articleNumber = articleNumber,
        name = name,
        brand = brand,
        shortDescription = shapedFacts.shortDescription,
        technicalFacts = shapedFacts.technicalFacts,
        stock = stock,
        centralStock = centralStock,
        price = grossPrice,
        priceScope = when (priceScope) {
            ProviderPriceScope.BRANCH -> "branch"
            ProviderPriceScope.ONLINE -> "online"
            null -> null
        },
    )
}

internal fun LocalProduct.toAdvisorVerifiedProduct(
    query: String,
): AdvisorVerifiedProduct {
    val shapedFacts = shapeAdvisorProductFacts(
        query = query,
        shortDescription = shortDescription,
        technicalFacts = technicalFacts,
    )
    return AdvisorVerifiedProduct(
        obik = obik,
        name = name,
        brand = brand,
        shortDescription = shapedFacts.shortDescription,
        technicalFacts = shapedFacts.technicalFacts,
        stock = stock,
        price = grossPrice,
    )
}

private data class AdvisorBoundProductFacts(
    val shortDescription: String?,
    val technicalFacts: List<AdvisorTechnicalFact>,
)

private data class AdvisorFactCandidate(
    val sourceIndex: Int,
    val fact: AdvisorTechnicalFact,
)

private data class AdvisorRelevance(
    val score: Int,
    val clear: Boolean,
)

private data class AdvisorComparisonProfile(
    val tokens: Set<String>,
    val technicalSignals: Set<String>,
)

private fun shapeAdvisorProductFacts(
    query: String,
    shortDescription: String?,
    technicalFacts: List<TechnicalFact>,
): AdvisorBoundProductFacts {
    val candidates = technicalFacts.mapIndexedNotNull { index, fact ->
        val label = fact.label.toAdvisorBoundText(
            ADVISOR_PRODUCT_FACT_LABEL_MAX_CHARS,
        )
        val value = fact.value.toAdvisorBoundText(
            ADVISOR_PRODUCT_FACT_VALUE_MAX_CHARS,
        )
        if (label.isNullOrEmpty() || value.isNullOrEmpty()) {
            null
        } else {
            AdvisorFactCandidate(
                sourceIndex = index,
                fact = AdvisorTechnicalFact(label, value),
            )
        }
    }

    return AdvisorBoundProductFacts(
        shortDescription = shortDescription.toAdvisorBoundText(
            ADVISOR_PRODUCT_DESCRIPTION_MAX_CHARS,
        ),
        technicalFacts = selectAdvisorFacts(
            query = query,
            candidates = candidates,
        ).map(AdvisorFactCandidate::fact),
    )
}

private fun selectAdvisorFacts(
    query: String,
    candidates: List<AdvisorFactCandidate>,
): List<AdvisorFactCandidate> {
    if (candidates.size <= ADVISOR_PRODUCT_TECHNICAL_FACTS_MAX) {
        return candidates
    }

    val selected = candidates
        .take(ADVISOR_PRODUCT_TECHNICAL_FACTS_MAX)
        .toMutableList()
    val queryProfile = comparisonProfile(query)
    if (
        queryProfile.tokens.isEmpty() &&
        queryProfile.technicalSignals.isEmpty()
    ) {
        return selected
    }

    val selectedRelevance = selected.map {
        relevance(queryProfile, it.fact)
    }.toMutableList()

    var rescued: AdvisorFactCandidate? = null
    var rescuedRelevance: AdvisorRelevance? = null
    candidates
        .drop(ADVISOR_PRODUCT_TECHNICAL_FACTS_MAX)
        .forEach { candidate ->
            val candidateRelevance = relevance(queryProfile, candidate.fact)
            if (!candidateRelevance.clear) return@forEach
            val current = rescuedRelevance
            if (
                current == null ||
                candidateRelevance.score > current.score
            ) {
                rescued = candidate
                rescuedRelevance = candidateRelevance
            }
        }

    val rescue = rescued ?: return selected
    val rescueRelevance = rescuedRelevance ?: return selected

    var replaceIndex = selected.lastIndex
    var weakestScore = selectedRelevance[replaceIndex].score
    for (index in selected.indices.reversed()) {
        val score = selectedRelevance[index].score
        if (score < weakestScore) {
            weakestScore = score
            replaceIndex = index
        }
    }

    if (rescueRelevance.score <= weakestScore) {
        return selected
    }

    selected[replaceIndex] = rescue
    return selected
}

private fun relevance(
    query: AdvisorComparisonProfile,
    fact: AdvisorTechnicalFact,
): AdvisorRelevance {
    val factProfile = comparisonProfile(
        fact.label + " " + fact.value,
    )
    val signalMatches =
        query.technicalSignals.intersect(factProfile.technicalSignals).size
    val sharedTokens = query.tokens.intersect(factProfile.tokens)
    val aliasMatches = sharedTokens.count(TECHNICAL_CANONICAL_TOKENS::contains)
    val numericMatches = sharedTokens.count { token ->
        token.firstOrNull()?.isDigit() == true
    }
    val lexicalMatches =
        sharedTokens.size - aliasMatches - numericMatches

    val clear =
        signalMatches > 0 ||
            aliasMatches > 0 ||
            (
                sharedTokens.size >= 2 &&
                    (
                        numericMatches > 0 ||
                            sharedTokens.any { it.length >= 3 }
                    )
            )

    return AdvisorRelevance(
        score =
            signalMatches * 100 +
                aliasMatches * 20 +
                numericMatches * 8 +
                lexicalMatches * 3,
        clear = clear,
    )
}

private fun comparisonProfile(text: String): AdvisorComparisonProfile {
    val normalized = text.toComparisonText()
    val tokens = TOKEN.findAll(normalized)
        .map { it.value }
        .map(::canonicalToken)
        .filter { token ->
            token !in STOPWORDS &&
                (token.length > 1 || token.firstOrNull()?.isDigit() == true)
        }
        .toSet()

    return AdvisorComparisonProfile(
        tokens = tokens,
        technicalSignals = technicalSignals(normalized),
    )
}

private fun canonicalToken(raw: String): String =
    TECHNICAL_ALIASES[raw] ?: raw

private fun technicalSignals(normalized: String): Set<String> {
    val signals = linkedSetOf<String>()
    IP_SIGNAL.findAll(normalized).forEach {
        signals += "ip" + it.groupValues[1]
    }
    VALUE_UNIT_SIGNAL.findAll(normalized).forEach {
        val number = it.groupValues[1]
        val unit = it.groupValues[2]
        signals += number + unit
    }
    return signals
}

private fun String.toComparisonText(): String =
    Normalizer.normalize(lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace('²', '2')
        .replace(',', '.')
        .replace(Regex("[^\\p{L}\\p{N}.]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String?.toAdvisorBoundText(maxChars: Int): String? {
    val normalized = this
        ?.replace(Regex("[\\s\\u0000-\\u001f\\u007f]+"), " ")
        ?.trim()
        .orEmpty()
    if (normalized.isEmpty()) return null
    if (normalized.length <= maxChars) return normalized

    val prefix = normalized.take(maxChars).trimEnd()
    val lastSpace = prefix.lastIndexOf(' ')
    return if (lastSpace >= maxChars - 24) {
        prefix.substring(0, lastSpace).trimEnd()
    } else {
        prefix
    }
}

private val TOKEN = Regex("""[\p{L}\p{N}.]+""")
private val IP_SIGNAL = Regex("""\bip\s*([0-9]{2,3})\b""")
private val VALUE_UNIT_SIGNAL = Regex(
    """\b([0-9]+(?:\.[0-9]+)?)\s*(a|v|w|kw|mw|hz|mm2|cm2|m2)\b""",
)

private val TECHNICAL_ALIASES = mapOf(
    "voltage" to "voltage",
    "napiecie" to "voltage",
    "napiecia" to "voltage",
    "current" to "current",
    "prad" to "current",
    "pradu" to "current",
    "power" to "power",
    "moc" to "power",
    "mocy" to "power",
    "phase" to "phase",
    "phases" to "phase",
    "faza" to "phase",
    "fazy" to "phase",
    "faz" to "phase",
    "pole" to "pole",
    "poles" to "pole",
    "biegun" to "pole",
    "bieguny" to "pole",
    "biegunow" to "pole",
    "ip" to "ip",
    "diameter" to "diameter",
    "srednica" to "diameter",
    "srednicy" to "diameter",
    "crosssection" to "cross_section",
    "przekroj" to "cross_section",
    "przekroju" to "cross_section",
    "thread" to "thread",
    "gwint" to "thread",
    "gwintu" to "thread",
    "dimension" to "dimensions",
    "dimensions" to "dimensions",
    "wymiar" to "dimensions",
    "wymiary" to "dimensions",
    "wymiarow" to "dimensions",
)

private val TECHNICAL_CANONICAL_TOKENS =
    TECHNICAL_ALIASES.values.toSet()

private val STOPWORDS = setOf(
    "i",
    "oraz",
    "do",
    "na",
    "z",
    "ze",
    "w",
    "we",
    "dla",
    "czy",
    "jaki",
    "jaka",
    "jakie",
    "jakiego",
    "chce",
    "szukam",
    "potrzebuje",
    "produkt",
    "produkty",
    "the",
    "and",
    "for",
    "with",
    "what",
    "which",
    "need",
    "want",
)
