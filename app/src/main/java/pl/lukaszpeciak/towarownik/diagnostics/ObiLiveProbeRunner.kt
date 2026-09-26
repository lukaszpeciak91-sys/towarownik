package pl.lukaszpeciak.towarownik.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal class ObiLiveProbeRunner(
    private val baseUrl: HttpUrl = "https://www.obi.pl/".toHttpUrl(),
    private val nativeUserAgent: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun run(): ObiLiveProbeReport = withContext(ioDispatcher) {
        val urls = ObiProbeUrls(baseUrl)
        val sections = mutableListOf<ObiProbeSection>()

        val endpointSteps = listOf(
            ObiProbeSession(baseUrl, ObiProbeProfile.A_BASELINE, nativeUserAgent)
                .get("root", urls.root(), ObiProbeBodyKind.GENERIC),
            ObiProbeSession(baseUrl, ObiProbeProfile.A_BASELINE, nativeUserAgent)
                .get("search-dedra", urls.search(), ObiProbeBodyKind.SEARCH),
            ObiProbeSession(baseUrl, ObiProbeProfile.A_BASELINE, nativeUserAgent)
                .get("product-bare", urls.bareProduct(), ObiProbeBodyKind.PRODUCT),
            ObiProbeSession(baseUrl, ObiProbeProfile.A_BASELINE, nativeUserAgent)
                .get("product-canonical", urls.canonicalProduct(), ObiProbeBodyKind.PRODUCT),
            ObiProbeSession(baseUrl, ObiProbeProfile.A_BASELINE, nativeUserAgent)
                .get(
                    "store-change-bare",
                    urls.storeChange(ObiProbeUrls.BARE_PRODUCT_REDIRECT),
                    ObiProbeBodyKind.PRODUCT,
                ),
        )
        sections += ObiProbeSection("endpoint-baseline", endpointSteps)

        ObiProbeProfile.entries.forEach { profile ->
            val step = ObiProbeSession(baseUrl, profile, nativeUserAgent)
                .get("search-dedra", urls.search(), ObiProbeBodyKind.SEARCH)
            sections += ObiProbeSection("profile-${profile.reportName}", listOf(step))
        }

        val sessionSequences = listOf(
            runSessionSequence(
                urls = urls,
                profile = ObiProbeProfile.A_BASELINE,
                bootstrap = ProbeBootstrap.NONE,
                sectionName = "session-S0-baseline",
            ),
            runSessionSequence(
                urls = urls,
                profile = ObiProbeProfile.A_BASELINE,
                bootstrap = ProbeBootstrap.ROOT,
                sectionName = "session-S1-baseline",
            ),
            runSessionSequence(
                urls = urls,
                profile = ObiProbeProfile.A_BASELINE,
                bootstrap = ProbeBootstrap.CANONICAL_PRODUCT,
                sectionName = "session-S2-baseline",
            ),
            runSessionSequence(
                urls = urls,
                profile = ObiProbeProfile.E_COMBINED_HTML,
                bootstrap = ProbeBootstrap.NONE,
                sectionName = "session-S0-profile-E",
            ),
            runSessionSequence(
                urls = urls,
                profile = ObiProbeProfile.E_COMBINED_HTML,
                bootstrap = ProbeBootstrap.ROOT,
                sectionName = "session-S1-profile-E",
            ),
            runSessionSequence(
                urls = urls,
                profile = ObiProbeProfile.E_COMBINED_HTML,
                bootstrap = ProbeBootstrap.CANONICAL_PRODUCT,
                sectionName = "session-S2-profile-E",
            ),
            runSessionSequence(
                urls = urls,
                profile = ObiProbeProfile.G_BROWSER_HTML,
                bootstrap = ProbeBootstrap.NONE,
                sectionName = "session-S0-profile-G",
            ),
            runSessionSequence(
                urls = urls,
                profile = ObiProbeProfile.G_BROWSER_HTML,
                bootstrap = ProbeBootstrap.ROOT,
                sectionName = "session-S1-profile-G",
            ),
            runSessionSequence(
                urls = urls,
                profile = ObiProbeProfile.G_BROWSER_HTML,
                bootstrap = ProbeBootstrap.CANONICAL_PRODUCT,
                sectionName = "session-S2-profile-G",
            ),
        )
        sessionSequences.forEach { sections += it.section }

        val storeOpportunity = sessionSequences
            .firstOrNull { sequence ->
                sequence.storeStep.hops.firstOrNull()?.status?.let { it != 404 } == true
            }

        if (storeOpportunity != null) {
            val targetSteps = mutableListOf<ObiProbeStep>()
            targetSteps += runStoreTargetCase(
                urls = urls,
                profile = storeOpportunity.profile,
                bootstrap = storeOpportunity.bootstrap,
                redirectPath = ObiProbeUrls.BARE_PRODUCT_REDIRECT,
                label = "redirect-target-bare",
            )
            targetSteps += runStoreTargetCase(
                urls = urls,
                profile = storeOpportunity.profile,
                bootstrap = storeOpportunity.bootstrap,
                redirectPath = ObiProbeUrls.CANONICAL_PRODUCT_REDIRECT,
                label = "redirect-target-canonical",
            )
            sections += ObiProbeSection(
                "store-redirect-target-control-profile-${storeOpportunity.profile.reportName}-bootstrap-${storeOpportunity.bootstrap.name.lowercase()}",
                targetSteps,
            )
        }

        ObiLiveProbeReport(sections = sections)
    }

    private fun runSessionSequence(
        urls: ObiProbeUrls,
        profile: ObiProbeProfile,
        bootstrap: ProbeBootstrap,
        sectionName: String,
    ): SessionSequenceResult {
        val session = ObiProbeSession(baseUrl, profile, nativeUserAgent)
        val steps = mutableListOf<ObiProbeStep>()

        when (bootstrap) {
            ProbeBootstrap.NONE -> Unit
            ProbeBootstrap.ROOT -> {
                steps += session.get(
                    "bootstrap-root",
                    urls.root(),
                    ObiProbeBodyKind.GENERIC,
                )
            }
            ProbeBootstrap.CANONICAL_PRODUCT -> {
                steps += session.get(
                    "bootstrap-canonical-product",
                    urls.canonicalProduct(),
                    ObiProbeBodyKind.PRODUCT,
                )
            }
        }

        val storeStep = session.get(
            "store-change",
            urls.storeChange(ObiProbeUrls.BARE_PRODUCT_REDIRECT),
            ObiProbeBodyKind.PRODUCT,
        )
        steps += storeStep

        return SessionSequenceResult(
            section = ObiProbeSection(sectionName, steps),
            storeStep = storeStep,
            profile = profile,
            bootstrap = bootstrap,
        )
    }

    private fun runStoreTargetCase(
        urls: ObiProbeUrls,
        profile: ObiProbeProfile,
        bootstrap: ProbeBootstrap,
        redirectPath: String,
        label: String,
    ): ObiProbeStep {
        val session = ObiProbeSession(baseUrl, profile, nativeUserAgent)
        when (bootstrap) {
            ProbeBootstrap.NONE -> Unit
            ProbeBootstrap.ROOT -> session.get(
                "bootstrap-root",
                urls.root(),
                ObiProbeBodyKind.GENERIC,
            )
            ProbeBootstrap.CANONICAL_PRODUCT -> session.get(
                "bootstrap-canonical-product",
                urls.canonicalProduct(),
                ObiProbeBodyKind.PRODUCT,
            )
        }
        return session.get(
            label,
            urls.storeChange(redirectPath),
            ObiProbeBodyKind.PRODUCT,
        )
    }

    private data class SessionSequenceResult(
        val section: ObiProbeSection,
        val storeStep: ObiProbeStep,
        val profile: ObiProbeProfile,
        val bootstrap: ProbeBootstrap,
    )

    private enum class ProbeBootstrap {
        NONE,
        ROOT,
        CANONICAL_PRODUCT,
    }
}

