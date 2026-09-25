package pl.lukaszpeciak.towarownik.product

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.lukaszpeciak.towarownik.diagnostics.DiagnosticBodySignatures
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticInputType
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticNetworkInterceptor
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticOperationType
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticRecorder
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticRequestTag
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnostics
import pl.lukaszpeciak.towarownik.diagnostics.productBodySignatures
import pl.lukaszpeciak.towarownik.diagnostics.searchBodySignatures

enum class ObiHttpFailureKind {
    TRANSPORT,
    NOT_FOUND,
    SERVER,
    DATA,
}

sealed interface ObiHttpResult {
    data class Success(
        val html: String,
        internal val diagnosticId: Long? = null,
    ) : ObiHttpResult

    data class Failure(
        internal val kind: ObiHttpFailureKind,
        val reason: String,
        internal val diagnosticId: Long? = null,
    ) : ObiHttpResult
}

class ObiHttpClient(
    private val baseUrl: HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host("www.obi.pl")
        .build(),
    client: OkHttpClient? = null,
    private val diagnostics: ObiDiagnosticRecorder = ObiDiagnostics.recorder,
) {
    private val client = (client ?: OkHttpClient.Builder()
        .cookieJar(SessionCookieJar())
        .followRedirects(true)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build())
        .newBuilder()
        .addNetworkInterceptor(ObiDiagnosticNetworkInterceptor(diagnostics))
        .build()

    fun fetchProduct(obik: String, storeNumber: String): ObiHttpResult {
        val selectionUrl = baseUrl.newBuilder()
            .addPathSegments("api/disc/store/change")
            .addQueryParameter("storeNumber", storeNumber)
            .addQueryParameter("redirectUrl", "/p/$obik")
            .build()
        val diagnosticId = diagnostics.startOperation(
            operation = ObiDiagnosticOperationType.PRODUCT_LOOKUP,
            inputType = ObiDiagnosticInputType.OBIK,
            identifier = obik,
            requestedUrl = selectionUrl.toString(),
            requestMethod = "GET",
        )
        return execute(
            request = taggedGet(selectionUrl, diagnosticId),
            emptyReason = "OBI returned an empty product page",
            diagnosticId = diagnosticId,
            bodySignatures = { html -> productBodySignatures(html, obik) },
        )
    }

    fun fetchSearch(
        query: String,
        inputType: ObiDiagnosticInputType = ObiDiagnosticInputType.UNKNOWN,
    ): ObiHttpResult {
        val searchUrl = baseUrl.newBuilder()
            .addPathSegment("search")
            .addPathSegment(query)
            .addPathSegment("")
            .build()
        val diagnosticId = diagnostics.startOperation(
            operation = ObiDiagnosticOperationType.SEARCH,
            inputType = inputType,
            identifier = query,
            requestedUrl = searchUrl.toString(),
            requestMethod = "GET",
        )
        return execute(
            request = taggedGet(searchUrl, diagnosticId),
            emptyReason = "OBI returned an empty search page",
            diagnosticId = diagnosticId,
            bodySignatures = ::searchBodySignatures,
        )
    }

    private fun taggedGet(url: HttpUrl, diagnosticId: Long?): Request {
        val builder = Request.Builder().url(url).get()
        if (diagnosticId != null) {
            builder.tag(
                ObiDiagnosticRequestTag::class.java,
                ObiDiagnosticRequestTag(diagnosticId),
            )
        }
        return builder.build()
    }

    private fun execute(
        request: Request,
        emptyReason: String,
        diagnosticId: Long?,
        bodySignatures: (String) -> DiagnosticBodySignatures,
    ): ObiHttpResult {
        val startedNanos = System.nanoTime()
        return try {
            client.newCall(request).execute().use { response ->
                diagnostics.mappingTrace(diagnosticId, "HTTP ${response.code}")
                if (!response.isSuccessful) {
                    val kind = if (response.code == 404) {
                        ObiHttpFailureKind.NOT_FOUND
                    } else {
                        ObiHttpFailureKind.SERVER
                    }
                    diagnostics.mappingTrace(diagnosticId, "ObiHttpFailureKind.$kind")
                    ObiHttpResult.Failure(
                        kind = kind,
                        reason = "OBI returned HTTP ${response.code}",
                        diagnosticId = diagnosticId,
                    )
                } else {
                    diagnostics.parserStage(diagnosticId, "HTTP_SUCCESS")
                    val body = response.body?.string()
                    if (body != null) {
                        diagnostics.recordBodySignatures(diagnosticId, bodySignatures(body))
                    }
                    if (body.isNullOrBlank()) {
                        diagnostics.mappingTrace(diagnosticId, "ObiHttpFailureKind.DATA")
                        ObiHttpResult.Failure(
                            kind = ObiHttpFailureKind.DATA,
                            reason = emptyReason,
                            diagnosticId = diagnosticId,
                        )
                    } else {
                        ObiHttpResult.Success(
                            html = body,
                            diagnosticId = diagnosticId,
                        )
                    }
                }
            }
        } catch (exception: Exception) {
            diagnostics.mappingTrace(diagnosticId, "TRANSPORT_EXCEPTION")
            diagnostics.mappingTrace(diagnosticId, "ObiHttpFailureKind.TRANSPORT")
            ObiHttpResult.Failure(
                kind = ObiHttpFailureKind.TRANSPORT,
                reason = "OBI request failed: ${exception.message ?: exception.javaClass.simpleName}",
                diagnosticId = diagnosticId,
            )
        } finally {
            diagnostics.recordDuration(
                diagnosticId,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
            )
        }
    }

    private class SessionCookieJar : CookieJar {
        private val cookies = ConcurrentHashMap<String, Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                this.cookies["${cookie.domain}|${cookie.path}|${cookie.name}"] = cookie
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.filter { it.matches(url) }
    }
}
