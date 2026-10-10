package pl.lukaszpeciak.towarownik.product

import java.util.Locale

/**
 * Presentation-only unit from an explicitly verified provider field.
 * Missing/malformed values must never default to "szt.".
 */
internal fun verifiedStockUnitOrNull(raw: String?): String? {
    val unit = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (unit.length > 20 || !UNIT_LABEL.matches(unit)) return null
    return when (unit.lowercase(Locale.ROOT)) {
        "m", "metr", "metry", "na metr", "na metry" -> "m"
        "m²", "m2", "metr kwadratowy", "metry kwadratowe",
        "na m²", "na m2", "na metr kwadratowy", "na metry kwadratowe" -> "m²"
        "m³", "m3", "metr sześcienny", "metry sześcienne",
        "na m³", "na m3", "na metr sześcienny", "na metry sześcienne" -> "m³"
        "kg", "kilogram", "kilogramy", "na kg", "na kilogram", "na kilogramy" -> "kg"
        "l", "litr", "litry", "na litr", "na litry" -> "l"
        "opak", "opak.", "opakowanie", "opakowania", "op.",
        "na opakowanie", "na opakowania" -> "opak."
        "szt", "szt.", "sztuka", "sztuki", "pcs", "pc", "na sztukę", "na sztuki" -> "szt."
        else -> unit // Preserve other bounded, verified unit labels as provided.
    }
}

private val UNIT_LABEL = Regex("[\\p{L}\\p{N}²³./ -]{1,20}")

internal fun formatStockQuantity(stock: Int, verifiedUnit: String?): String {
    val unit = verifiedStockUnitOrNull(verifiedUnit)
    return if (unit == null) "$stock" else "$stock $unit"
}
