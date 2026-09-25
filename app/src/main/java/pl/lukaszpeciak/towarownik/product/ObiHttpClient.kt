package pl.lukaszpeciak.towarownik.product

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface ObiHttpResult {
    data class Success(val html: String) : ObiHttpResult
    data class Failure(val reason: String) : ObiHttpResult
}

class ObiHttpClient(
    private val baseUrl: HttpUrl = HttpUrl.Builder()
        .scheme("https")
        .host("www.obi.pl")
        .build(),
    client: OkHttpClient? = null,
) {
    private val client = client ?: OkHttpClient.Builder()
        .cookieJar(SessionCookieJar())
        .followRedirects(true)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetchProduct(obik: String, storeNumber: String): ObiHttpResult {
        val selectionUrl = baseUrl.newBuilder()
            .addPathSegments("api/disc/store/change")
            .addQueryParameter("storeNumber", storeNumber)
            .addQueryParameter("redirectUrl", "/p/$obik")
            .build()
        val request = Request.Builder().url(selectionUrl).get().build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ObiHttpResult.Failure("OBI returned HTTP ${response.code}")
                } else {
                    val body = response.body?.string()
                    if (body.isNullOrBlank()) ObiHttpResult.Failure("OBI returned an empty product page")
                    else ObiHttpResult.Success(body)
                }
            }
        } catch (exception: Exception) {
            ObiHttpResult.Failure("OBI request failed: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }

    private class SessionCookieJar : CookieJar {
        private val cookies = ConcurrentHashMap<String, Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie -> this.cookies["${cookie.domain}|${cookie.path}|${cookie.name}"] = cookie }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.filter { it.matches(url) }
    }
}
