package pl.lukaszpeciak.towarownik.diagnostics

import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

object ObiDiagnostics {
    val recorder = ObiDiagnosticRecorder()
}

class ObiDiagnosticRecorder(
    private val maxHistory: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val nextId = AtomicLong(1)
    private val active = linkedMapOf<Long, MutableOperation>()
    private val history = ArrayDeque<DiagnosticOperationSnapshot>()

    @Volatile
    private var enabled = false

    @Volatile
    private var deviceContext: DiagnosticDeviceContext? = null

    init {
        require(maxHistory > 0)
    }

    fun configureDeviceContext(context: DiagnosticDeviceContext) {
        deviceContext = context
    }

    fun isEnabled(): Boolean = enabled

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    @Synchronized
    fun clear() {
        active.clear()
        history.clear()
    }

    @Synchronized
    fun startOperation(
        operation: ObiDiagnosticOperationType,
        inputType: ObiDiagnosticInputType,
        identifier: String,
        requestedUrl: String,
        requestMethod: String,
    ): Long? {
        if (!enabled) return null

        val id = nextId.getAndIncrement()
        active[id] = MutableOperation(
            id = id,
            operation = operation,
            inputType = inputType,
            identifier = identifier,
            timestampMillis = clock(),
            requestedUrl = sanitizeDiagnosticUrl(requestedUrl) ?: "[redacted-url]",
            requestMethod = requestMethod,
        )
        return id
    }

    @Synchronized
    fun recordNetworkRequest(
        id: Long?,
        userAgent: String?,
        accept: String?,
        acceptLanguage: String?,
        outgoingCookies: List<DiagnosticCookie>,
    ) {
        val operation = id?.let(active::get) ?: return
        if (operation.userAgent == null) operation.userAgent = userAgent
        if (operation.accept == null) operation.accept = accept
        if (operation.acceptLanguage == null) operation.acceptLanguage = acceptLanguage
        operation.outgoingCookies.addAll(outgoingCookies)
    }

    @Synchronized
    fun recordHttpHop(
        id: Long?,
        status: Int,
        url: String,
        location: String?,
        contentType: String?,
        contentEncoding: String?,
        declaredContentLength: Long?,
        safeInfrastructureHeaders: Map<String, String>,
        outgoingCookies: List<DiagnosticCookie>,
        setCookies: List<DiagnosticCookie>,
        outgoingStore075CookieMatch: Store075CookieMatch,
        setCookieStore075Match: Store075CookieMatch,
    ) {
        val operation = id?.let(active::get) ?: return
        val sanitizedUrl = sanitizeDiagnosticUrl(url) ?: "[redacted-url]"
        val sanitizedLocation = sanitizeDiagnosticUrl(location)

        if (operation.hops.size < MAX_HOPS) {
            operation.hops += DiagnosticHttpHop(
                number = operation.hops.size + 1,
                status = status,
                url = sanitizedUrl,
                location = sanitizedLocation,
                contentType = contentType,
                contentEncoding = contentEncoding,
                declaredContentLength = declaredContentLength,
                safeInfrastructureHeaders = safeInfrastructureHeaders,
                outgoingCookies = outgoingCookies.distinct(),
                setCookies = setCookies.distinct(),
                outgoingStore075CookieMatch = outgoingStore075CookieMatch,
                setCookieStore075Match = setCookieStore075Match,
            )
        }
        operation.setCookies.addAll(setCookies)
        operation.finalStatus = status
        operation.finalUrl = sanitizedUrl
    }

    @Synchronized
    fun recordDuration(id: Long?, durationMillis: Long) {
        id?.let(active::get)?.durationMillis = durationMillis
    }

    @Synchronized
    fun recordBodySignatures(id: Long?, signatures: DiagnosticBodySignatures) {
        id?.let(active::get)?.bodySignatures = signatures.copy(
            canonicalUrl = sanitizeDiagnosticUrl(signatures.canonicalUrl),
        )
    }

    @Synchronized
    fun parserStage(id: Long?, stage: String) {
        id?.let(active::get)?.parserStages?.add(stage)
    }

    @Synchronized
    fun mappingTrace(id: Long?, stage: String) {
        id?.let(active::get)?.errorMappingTrace?.add(stage)
    }

    @Synchronized
    fun finish(id: Long?) {
        if (id == null) return
        val operation = active.remove(id) ?: return
        history.addLast(operation.snapshot())
        while (history.size > maxHistory) {
            history.removeFirst()
        }
    }

    @Synchronized
    fun snapshots(): List<DiagnosticOperationSnapshot> = history.toList()

    @Synchronized
    fun report(): String {
        val records = history.toList() + active.values.map(MutableOperation::snapshot)
        return renderReport(records)
    }

    private fun renderReport(records: List<DiagnosticOperationSnapshot>): String = buildString {
        appendLine("Towarownik OBI diagnostics")
        appendLine("diagnosticMode=${if (enabled) "ON" else "OFF"}")
        deviceContext?.let { context ->
            appendLine("app=${context.versionName} (${context.versionCode})")
            appendLine("android=${context.androidVersion} API ${context.apiLevel}")
            appendLine("device=${context.manufacturer} ${context.model}")
        } ?: appendLine("deviceContext=unavailable")
        appendLine("operations=${records.size}/$maxHistory")
        appendLine()

        if (records.isEmpty()) {
            appendLine("No captured OBI operations.")
            return@buildString
        }

        records.forEachIndexed { index, record ->
            appendLine("--- operation ${index + 1} ---")
            appendLine("id=${record.id}")
            appendLine("timestamp=${Instant.ofEpochMilli(record.timestampMillis)}")
            appendLine("operation=${record.operation}")
            appendLine("inputType=${record.inputType}")
            appendLine("identifier=${record.identifier}")
            appendLine("request=${record.requestMethod} ${record.requestedUrl}")
            appendLine("userAgent=${record.userAgent ?: "(none)"}")
            appendLine("accept=${record.accept ?: "(none)"}")
            appendLine("acceptLanguage=${record.acceptLanguage ?: "(none)"}")
            appendLine("durationMs=${record.durationMillis ?: -1}")
            appendLine("outgoingCookieNames=${record.outgoingCookies.safeCookieList()}")
            appendLine("setCookieNames=${record.setCookies.safeCookieList()}")
            appendLine("store075CookieMatch=${record.store075CookieMatch.reportValue}")

            appendLine("redirectChain:")
            if (record.hops.isEmpty()) {
                appendLine("  (none captured)")
            } else {
                record.hops.forEach { hop ->
                    append("  hop=${hop.number} status=${hop.status} url=${hop.url}")
                    hop.location?.let { append(" location=$it") }
                    appendLine()
                    appendLine("    outgoingCookieNames=${hop.outgoingCookies.safeCookieList()}")
                    appendLine(
                        "    outgoingStore075CookieMatch=" +
                            hop.outgoingStore075CookieMatch.reportValue,
                    )
                    appendLine("    setCookieNames=${hop.setCookies.safeCookieList()}")
                    appendLine(
                        "    setCookieStore075Match=" +
                            hop.setCookieStore075Match.reportValue,
                    )
                    appendLine("    contentType=${hop.contentType ?: "(none)"}")
                    appendLine("    contentEncoding=${hop.contentEncoding ?: "(none)"}")
                    appendLine("    declaredContentLength=${hop.declaredContentLength ?: -1}")
                    if (hop.safeInfrastructureHeaders.isNotEmpty()) {
                        appendLine(
                            "    infraHeaders=" +
                                hop.safeInfrastructureHeaders.entries.joinToString { "${it.key}=${it.value}" },
                        )
                    }
                }
            }
            appendLine("finalStatus=${record.finalStatus ?: -1}")
            appendLine("finalUrl=${record.finalUrl ?: "(none)"}")

            record.bodySignatures?.let { body ->
                appendLine("body.decodedBodyUtf8Bytes=${body.decodedBodyUtf8Bytes}")
                appendLine("body.looksLikeHtml=${body.looksLikeHtml}")
                appendLine("body.title=${body.title ?: "(none)"}")
                appendLine("body.accessDeniedOrChallenge=${body.accessDeniedOrChallenge}")
                appendLine("body.challengeIndicators=${body.challengeIndicators.joinToString().ifBlank { "(none)" }}")
                appendLine("body.containsNuxtData=${body.containsNuxtData}")
                body.containsRequestedObik?.let { appendLine("body.containsRequestedObik=$it") }
                body.containsCanonicalProductUrl?.let { appendLine("body.containsCanonicalProductUrl=$it") }
                body.canonicalUrl?.let { appendLine("body.canonicalUrl=$it") }
                body.containsSelectedStore?.let { appendLine("body.containsSelectedStore=$it") }
                body.containsStore075?.let { appendLine("body.containsStore075=$it") }
                appendLine("body.recognizedProductLinkCount=${body.recognizedProductLinkCount}")
                body.containsSearchResultsPhrase?.let { appendLine("body.containsSearchResultsPhrase=$it") }
                body.detectedSearchResultCount?.let { appendLine("body.detectedSearchResultCount=$it") }
                body.zeroResultPhraseNoResultsFound?.let { appendLine("body.zeroResult.noResultsFound=$it") }
                body.zeroResultPhraseProductsNotFound?.let { appendLine("body.zeroResult.productsNotFound=$it") }
                body.zeroResultPhraseNoResults?.let { appendLine("body.zeroResult.noResults=$it") }
                body.containsZeroCountToken?.let { appendLine("body.containsZeroCountToken=$it") }
            }

            appendLine("parserStages:")
            if (record.parserStages.isEmpty()) {
                appendLine("  (none)")
            } else {
                record.parserStages.forEach { appendLine("  $it") }
            }

            appendLine("errorMappingTrace:")
            if (record.errorMappingTrace.isEmpty()) {
                appendLine("  (none)")
            } else {
                record.errorMappingTrace.forEach { appendLine("  $it") }
            }
            appendLine()
        }
    }

    private fun List<DiagnosticCookie>.safeCookieList(): String =
        distinctBy { Triple(it.name, it.domain, it.path) }
            .joinToString { cookie ->
                buildString {
                    append(cookie.name)
                    cookie.domain?.let { append("@").append(it) }
                    cookie.path?.let { append(it) }
                }
            }
            .ifBlank { "(none)" }

    private inner class MutableOperation(
        val id: Long,
        val operation: ObiDiagnosticOperationType,
        val inputType: ObiDiagnosticInputType,
        val identifier: String,
        val timestampMillis: Long,
        val requestedUrl: String,
        val requestMethod: String,
        var userAgent: String? = null,
        var accept: String? = null,
        var acceptLanguage: String? = null,
        var durationMillis: Long? = null,
        val outgoingCookies: MutableList<DiagnosticCookie> = mutableListOf(),
        val setCookies: MutableList<DiagnosticCookie> = mutableListOf(),
        val hops: MutableList<DiagnosticHttpHop> = mutableListOf(),
        var finalStatus: Int? = null,
        var finalUrl: String? = null,
        var bodySignatures: DiagnosticBodySignatures? = null,
        val parserStages: MutableList<String> = mutableListOf(),
        val errorMappingTrace: MutableList<String> = mutableListOf(),
    ) {
        fun snapshot(): DiagnosticOperationSnapshot =
            DiagnosticOperationSnapshot(
                id = id,
                operation = operation,
                inputType = inputType,
                identifier = identifier,
                timestampMillis = timestampMillis,
                requestedUrl = requestedUrl,
                requestMethod = requestMethod,
                userAgent = userAgent,
                accept = accept,
                acceptLanguage = acceptLanguage,
                durationMillis = durationMillis,
                outgoingCookies = outgoingCookies.distinct(),
                setCookies = setCookies.distinct(),
                store075CookieMatch = hops.lastOrNull()?.outgoingStore075CookieMatch
                    ?: Store075CookieMatch.UNKNOWN,
                hops = hops.toList(),
                finalStatus = finalStatus,
                finalUrl = finalUrl,
                bodySignatures = bodySignatures,
                parserStages = parserStages.toList(),
                errorMappingTrace = errorMappingTrace.toList(),
            )
    }

    private companion object {
        const val MAX_HOPS = 10
    }
}