internal class ObiProbeUrls(
    private val baseUrl: HttpUrl,
) {
    fun root(): HttpUrl =
        baseUrl.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
            .build()

    fun search(): HttpUrl =
        baseUrl.newBuilder()
            .encodedPath("/search/$OBI_PROBE_CONTROL_SEARCH/")
            .query(null)
            .fragment(null)
            .build()

    fun bareProduct(): HttpUrl =
        baseUrl.newBuilder()
            .encodedPath(BARE_PRODUCT_REDIRECT)
            .query(null)
            .fragment(null)
            .build()

    fun canonicalProduct(): HttpUrl =
        baseUrl.newBuilder()
            .encodedPath(CANONICAL_PRODUCT_REDIRECT)
            .query(null)
            .fragment(null)
            .build()

    fun storeChange(redirectPath: String): HttpUrl =
        baseUrl.newBuilder()
            .encodedPath("/api/disc/store/change")
            .query(null)
            .fragment(null)
            .addQueryParameter("storeNumber", "075")
            .addQueryParameter("redirectUrl", redirectPath)
            .build()

    companion object {
        const val BARE_PRODUCT_REDIRECT = "/p/3496072"
        const val CANONICAL_PRODUCT_REDIRECT =
            "/p/3496072/dragon-klej-uniwersalny-butapren-50-ml"
    }
}

