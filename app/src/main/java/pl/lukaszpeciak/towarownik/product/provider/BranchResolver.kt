package pl.lukaszpeciak.towarownik.product.provider

import java.text.Normalizer

internal sealed interface BranchResolution {
    data object NotMentioned : BranchResolution

    data class CurrentBranch(
        val branch: ProviderBranch,
    ) : BranchResolution

    data class Resolved(
        val branch: ProviderBranch,
    ) : BranchResolution

    data class Ambiguous(
        val candidates: List<ProviderBranch>,
        val allowsExplicitBranchHint: Boolean = false,
    ) : BranchResolution

    data object UnknownMention : BranchResolution
}

/** Resolves current USER discovery scope; model hints never participate here. */
internal object BranchResolver {
    fun resolve(
        userText: String,
        branches: List<ProviderBranch>,
        currentBranchId: BranchId,
        allowBareBranchIds: Boolean = true,
    ): BranchResolution {
        if (branches.isEmpty() || branches.size > 100 ||
            branches.map { it.branchId }.distinct().size != branches.size ||
            branches.any { it.name.isBlank() }
        ) return BranchResolution.UnknownMention

        val current = branches.singleOrNull { it.branchId == currentBranchId }
        val text = normalizedText(userText)
        val scopes = mutableListOf<String>()
        // Scope begins at a place phrase, not every city/number in a product query.
        for (match in LOCATION_MARKER.findAll(text)) {
            // A watt unit in "203 W w Bochni" is not a second place marker.
            if (match.value.trim() == "w" &&
                text.substring(0, match.range.first).trimEnd().lastOrNull()?.isDigit() == true
            ) continue
            val source = text.substring(match.range.last + 1).trimStart()
            val first = source.substringBefore(' ')
            if (source.isBlank() || first in CONTEXT_WORDS ||
                first in LOCATION_NOUNS || first in setOf("obi", "kwant") ||
                MEASUREMENT_PREFIX.containsMatchIn(source)
            ) continue
            scopes += source
        }
        // A standalone market ID and an explicit comparison of IDs remain supported.
        val numericOnly = text.replaceFirst(Regex("""^porownaj\s+"""), "")
        if ((allowBareBranchIds || text.startsWith("porownaj ")) && Regex("""[0-9]+(?:\s+(?:i|oraz)\s+[0-9]+)*""").matches(numericOnly) &&
            !Regex("""[/\\x×*:.\-]""").containsMatchIn(userText)
        ) scopes += numericOnly

        val candidates = linkedSetOf<ProviderBranch>()
        for (scope in scopes.distinct()) {
            var tail = scope
            var place = consumePlace(tail, branches, current)
                ?: return BranchResolution.UnknownMention
            candidates += place.branches
            tail = place.tail
            while (true) {
                val connector = Regex("""^(?:i|oraz)\s+""").find(tail) ?: break
                tail = tail.drop(connector.value.length)
                    .replaceFirst(Regex("""^(?:w|we|na)\s+"""), "")
                place = consumePlace(tail, branches, current)
                    ?: return BranchResolution.UnknownMention
                candidates += place.branches
                tail = place.tail
            }
        }
        if (current != null && CURRENT_BRANCH_PHRASES.any {
                containsNormalizedPhrase(text, it)
            }
        ) candidates += current
        if (candidates.isEmpty()) return BranchResolution.NotMentioned
        if (candidates.size > 1) return BranchResolution.Ambiguous(
            candidates = candidates.sortedBy { it.branchId.value },
            // Only an entirely USER-named numeric set can be selected per call.
            allowsExplicitBranchHint = candidates.all {
                explicitNumericScopeContains(scopes, it.branchId.value)
            },
        )
        val branch = candidates.single()
        return if (branch.branchId == currentBranchId) {
            BranchResolution.CurrentBranch(branch)
        } else BranchResolution.Resolved(branch)
    }

    fun resolveHint(hint: String, branches: List<ProviderBranch>): ProviderBranch? =
        consumePlace(normalizedText(hint), branches, null)
            ?.takeIf { it.tail.isBlank() }?.branches?.singleOrNull()

    private data class Place(val branches: List<ProviderBranch>, val tail: String)

