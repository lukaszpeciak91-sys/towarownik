package pl.lukaszpeciak.towarownik.agent

import java.text.Normalizer
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch

/** Model hints can constrain but cannot grant USER-owned inventory scope. */
internal sealed interface KwantAuthorizedLocations {
    data class Allowed(val requested: List<BranchId>, val nationwide: Boolean) :
        KwantAuthorizedLocations
    data class Denied(val reason: String) : KwantAuthorizedLocations
}

internal fun authorizeKwantLocations(
    userText: String,
    hints: List<String>,
    branches: List<ProviderBranch>,
    selected: BranchId,
): KwantAuthorizedLocations {
    fun norm(value: String, keepCommas: Boolean = false): String =
        Normalizer.normalize(value.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(if (keepCommas) Regex("""[^a-z0-9,]+""") else Regex("""[^a-z0-9]+"""), " ")
            .replace(Regex("""\s*,\s*"""), ", ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    if (branches.isEmpty() || branches.size > 100 ||
        branches.map { it.branchId }.distinct().size != branches.size ||
        selected !in branches.map { it.branchId }) {
        return KwantAuthorizedLocations.Denied("directory_unavailable")
    }
    val aliases = linkedMapOf<String, MutableSet<BranchId>>()
    branches.forEach { branch ->
        val name = norm(branch.name)
        if (name.isBlank()) return KwantAuthorizedLocations.Denied("directory_unavailable")
        for (alias in listOfNotNull(name, advisorCityLocative(name))) {
            aliases.getOrPut(alias) { linkedSetOf() }.add(branch.branchId)
        }
    }
    val names = aliases.keys.sortedByDescending { it.length }
    val byId = branches.associateBy { it.branchId.value }
    // 1000 W w Zamościu: W is a measurement, not an independent locality.
    val text = norm(userText, keepCommas = true).replace(
        Regex("""\b[0-9]{1,6}\s+w\b(?=\s+(?:w|we)\s+)"""), "",
    ).replace(Regex("""\s+"""), " ").trim()

    val explicit = linkedSetOf<BranchId>()
    val nonLocalPhrases = setOf(
        "wszystkich", "wszystkie", "innych", "inne", "pozostalych",
        "oddzialach", "hurtowniach", "ktorych", "jakich", "calym",
        "calej", "sieci",
    )
    fun boundary(s: String, length: Int): Boolean =
        s.length == length || !s[length].isLetterOrDigit()

    fun consumePlace(source: String): Pair<Set<BranchId>, String>? {
        var remaining = source.trimStart()
        remaining = remaining.replaceFirst(
            Regex("""^(?:w|we)\s+"""), "",
        )
        remaining = remaining.replaceFirst(
            Regex("""^(?:oddziale|oddzial|hurtowni)\s+(?:nr\s+)?"""), "",
        )
        remaining = remaining.replaceFirst(Regex("""^(?:w|we)\s+"""), "")
        val number = Regex("""^([0-9]{1,8})\b""").find(remaining)
        if (number != null) {
            val target = byId[number.groupValues[1]] ?: return null
            return setOf(target.branchId) to remaining.drop(number.value.length).trimStart()
        }
        val name = names.firstOrNull { remaining.startsWith(it) && boundary(remaining, it.length) }
            ?: return null
        return aliases.getValue(name) to remaining.drop(name.length).trimStart()
    }

    // Parse the COMPLETE comma / i / oraz chain. A later unknown place makes
    // the entire USER request ambiguous, rather than silently checking its prefix.
    val preposition = Regex("""\b(?:w|we)\s+""")
    for (match in preposition.findAll(text)) {
        val source = text.substring(match.range.last + 1).trimStart()
        if (nonLocalPhrases.any { source == it || source.startsWith("$it ") }) continue
        if (source.startsWith("kwant") || source.startsWith("tym ") ||
            source.startsWith("produk") || source.startsWith("stanie") ||
            source.startsWith("sklepie") || source.startsWith("sztuk")) continue

        val first = consumePlace(source)
        if (first == null) {
            // A repeated "w" inside "w oddziale w Zamościu" is handled
            // by the next preposition; all other unrecognized places fail.
            if (source.matches(Regex("""^(?:oddziale|hurtowni)\s+w\b.*"""))) continue
            return KwantAuthorizedLocations.Denied("unknown_location")
        }
        explicit.addAll(first.first)
        var previous = first.first
        var tail = first.second

        while (true) {
            val connector = Regex("""^(?:,|\bi\b|\boraz\b)\s*""").find(tail)
            if (connector != null) {
                tail = tail.drop(connector.value.length).trimStart()
                val next = consumePlace(tail)
                    ?: return KwantAuthorizedLocations.Denied("unknown_location")
                explicit.addAll(next.first)
                previous = next.first
                tail = next.second
                continue
            }
            // Paired descriptions must agree: "oddział 128 (Tarnów)"
            // or "Zamość, oddział 216" without a new connector are
            // NOT two independent authorizations.
            val paired = consumePlace(tail)
            if (paired != null && tail.isNotBlank()) {
                if (paired.first != previous) {
                    return KwantAuthorizedLocations.Denied("location_conflict")
                }
                tail = paired.second
                continue
            }
            break
        }
    }

    val broad = Regex(
        """\b(?:wszystkich|wszystkie|innych|pozostalych)\s+(?:oddzialach|hurtowniach|oddzialy)\b|""" +
            """\bgdzie\s+(?:jest\s+)?dostepn|\bw\s+jakich\s+oddzialach\b|""" +
            """\bw\s+oddzialach\b|\bw\s+calej\s+sieci\b""",
    ).containsMatchIn(text)
    val allowed = when {
        explicit.isNotEmpty() -> explicit.toList()
        broad -> branches.map { it.branchId }
        else -> listOf(selected)
    }
    for (hint in hints) {
        val h = norm(hint)
        val resolved = byId[h]?.let { setOf(it.branchId) }
            ?: aliases[h]
            ?: Regex("""^(?:oddzial|oddziale|hurtownia|kwant)\s+([0-9]{1,8})$""")
                .matchEntire(h)?.groupValues?.get(1)?.let {
                    byId[it]?.let { found -> setOf(found.branchId) }
                }
            ?: return KwantAuthorizedLocations.Denied("unknown_location")
        if (resolved.any { it !in allowed }) {
            return KwantAuthorizedLocations.Denied("location_not_authorized")
        }
    }
    return KwantAuthorizedLocations.Allowed(
        requested = if (broad && explicit.isEmpty()) emptyList() else allowed,
        nationwide = broad && explicit.isEmpty(),
    )
}
