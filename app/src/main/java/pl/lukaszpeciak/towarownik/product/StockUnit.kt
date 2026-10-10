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
        "szt", "szt.", "sztuka", "sztuki", "pcs", "pc" -> "szt."
        "m", "metr", "metry" -> "m"
        "kg", "kilogram" -> "kg"
        "l", "litr", "litry" -> "l"
        "opak", "opak.", "opakowanie", "op." -> "opak."
        else -> unit // Preserve other bounded, verified unit labels as provided.
    }
}

private val UNIT_LABEL = Regex("[\\p{L}\\p{N}²³./ -]{1,20}")

internal fun formatStockQuantity(stock: Int, verifiedUnit: String?): String {
    val unit = verifiedStockUnitOrNull(verifiedUnit)
    return if (unit == null) "$stock" else "$stock $unit"
}
