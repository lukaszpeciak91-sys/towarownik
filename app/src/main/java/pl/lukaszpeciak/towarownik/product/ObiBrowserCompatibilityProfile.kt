package pl.lukaszpeciak.towarownik.product

import okhttp3.Request

/**
 * OBI/CloudFront compatibility profile proven by the Android live probe.
 *
 * The User-Agent is a fixed synthetic browser-like Android Chrome string used only for
 * protocol compatibility. It does not represent the user's installed Chrome version.
 */
internal object ObiBrowserCompatibilityProfile {
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    const val ACCEPT =
        "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

    const val ACCEPT_LANGUAGE = "pl-PL,pl;q=0.9"

    fun apply(builder: Request.Builder): Request.Builder =
        builder
            .header("User-Agent", USER_AGENT)
            .header("Accept", ACCEPT)
            .header("Accept-Language", ACCEPT_LANGUAGE)
}
