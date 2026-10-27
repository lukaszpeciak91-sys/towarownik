package pl.lukaszpeciak.towarownik

/**
 * One Android boundary for newly submitted USER message text. Preserve meaningful
 * lines, spacing, tabs, Unicode and identifiers; normalize only line endings and
 * sanitize non-layout ISO controls using the existing persisted-message policy.
 *
 * Branch/location authority deliberately uses its separate legacy normalization.
 */
internal fun canonicalAdvisorUserMessage(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n').map { character ->
        if (character.isISOControl() && character != '\n' && character != '\t') ' ' else character
    }.joinToString("")