    private fun consumePlace(
        source: String,
        branches: List<ProviderBranch>,
        current: ProviderBranch?,
    ): Place? {
        var remaining = source.replaceFirst(
            Regex("""^(?:(?:w|we|na)\s+)?(?:obi|kwant|market|markecie|sklep|sklepie|oddzial|oddziale|hurtownia|hurtowni|magazyn|magazynie)\s+(?:nr\s+)?"""), "",
        ).replaceFirst(Regex("""^(?:w|we|na)\s+"""), "")
        var matched: List<ProviderBranch>
        val number = Regex("""^[0-9]+\b""").find(remaining)
        val names = branches.flatMap { branch -> cityAliases(branch.name).map { it to branch } }
        val city = names.map { it.first }.sortedByDescending(String::length)
            .firstOrNull { startsWithPhrase(remaining, it) }
        val alias = CURRENT_BRANCH_PHRASES.firstOrNull { startsWithPhrase(remaining, it) }
        when {
            number != null -> {
                if (MEASUREMENT_PREFIX.containsMatchIn(remaining)) return null
                matched = branches.filter { it.branchId.value == number.value }
                if (matched.size != 1) return null
                remaining = remaining.drop(number.value.length).trimStart()
            }
            city != null -> {
                matched = names.filter { it.first == city }.map { it.second }.distinct()
                remaining = remaining.drop(city.length).trimStart()
            }
            alias != null && current != null -> {
                matched = listOf(current)
                remaining = remaining.drop(alias.length).trimStart()
            }
            else -> {
                val street = matchStreet(remaining, branches) ?: return null
                return street
            }
        }
        // A paired city/ID is one location description and must agree.
        val pairedCity = names.map { it.first }.sortedByDescending(String::length)
            .firstOrNull { startsWithPhrase(remaining, it) }
        val pairedNumber = Regex("""^[0-9]+\b""").find(remaining)
        if (pairedCity != null) {
            val ids = names.filter { it.first == pairedCity }.map { it.second.branchId }
            matched = matched.filter { it.branchId in ids }
            remaining = remaining.drop(pairedCity.length).trimStart()
        } else if (pairedNumber != null && !MEASUREMENT_PREFIX.containsMatchIn(remaining)) {
            matched = matched.filter { it.branchId.value == pairedNumber.value }
            remaining = remaining.drop(pairedNumber.value.length).trimStart()
        }
        if (matched.isEmpty()) return null
        val streetSource = remaining.replaceFirst(Regex("""^(?:na|przy)\s+"""), "")
        val hasStreetMarker = streetSource != remaining ||
            Regex("""^(?:ul|ulica|al|aleja)\s+""").containsMatchIn(streetSource)
        val street = matchStreet(streetSource, branches)
        if (hasStreetMarker || street != null) {
            if (street == null) return null
            val ids = street.branches.map { it.branchId }
            matched = matched.filter { it.branchId in ids }
            if (matched.isEmpty()) return null
            remaining = street.tail
        }
        if (remaining.isNotBlank() && remaining.substringBefore(' ') !in CONTEXT_WORDS &&
            !Regex("""^(?:i|oraz|w|we|na)\b""").containsMatchIn(remaining) &&
            !MEASUREMENT_PREFIX.containsMatchIn(remaining)
        ) return null
        return Place(matched, remaining)
    }

    private fun matchStreet(source: String, branches: List<ProviderBranch>): Place? {
        val street = source.replaceFirst(Regex("""^(?:ul|ulica|al|aleja)\s+"""), "")
        val first = street.substringBefore(' ')
        if (first.isBlank()) return null
        var positions = branches.mapNotNull { branch ->
            val address = comparisonTokens(branch.address.orEmpty())
            val index = address.indexOf(canonicalLocationToken(first))
            if (index < 0 || first.length < 3 || first.any(Char::isDigit) ||
                canonicalLocationToken(first) in ADDRESS_NOISE ||
                canonicalLocationToken(first) in comparisonTokens(branch.name)
            ) null else AddressPosition(branch, address, index)
        }
        if (positions.isEmpty()) return null
        var words = street.split(' ')
        while (words.isNotEmpty()) {
            val matching = positions.filter {
                it.address.getOrNull(it.index) == canonicalLocationToken(words.first())
            }
            if (matching.isEmpty()) break
            positions = matching.map { it.copy(index = it.index + 1) }
            words = words.drop(1)
        }
        // The whole supplied street description must match, not an arbitrary
        // bag of address tokens assembled from different branches.
        if (words.isNotEmpty() && words.first() !in CONTEXT_WORDS &&
            words.first() !in setOf("i", "oraz", "w", "we", "na") &&
            !MEASUREMENT_PREFIX.containsMatchIn(words.joinToString(" "))
        ) return null
        return Place(positions.map { it.branch }.distinct(), words.joinToString(" "))
    }

    private data class AddressPosition(
        val branch: ProviderBranch,
        val address: List<String>,
        val index: Int,
    )

    private fun explicitNumericScopeContains(scopes: List<String>, id: String): Boolean =
        scopes.any { scope ->
            val stripped = scope.replaceFirst(Regex("""^(?:nr\s+)?"""), "")
            Regex("""[0-9]+(?:\s+(?:i|oraz)\s+[0-9]+)*""").matches(stripped) &&
                id in stripped.split(' ')
        }

