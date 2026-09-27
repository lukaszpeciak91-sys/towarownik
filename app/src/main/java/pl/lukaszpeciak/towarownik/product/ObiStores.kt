package pl.lukaszpeciak.towarownik.product

internal const val DEFAULT_OBI_STORE_NUMBER = "075"

// Confirmed against the official OBI Poland customer-relations market list
// on 2026-09-27. Runtime code never discovers or expands this allowlist.
internal val SUPPORTED_OBI_STORE_NUMBERS: List<String> = listOf(
    "001", "002", "003", "004", "006", "007", "008", "009",
    "011", "012", "013", "015", "016", "017", "018", "019",
    "020", "022", "024", "027", "028", "029", "030", "031",
    "033", "035", "036", "037", "038", "039", "040", "041",
    "043", "044", "048", "049", "050", "051", "052", "053",
    "055", "056", "057", "059", "060", "061", "062", "064",
    "065", "066", "067", "068", "069", "070", "071", "072",
    "073", "074", "075", "076", "077", "078",
)

private val SUPPORTED_OBI_STORE_NUMBER_SET =
    SUPPORTED_OBI_STORE_NUMBERS.toSet()
private val OBI_STORE_NUMBER_PATTERN = Regex("""\d{3}""")
private val OBI_STORE_TOKEN_PATTERN = Regex("""(?<!\d)(\d{3})(?!\d)""")

internal fun isSupportedObiStoreNumber(
    storeNumber: String,
): Boolean =
    OBI_STORE_NUMBER_PATTERN.matches(storeNumber) &&
        storeNumber in SUPPORTED_OBI_STORE_NUMBER_SET

internal fun supportedObiStoreTokens(
    text: String,
): Set<String> =
    OBI_STORE_TOKEN_PATTERN
        .findAll(text)
        .map { it.groupValues[1] }
        .filter(::isSupportedObiStoreNumber)
        .toSet()