internal class ObiProbeSession(
    baseUrl: HttpUrl,
    private val profile: ObiProbeProfile,
    private val nativeUserAgent: String,
) {
    private val traceInterceptor = ProbeTraceInterceptor()
    private val client = OkHttpClient.Builder()
        .cookieJar(ProbeCookieJar())
        .followRedirects(true)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(traceInterceptor)
        .build()

    init {
        require(baseUrl.scheme == "http" || baseUrl.scheme == "https")
    }

    fun get(
        label: String,
        url: HttpUrl,
        bodyKind: ObiProbeBodyKind,
    ): ObiProbeStep {
        traceInterceptor.begin()
        val requestBuilder = Request.Builder().url(url).get()
        profile.applyHeaders(requestBuilder, nativeUserAgent)
        val request = requestBuilder.build()
        val startedNanos = System.nanoTime()

        return try {
            client.newCall(request).execute().use { response ->
                val previewBytes = runCatching {
                    response.peekBody(BODY_PREVIEW_PROBE_BYTES).bytes()
                }.getOrDefault(ByteArray(0))
                val previewTruncated = when {
                    previewBytes.size > BODY_PREVIEW_LIMIT_BYTES -> true
                    previewBytes.size < BODY_PREVIEW_LIMIT_BYTES -> false
                    else -> null
                }
                val previewText = previewBytes
                    .copyOfRange(
                        0,
                        minOf(previewBytes.size, BODY_PREVIEW_LIMIT_BYTES),
                    )
                    .toString(Charsets.UTF_8)
                val signatures = when (bodyKind) {
                    ObiProbeBodyKind.GENERIC -> genericBodySignatures(previewText)
                    ObiProbeBodyKind.SEARCH -> searchBodySignatures(previewText)
                    ObiProbeBodyKind.PRODUCT -> productBodySignatures(
                        previewText,
                        OBI_PROBE_CONTROL_OBIK,
                    )
                }
                val bodyPreview = ObiProbeBodyPreview(
                    previewUtf8Bytes = previewText.toByteArray(Charsets.UTF_8).size,
                    previewLimitBytes = BODY_PREVIEW_LIMIT_BYTES,
                    previewTruncated = previewTruncated,
                    signatures = signatures,
                )

                ObiProbeStep(
                    label = label,
                    profile = profile,
                    requestedUrl = sanitizeDiagnosticUrl(url.toString()) ?: "[redacted-url]",
                    durationMillis = elapsedMillis(startedNanos),
                    hops = traceInterceptor.end(),
                    finalStatus = response.code,
                    finalUrl = sanitizeDiagnosticUrl(response.request.url.toString()),
                    bodyPreview = bodyPreview,
                    errorType = null,
                )
            }
        } catch (exception: Exception) {
            ObiProbeStep(
                label = label,
                profile = profile,
                requestedUrl = sanitizeDiagnosticUrl(url.toString()) ?: "[redacted-url]",
                durationMillis = elapsedMillis(startedNanos),
                hops = traceInterceptor.end(),
                finalStatus = null,
                finalUrl = null,
                bodyPreview = null,
                errorType = exception.javaClass.simpleName,
            )
        }
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    private companion object {
        const val BODY_PREVIEW_LIMIT_BYTES = 64 * 1024
        const val BODY_PREVIEW_PROBE_BYTES = BODY_PREVIEW_LIMIT_BYTES.toLong() + 1L
    }
}

internal fun ObiProbeProfile.applyHeaders(
    builder: Request.Builder,
    nativeUserAgent: String,
) {
    when (this) {
        ObiProbeProfile.A_BASELINE -> Unit
        ObiProbeProfile.B_UA_ONLY -> builder.header("User-Agent", nativeUserAgent)
        ObiProbeProfile.C_ACCEPT_ONLY -> builder.header("Accept", HTML_ACCEPT)
        ObiProbeProfile.D_LANGUAGE_ONLY -> builder.header("Accept-Language", POLISH_ACCEPT_LANGUAGE)
        ObiProbeProfile.E_COMBINED_HTML -> {
            builder.header("User-Agent", nativeUserAgent)
            builder.header("Accept", HTML_ACCEPT)
            builder.header("Accept-Language", POLISH_ACCEPT_LANGUAGE)
        }
        ObiProbeProfile.F_BROWSER_UA_ONLY -> {
            builder.header("User-Agent", SYNTHETIC_BROWSER_USER_AGENT)
        }
        ObiProbeProfile.G_BROWSER_HTML -> {
            builder.header("User-Agent", SYNTHETIC_BROWSER_USER_AGENT)
            builder.header("Accept", HTML_ACCEPT)
            builder.header("Accept-Language", POLISH_ACCEPT_LANGUAGE)
        }
    }
}

private class ProbeTraceInterceptor : Interceptor {
    private var hops: MutableList<DiagnosticHttpHop>? = null

    fun begin() {
        hops = mutableListOf()
    }

    fun end(): List<DiagnosticHttpHop> =
        hops.orEmpty().toList().also { hops = null }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val outgoingEvidence = outgoingDiagnosticCookieEvidence(request.header("Cookie"))
        val response = chain.proceed(request)
        val setCookieEvidence = setDiagnosticCookieEvidence(
            requestUrl = request.url,
            responseHeaders = response.headers,
        )

        hops?.add(
            DiagnosticHttpHop(
                number = (hops?.size ?: 0) + 1,
                status = response.code,
                url = sanitizeDiagnosticUrl(request.url.toString()) ?: "[redacted-url]",
                location = sanitizeDiagnosticUrl(response.header("Location")),
                protocol = diagnosticProtocolLabel(response.protocol),
                safeRequestHeaders = safeDiagnosticRequestHeaders(request),
                contentType = response.header("Content-Type"),
                contentEncoding = response.header("Content-Encoding"),
                declaredContentLength = response.header("Content-Length")?.toLongOrNull(),
                safeInfrastructureHeaders = safeDiagnosticInfrastructureHeaders(response),
                outgoingCookies = outgoingEvidence.cookies,
                setCookies = setCookieEvidence.cookies,
                outgoingStore075CookieMatch = outgoingEvidence.store075Match,
                setCookieStore075Match = setCookieEvidence.store075Match,
            ),
        )
        return response
    }
}

private class ProbeCookieJar : CookieJar {
    private val cookies = ConcurrentHashMap<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            this.cookies["${cookie.domain}|${cookie.path}|${cookie.name}"] = cookie
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.values.filter { it.matches(url) }
}

private const val SYNTHETIC_BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

private const val HTML_ACCEPT =
    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
private const val POLISH_ACCEPT_LANGUAGE = "pl-PL,pl;q=0.9"
