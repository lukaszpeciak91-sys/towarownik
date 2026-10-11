package pl.lukaszpeciak.towarownik.agent

import java.text.Normalizer

/**
 * Shared, USER-text-only product identity policy for history selection and
 * location-tool authorization. This must not be expanded from model hints.
 */
internal class AdvisorProductIdentityReferences private constructor(
    private val userText: String,
    val referencedNumericIds: Set<String>,
) {
    fun mentions(identifier: String): Boolean {
        if (identifier.isBlank()) return false
        val value = identifier.lowercase()
        return when {
            // Three-digit OBI store IDs (003, 052, 075) are locations.
            value.matches(Regex("""[0-9]{1,3}""")) -> false
            // Short KWANT product IDs/articles require explicit product context.
            value.matches(Regex("""[0-9]{4,6}""")) -> value in referencedNumericIds
            // Preserve bounded 7–13 digit OBIK/EAN and non-numeric mentions.
            else -> Regex(
                """(?<![a-z0-9])""" + Regex.escape(value) + """(?![a-z0-9])""",
            ).containsMatchIn(userText)
        }
    }

    companion object {
        fun fromUserText(text: String): AdvisorProductIdentityReferences {
            val lowercase = text.lowercase()
            fun hasMeasurementUnitAfter(source: String, endIndex: Int): Boolean {
                val following = source.substring(endIndex + 1)
                return Regex("""^\s*(?:mm|cm|m2|m3|m|kg|kw|kwh|v|a|l|ml|szt|opak)\b""")
                    .containsMatchIn(following) ||
                    // "1000 W w oddziale..." is watts; "1000 w Zamościu" is a location.
                    Regex("""^\s*w\b(?:\s+w\b|\s*$|\s*[,.;!?])""")
                        .containsMatchIn(following)
            }
            val labeled = Regex(
                """\b(?:obik|ean|sku|id(?:\s+produktu)?|produkt(?:u|y|ow)?|""" +
                    """kod(?:u|zie)?|art\.?|artykul(?:u)?|indeks(?:u)?|""" +
                    """model(?:u)?|gniazd(?:a|o)|numer(?:\s+katalogowy)?|""" +
                    """nr(?:\s+katalogowy)?)\s*(?:nr\s*)?[:#-]?\s*([0-9]{4,13})\b""",
            ).findAll(lowercase)
                .filterNot { it.groupValues[1].length <= 6 &&
                    hasMeasurementUnitAfter(lowercase, it.range.last)
                }.map { it.groupValues[1] }
            val directCheck = Regex("""\bsprawd[zź](?:cie)?\s+([0-9]{4,6})\b""")
                .findAll(lowercase)
                .filterNot { hasMeasurementUnitAfter(lowercase, it.range.last) }
                .map { it.groupValues[1] }
            val normalized = Normalizer.normalize(
                lowercase.replace('ł', 'l'), Normalizer.Form.NFD,
            ).replace(Regex("""\p{M}+"""), "")
                .replace(Regex("""[^a-z0-9]+"""), " ")
                .trim()
            val naturalStockQuestion = Regex(
                """^(?:sprawdz(?:cie)?|jaki jest)\s+(?:stan|dostepnosc)\s+([0-9]{4,6})\b""",
            ).findAll(normalized)
                .filterNot { hasMeasurementUnitAfter(normalized, it.range.last) }
                .map { it.groupValues[1] }
            // Keep the existing long OBIK/EAN fail-closed check unchanged.
            val longNumbers = Regex("""(?<![0-9])[0-9]{7,13}(?![0-9])""")
                .findAll(lowercase).map { it.value }
            return AdvisorProductIdentityReferences(
                userText = lowercase,
                referencedNumericIds = (
                    labeled + directCheck + naturalStockQuestion + longNumbers
                ).toSet(),
            )
        }
    }
}
