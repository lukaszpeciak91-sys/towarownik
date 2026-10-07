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

internal object BranchResolver {
    fun resolve(
        userText: String,
        branches: List<ProviderBranch>,
        currentBranchId: BranchId,
    ): BranchResolution {
        if (branches.isEmpty()) return BranchResolution.NotMentioned

        val current = branches.singleOrNull {
            it.branchId == currentBranchId
        }
        val textTokens = comparisonTokens(userText)
        val naturalLocationIntent =
            hasExplicitBranchLocationIntent(userText)
        val metadataMatches = branches
            .mapNotNull { branch ->
                branchMentionScore(
                    textTokens = textTokens,
                    rawText = userText,
                    branch = branch,
                    allowNaturalMetadata =
                        naturalLocationIntent &&
                            hasScopedNaturalReference(
                                userText = userText,
                                branch = branch,
                            ),
                ).takeIf { it > 0 }?.let { score ->
                    BranchMatch(branch, score)
                }
            }

        val strongestMetadata = metadataMatches
            .maxOfOrNull(BranchMatch::score)
            ?.let { strongest ->
                metadataMatches.filter { it.score == strongest }
            }
            .orEmpty()

        val currentAliasMentioned =
            current != null && currentBranchAliasMentioned(
                userText = userText,
                currentBranch = current,
            )

        val candidates = buildList {
            strongestMetadata.forEach { add(it.branch) }
            if (
                currentAliasMentioned &&
                current != null &&
                none { it.branchId == current.branchId }
            ) {
                add(current)
            }
        }.distinctBy(ProviderBranch::branchId)

        if (candidates.size > 1) {
            return BranchResolution.Ambiguous(
                candidates = candidates.sortedBy { it.branchId.value },
                allowsExplicitBranchHint = candidates.all {
                    containsExactBranchId(
                        userText,
                        it.branchId.value,
                    )
                },
            )
        }

        candidates.singleOrNull()?.let { branch ->
            return if (branch.branchId == currentBranchId) {
                BranchResolution.CurrentBranch(branch)
            } else {
                BranchResolution.Resolved(branch)
            }
        }

        return if (naturalLocationIntent) {
            BranchResolution.UnknownMention
        } else {
            BranchResolution.NotMentioned
        }
    }

    fun resolveHint(
        hint: String,
        branches: List<ProviderBranch>,
    ): ProviderBranch? {
        val textTokens = comparisonTokens(hint)
        val matches = branches.mapNotNull { branch ->
            branchMentionScore(
                textTokens = textTokens,
                rawText = hint,
                branch = branch,
            ).takeIf { it > 0 }?.let { score ->
                BranchMatch(branch, score)
            }
        }
        val strongest = matches.maxOfOrNull(BranchMatch::score)
            ?: return null
        return matches
            .filter { it.score == strongest }
            .map(BranchMatch::branch)
            .distinctBy(ProviderBranch::branchId)
            .singleOrNull()
    }

    private fun branchMentionScore(
        textTokens: List<String>,
        rawText: String,
        branch: ProviderBranch,
        allowNaturalMetadata: Boolean = true,
    ): Int {
        var score = 0

        if (containsExactBranchId(rawText, branch.branchId.value)) {
            score += 1_000
        }

        if (allowNaturalMetadata) {
            val nameTokens = comparisonTokens(branch.name)
            if (
                nameTokens.isNotEmpty() &&
                containsTokenSequence(textTokens, nameTokens)
            ) {
                score += 200
            }

            val addressTokens = significantAddressTokens(branch.address)
            val addressMatches = addressTokens.count(textTokens::contains)
            if (addressMatches > 0) {
                score += 300 + addressMatches * 20
            }
        }

        return score
    }

    private fun currentBranchAliasMentioned(
        userText: String,
        currentBranch: ProviderBranch,
    ): Boolean {
        val normalized = normalizedText(userText)
        if (CURRENT_BRANCH_PHRASES.any {
                containsNormalizedPhrase(normalized, it)
            }
        ) {
            return true
        }

        val currentName = normalizedText(currentBranch.name)
        val aliases = CURRENT_BRANCH_NAME_ALIASES[currentName].orEmpty()
        return aliases.any {
            containsNormalizedPhrase(normalized, it)
        }
    }

    private fun hasExplicitBranchLocationIntent(
        userText: String,
    ): Boolean {
        val normalized = normalizedText(userText)
        return PROVIDER_LOCATION_INTENT.containsMatchIn(normalized) ||
            BRANCH_NOUN_LOCATION_INTENT.containsMatchIn(normalized)
    }

