package pl.lukaszpeciak.towarownik.agent

import java.text.Normalizer
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch

/** A model location hint is only a constraint on the USER-authorized KWANT scope. */
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
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase().replace('ł', 'l'), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^a-z0-9]+"""), " ")
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
    val byId = branches.associateBy { it.branchId.value }
    val text = norm(userText)
    val explicit = linkedSetOf<BranchId>()
    val foundIds = Regex("""\b(?:oddzial(?:e|u)?|hurtowni)\s+(?:nr\s+)?([0-9]{1,8})\b""")
        .findAll(text).map { it.groupValues[1] }
    for (id in foundIds) {
        explicit += byId[id]?.branchId
            ?: return KwantAuthorizedLocations.Denied("unknown_location")
    }
    val locationWords = listOf(
        "wszystkich", "wszystkie", "innych", "inne", "pozostalych",
        "oddzialach", "hurtowniach", "ktorych", "jakich", "calym",
        "calej", "sieci",
    )
    // Constrain only explicit user place phrases; unknown named locations
    // require clarification rather than silently expanding to all branches.
    val places = Regex("""\b(?:w|we)\s+(?:(?:oddziale|hurtowni)\s+(?:w\s+)?)?([a-z]+(?:\s+[a-z]+)?)""")
    for (match in places.findAll(text)) {
        val phrase = match.groupValues[1]
        if (locationWords.any { phrase.startsWith(it + " ") || phrase == it }) continue
        val alias = aliases.keys.sortedByDescending { it.length }
            .firstOrNull { phrase == it || phrase.startsWith(it + " ") }
        if (alias != null) {
            explicit += aliases.getValue(alias)
        } else if (!phrase.startsWith("kwant") && !phrase.startsWith("tym ") &&
            !phrase.startsWith("produk") && !phrase.startsWith("stanie") &&
            !phrase.startsWith("sklepie") && !phrase.startsWith("sztuk")) {
            return KwantAuthorizedLocations.Denied("unknown_location")
        }
    }
    // Explicit "oddział Tarnów" / bare canonical city clarification.
    val exactBare = aliases[text.removePrefix("a w ").trim()]
    if (exactBare != null) explicit += exactBare
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
