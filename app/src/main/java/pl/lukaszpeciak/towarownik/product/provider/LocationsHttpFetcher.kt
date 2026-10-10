package pl.lukaszpeciak.towarownik.product.provider

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import pl.lukaszpeciak.towarownik.product.ObiBrowserCompatibilityProfile

internal sealed interface LocationsHttpResult {
    data class Success(val json: String) : LocationsHttpResult
    data class Failure(val failure: LocationFailure) : LocationsHttpResult
}

internal fun interface LocationsHttpFetcher {
    suspend fun get(url: HttpUrl): LocationsHttpResult
}

/**
 * Dedicated JSON-only public GET transport. No cookies, redirects, selected-store navigation,
 * HTML fallback, disk cache, request/response logging, or unbounded body reads.
 */
internal class OkHttpLocationsFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) : LocationsHttpFetcher {
    override suspend fun get(url: HttpUrl): LocationsHttpResult {
        if (!trustedLocationsEndpoint(url)) {
            return LocationsHttpResult.Failure(LocationFailure.INVALID_LOCATION_IDS)
        }
        val builder = Request.Builder().url(url).get()
            .header("Accept", "application/json")
            .header("Accept-Language", "pl-PL")
        if (url.host == "www.obi.pl") {
            builder.header("User-Agent", ObiBrowserCompatibilityProfile.USER_AGENT)
        } else {
            builder.header("Origin", "https://kwant.net.pl")
                .header("Referer", "https://kwant.net.pl/")
        }
        val request = builder.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resume(
                            LocationsHttpResult.Failure(LocationFailure.TRANSPORT),
                        )
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { safeResponse(it, url) }
                    if (continuation.isActive) continuation.resume(result)
                }
            })
        }
    }

    private fun safeResponse(response: Response, expected: HttpUrl): LocationsHttpResult {
        if (response.request.url != expected || !trustedLocationsEndpoint(response.request.url)) {
            return LocationsHttpResult.Failure(LocationFailure.TRANSPORT)
        }
        if (!response.isSuccessful) {
            return LocationsHttpResult.Failure(LocationFailure.TRANSPORT)
        }
        val contentType = response.header("Content-Type")?.lowercase()
        if (contentType == null || !(contentType.contains("application/json") ||
                    contentType.contains("+json"))) {
            return LocationsHttpResult.Failure(LocationFailure.MALFORMED_RESPONSE)
        }
        if (response.body == null) {
            return LocationsHttpResult.Failure(LocationFailure.MALFORMED_RESPONSE)
        }
        return try {
            val bytes = response.peekBody(MAX_JSON_BYTES + 1L).bytes()
            if (bytes.isEmpty() || bytes.size > MAX_JSON_BYTES) {
                LocationsHttpResult.Failure(LocationFailure.MALFORMED_RESPONSE)
            } else {
                LocationsHttpResult.Success(bytes.toString(Charsets.UTF_8))
            }
        } catch (_: IOException) {
            LocationsHttpResult.Failure(LocationFailure.TRANSPORT)
        }
    }

    private companion object {
        const val MAX_JSON_BYTES = 64 * 1024
    }
}

/** Allow only the two researched public, exact-product JSON endpoints. */
internal fun trustedLocationsEndpoint(url: HttpUrl): Boolean {
    if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() ||
        url.password.isNotEmpty() || url.fragment != null) return false

    return when (url.host) {
        "www.obi.pl" -> {
            url.encodedPath.matches(Regex("""/api/pdp/v1/stock/[0-9]{7}""")) &&
                url.queryParameterNames == setOf("storeIds") &&
                url.queryParameterValues("storeIds").size == 1 &&
                url.queryParameter("storeIds")?.isNotEmpty() == true
        }
        "services.kwant.net.pl" -> {
            url.encodedPath.matches(Regex("""/api/front/products/[0-9]+/departments""")) &&
                url.queryParameterNames == setOf("extended") &&
                url.queryParameterValues("extended").size == 1 &&
                !url.queryParameter("extended").isNullOrBlank()
        }
        else -> false
    }
}