    private fun hasScopedNaturalReference(
        userText: String,
        branch: ProviderBranch,
    ): Boolean {
        val tokens = normalizedText(userText)
            .split(' ')
            .filter(String::isNotBlank)
            .map(::canonicalLocationToken)
        val branchNameTokens = comparisonTokens(branch.name)
        val addressTokens = significantAddressTokens(branch.address)

        return tokens.indices.any { index ->
            if (tokens[index] !in LOCATION_INTENT_MARKERS) {
                return@any false
            }
            var locationIndex = index + 1
            while (
                locationIndex < tokens.size &&
                tokens[locationIndex] in LOCATION_CONNECTORS
            ) {
                locationIndex += 1
            }
            if (locationIndex >= tokens.size) {
                return@any false
            }

            val afterMarker = tokens.drop(locationIndex)
            containsTokenSequence(afterMarker, branchNameTokens) ||
                afterMarker.first() in addressTokens
        }
    }

    private fun significantAddressTokens(
        address: String?,
    ): Set<String> =
        address
            ?.let(::comparisonTokens)
            .orEmpty()
            .filter { token ->
                token.length >= 3 &&
                    token !in ADDRESS_NOISE &&
                    token.none(Char::isDigit)
            }
            .toSet()

    private fun comparisonTokens(text: String): List<String> =
        normalizedText(text)
            .split(' ')
            .asSequence()
            .filter(String::isNotBlank)
            .filterNot(STREET_PREFIXES::contains)
            .map(::canonicalLocationToken)
            .toList()

    private fun normalizedText(text: String): String =
        Normalizer.normalize(
            text.lowercase(),
            Normalizer.Form.NFD,
        )
            .replace(Regex("""\p{M}+"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun canonicalLocationToken(token: String): String =
        when {
            token.length > 5 && token.endsWith("iej") ->
                token.dropLast(3) + "a"
            token.length > 5 && token.endsWith("sciu") ->
                token.dropLast(2)
            token.length > 5 && token.endsWith("owie") ->
                token.dropLast(2)
            else -> token
        }

    private fun containsTokenSequence(
        haystack: List<String>,
        needle: List<String>,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return haystack
            .windowed(needle.size)
            .any { it == needle }
    }

    private fun containsExactBranchId(
        text: String,
        branchId: String,
    ): Boolean =
        Regex(
            """(?<!\p{L}|\p{N})""" +
                Regex.escape(branchId) +
                """(?!\p{L}|\p{N})""",
        ).containsMatchIn(text)

    private fun containsNormalizedPhrase(
        normalizedText: String,
        normalizedPhrase: String,
    ): Boolean =
        (" $normalizedText ").contains(" $normalizedPhrase ")

    private data class BranchMatch(
        val branch: ProviderBranch,
        val score: Int,
    )

    private val LOCATION_INTENT_MARKERS = setOf(
        "obi",
        "kwant",
        "market",
        "markecie",
        "sklep",
        "sklepie",
        "oddzial",
        "oddziale",
        "hurtownia",
        "hurtowni",
        "magazyn",
        "magazynie",
    )

    private val LOCATION_CONNECTORS = setOf(
        "w",
        "we",
        "na",
        "ul",
        "ulica",
        "al",
        "aleja",
    )

    private val STREET_PREFIXES = setOf(
        "ul",
        "ulica",
        "al",
        "aleja",
    )

    private val ADDRESS_NOISE = setOf(
        "centrum",
        "ch",
    )

    private val CURRENT_BRANCH_PHRASES = setOf(
        "u nas",
        "na naszym magazynie",
        "w naszym magazynie",
        "na naszym oddziale",
        "w naszym oddziale",
        "w naszym sklepie",
        "w naszym markecie",
    )

    private val CURRENT_BRANCH_NAME_ALIASES = mapOf(
        "nowy sacz" to setOf(
            "w nowym saczu",
            "w nowym sacz",
            "nowym saczu",
            "nowym sacz",
            "w saczu",
            "w sacz",
            "saczu",
            "sacz",
        ),
    )

    private val PROVIDER_LOCATION_INTENT = Regex(
        """\b(?:obi|kwant)\b(?:\s+(?:w|we|na))?\s+[\p{L}]{3,}""" +
            """|\b(?:w|we|na)\s+(?:obi|kwant)\b""",
    )

    private val BRANCH_NOUN_LOCATION_INTENT = Regex(
        """\b(?:w|we|na)\s+(?:markecie|sklepie|oddziale|hurtowni|""" +
            """magazynie)\b""" +
            """|\b(?:market|sklep|oddzial|hurtownia|magazyn)""" +
            """\s+(?:w|we|na\s+)?[\p{L}]{3,}""",
    )
}