    private fun cityAliases(name: String): List<String> {
        val canonical = normalizedText(name)
        // Exact aliases bind ONLY to a canonical name present in this directory.
        // No fuzzy matching and no static alias-to-branch-ID table.
        return listOfNotNull(canonical, CITY_LOCATIVES[canonical]) +
            if (canonical == "nowy sacz") listOf("sacz", "saczu", "nowym sacz") else emptyList()
    }

    private fun startsWithPhrase(text: String, phrase: String): Boolean =
        text == phrase || text.startsWith("$phrase ")

    private fun containsNormalizedPhrase(text: String, phrase: String): Boolean =
        (" $text ").contains(" $phrase ")

    private fun normalizedText(text: String): String =
        Normalizer.normalize(text.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ").trim()

    private fun comparisonTokens(text: String): List<String> =
        normalizedText(text).split(' ').map(::canonicalLocationToken)

    private fun canonicalLocationToken(token: String): String = when {
        token.length > 5 && token.endsWith("iej") -> token.dropLast(3) + "a"
        else -> token
    }

    private val LOCATION_NOUNS = setOf("market", "markecie", "sklep", "sklepie", "oddzial", "oddziale", "hurtownia", "hurtowni", "magazyn", "magazynie", "oddzialy", "oddzialach")
    private val LOCATION_MARKER = Regex(
        """\b(?:obi|kwant|market|markecie|sklep|sklepie|oddzial|oddziale|oddzialy|oddzialach|hurtownia|hurtowni|magazyn|magazynie|w|we|na)\s+""",
    )
    private val MEASUREMENT_PREFIX = Regex(
        """^[0-9]+\s+(?:m|mm|cm|m2|m3|kg|l|ml|szt|opak|w|kw|v|a|zl|x)\b""",
    )
    private val CONTEXT_WORDS = setOf(
        "jesli", "jezeli", "czy", "nie", "to", "ma", "mam", "mamy", "jest", "sa", "byl", "byla", "bylo", "moze",
        "prosze", "potrzebuje", "szukam", "dostepne", "dostepny", "podobne", "takie", "tym", "tej", "naszym", "naszej",
        "podobnych", "tych", "parametrach", "przyklad", "peszlu", "rurze", "jutro", "dzis", "teraz", "kolorze", "rozmiarze", "opakowaniu", "ziemi", "domu", "ogrodzie", "scianie", "drzwiach", "stanie", "sprzedazy", "sztukach",
    )
    private val ADDRESS_NOISE = setOf("centrum", "ch", "ul", "ulica", "al", "aleja")
    private val CURRENT_BRANCH_PHRASES = setOf(
        "u nas", "na naszym magazynie", "w naszym magazynie", "na naszym oddziale", "w naszym oddziale", "w naszym sklepie", "w naszym markecie",
    )
    private val CITY_LOCATIVES = mapOf(
        "bialystok" to "bialymstoku", "bochnia" to "bochni", "brzesko" to "brzesku", "bydgoszcz" to "bydgoszczy", "chelm" to "chelmie",
        "dabrowa tarnowska" to "dabrowie tarnowskiej", "kielce" to "kielcach", "krakow" to "krakowie", "krosno" to "krosnie", "lublin" to "lublinie",
        "lodz" to "lodzi", "mielec" to "mielcu", "nowy sacz" to "nowym saczu", "ostroleka" to "ostrolece", "piaseczno" to "piasecznie",
        "rzeszow" to "rzeszowie", "suwalki" to "suwalkach", "tarnow" to "tarnowie", "warszawa" to "warszawie", "wloclawek" to "wloclawku",
        "zamosc" to "zamosciu", "miejsce piastowe" to "miejscu piastowym", "wroclaw" to "wroclawiu", "gdansk" to "gdansku", "poznan" to "poznaniu",
        "bytom" to "bytomiu", "czeladz" to "czeladzi", "czestochowa" to "czestochowie", "gdynia" to "gdyni",
        "jablonna" to "jablonnie", "jastrzebie zdroj" to "jastrzebiu zdroju", "katowice" to "katowicach", "kobierzyce" to "kobierzycach",
        "kobylnica k slupska" to "kobylnicy k slupska", "leszno" to "lesznie", "lipienice" to "lipienicach", "lubin" to "lubinie",
        "mysiadlo" to "mysiadle", "olsztyn" to "olsztynie", "opole" to "opolu", "ostrow wielkopolski" to "ostrowie wielkopolskim",
        "piotrkow trybunalski" to "piotrkowie trybunalskim", "plock" to "plocku", "radom" to "radomiu", "rybnik" to "rybniku",
        "sochaczew" to "sochaczewie", "szczecin" to "szczecinie", "tychy" to "tychach", "walbrzych" to "walbrzychu", "zabki" to "zabkach", "zabrze" to "zabrzu",
        "torun" to "toruniu", "dabrowa gornicza" to "dabrowie gorniczej", "gorzow wielkopolski" to "gorzowie wielkopolskim",
    )
}
