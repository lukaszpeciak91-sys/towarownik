package pl.lukaszpeciak.towarownik.agent

import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope
import pl.lukaszpeciak.towarownik.product.provider.ProviderProduct

internal const val ADVISOR_PRODUCT_DESCRIPTION_MAX_CHARS = 220
internal const val ADVISOR_PRODUCT_TECHNICAL_FACTS_MAX = 6
internal const val ADVISOR_PRODUCT_FACT_LABEL_MAX_CHARS = 60
internal const val ADVISOR_PRODUCT_FACT_VALUE_MAX_CHARS = 100

internal fun ProviderProduct.toAdvisorVerifiedProduct(): AdvisorVerifiedProduct =
    AdvisorVerifiedProduct(
        obik = ref.productId,
        productId = ref.productId,
        articleNumber = articleNumber,
        name = name,
        brand = brand,
        shortDescription = shortDescription.toAdvisorBoundText(
            ADVISOR_PRODUCT_DESCRIPTION_MAX_CHARS,
        ),
        technicalFacts = technicalFacts
            .asSequence()
            .mapNotNull { fact ->
                val label = fact.label.toAdvisorBoundText(
                    ADVISOR_PRODUCT_FACT_LABEL_MAX_CHARS,
                )
                val value = fact.value.toAdvisorBoundText(
                    ADVISOR_PRODUCT_FACT_VALUE_MAX_CHARS,
                )
                if (label.isNullOrEmpty() || value.isNullOrEmpty()) {
                    null
                } else {
                    AdvisorTechnicalFact(label, value)
                }
            }
            .take(ADVISOR_PRODUCT_TECHNICAL_FACTS_MAX)
            .toList(),
        stock = stock,
        centralStock = centralStock,
        price = grossPrice,
        priceScope = when (priceScope) {
            ProviderPriceScope.BRANCH -> "branch"
            ProviderPriceScope.ONLINE -> "online"
            null -> null
        },
    )

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
