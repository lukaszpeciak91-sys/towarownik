package pl.lukaszpeciak.towarownik.diagnostics

internal enum class ObiProbeProfile(val reportName: String) {
    A_BASELINE("A"),
    B_UA_ONLY("B"),
    C_ACCEPT_ONLY("C"),
    D_LANGUAGE_ONLY("D"),
    E_COMBINED_HTML("E"),
    F_BROWSER_UA_ONLY("F"),
    G_BROWSER_HTML("G"),
}

internal enum class ObiProbeBodyKind {
    GENERIC,
    SEARCH,
    PRODUCT,
}

internal data class ObiProbeBodyPreview(
    val previewUtf8Bytes: Int,
    val previewLimitBytes: Int,
    val previewTruncated: Boolean?,
    val signatures: DiagnosticBodySignatures,
)

internal data class ObiProbeStep(
    val label: String,
    val profile: ObiProbeProfile,
    val requestedUrl: String,
    val durationMillis: Long,
    val hops: List<DiagnosticHttpHop>,
    val finalStatus: Int?,
    val finalUrl: String?,
    val bodyPreview: ObiProbeBodyPreview?,
    val errorType: String?,
)

internal data class ObiProbeSection(
    val name: String,
    val steps: List<ObiProbeStep>,
)

internal data class ObiLiveProbeReport(
    val probeVersion: Int = 1,
    val controlSearch: String = OBI_PROBE_CONTROL_SEARCH,
    val controlObik: String = OBI_PROBE_CONTROL_OBIK,
    val sections: List<ObiProbeSection>,
) {
    fun render(): String = buildString {
        appendLine("=== OBI LIVE PROBE ===")
        appendLine("probeVersion=$probeVersion")
        appendLine("controlSearch=$controlSearch")
        appendLine("controlObik=$controlObik")
        appendLine()

        sections.forEach { section ->
            appendLine("[${section.name}]")
            section.steps.forEach { step ->
                appendLine("step=${step.label}")
                appendLine("profile=${step.profile.reportName}")
                appendLine("request=${step.requestedUrl}")
                appendLine("durationMs=${step.durationMillis}")
                appendLine("finalStatus=${step.finalStatus ?: -1}")
                appendLine("finalUrl=${step.finalUrl ?: "(none)"}")
                step.errorType?.let { appendLine("error=$it") }

                step.hops.forEach { hop ->
                    append("hop=${hop.number} status=${hop.status} url=${hop.url}")
                    hop.location?.let { append(" location=$it") }
                    appendLine()
                    appendLine("  protocol=${hop.protocol ?: "(none)"}")
                    appendLine(
                        "  requestHeaders=" +
                            hop.safeRequestHeaders.entries.joinToString { "${it.key}=${it.value}" }
                                .ifBlank { "(none)" },
                    )
                    appendLine("  outgoingCookieNames=${hop.outgoingCookies.probeCookieNames()}")
                    appendLine("  outgoingStore075CookieMatch=${hop.outgoingStore075CookieMatch.reportValue}")
                    appendLine("  setCookieNames=${hop.setCookies.probeCookieNames()}")
                    appendLine("  setCookieStore075Match=${hop.setCookieStore075Match.reportValue}")
                    appendLine("  contentType=${hop.contentType ?: "(none)"}")
                    appendLine("  contentEncoding=${hop.contentEncoding ?: "(none)"}")
                    appendLine("  declaredContentLength=${hop.declaredContentLength ?: -1}")
                    appendLine(
                        "  infraHeaders=" +
                            hop.safeInfrastructureHeaders.entries.joinToString { "${it.key}=${it.value}" }
                                .ifBlank { "(none)" },
                    )
                }

                step.bodyPreview?.let { preview ->
                    val body = preview.signatures
                    appendLine("body.previewUtf8Bytes=${preview.previewUtf8Bytes}")
                    appendLine("body.previewLimitBytes=${preview.previewLimitBytes}")
                    appendLine(
                        "body.previewTruncated=" +
                            (preview.previewTruncated?.toString() ?: "unknown"),
                    )
                    appendLine("body.looksLikeHtml=${body.looksLikeHtml}")
                    appendLine("body.title=${body.title ?: "(none)"}")
                    appendLine("body.accessDeniedOrChallenge=${body.accessDeniedOrChallenge}")
                    appendLine("body.challengeIndicators=${body.challengeIndicators.joinToString().ifBlank { "(none)" }}")
                    appendLine("body.containsNuxtData=${body.containsNuxtData}")
                    body.containsRequestedObik?.let { appendLine("body.containsRequestedObik=$it") }
                    body.canonicalUrl?.let { appendLine("body.canonicalUrl=$it") }
                    body.containsSearchResultsPhrase?.let { appendLine("body.containsSearchResultsPhrase=$it") }
                    body.detectedSearchResultCount?.let { appendLine("body.detectedSearchResultCount=$it") }
                    body.zeroResultPhraseNoResultsFound?.let { appendLine("body.zeroResult.noResultsFound=$it") }
                    body.zeroResultPhraseProductsNotFound?.let { appendLine("body.zeroResult.productsNotFound=$it") }
                    body.zeroResultPhraseNoResults?.let { appendLine("body.zeroResult.noResults=$it") }
                    body.containsZeroCountToken?.let { appendLine("body.containsZeroCountToken=$it") }
                    appendLine("body.recognizedProductLinkCount=${body.recognizedProductLinkCount}")
                }
                appendLine()
            }
        }
    }
}

internal fun List<DiagnosticCookie>.probeCookieNames(): String =
    distinctBy { Triple(it.name, it.domain, it.path) }
        .joinToString { cookie ->
            buildString {
                append(cookie.name)
                cookie.domain?.let { append("@").append(it) }
                cookie.path?.let { append(it) }
            }
        }
        .ifBlank { "(none)" }

internal const val OBI_PROBE_CONTROL_SEARCH = "dedra"
internal const val OBI_PROBE_CONTROL_OBIK = "3496072"
internal const val OBI_PROBE_CANONICAL_PRODUCT_URL =
    "https://www.obi.pl/p/3496072/dragon-klej-uniwersalny-butapren-50-ml"
internal const val OBI_PROBE_SEARCH_URL = "https://www.obi.pl/search/dedra/"
