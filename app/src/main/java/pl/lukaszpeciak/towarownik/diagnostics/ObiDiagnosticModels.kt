package pl.lukaszpeciak.towarownik.diagnostics

enum class ObiDiagnosticOperationType {
    PRODUCT_LOOKUP,
    SEARCH,
    STORE_SELECTION,
}

enum class ObiDiagnosticInputType {
    OBIK,
    EAN,
    TEXT,
    UNKNOWN,
}

enum class Store075CookieMatch(val reportValue: String) {
    MATCH("true"),
    MISMATCH("false"),
    UNKNOWN("unknown"),
}

data class DiagnosticDeviceContext(
    val versionName: String,
    val versionCode: Long,
    val androidVersion: String,
    val apiLevel: Int,
    val manufacturer: String,
    val model: String,
)

data class DiagnosticCookie(
    val name: String,
    val domain: String?,
    val path: String?,
)

data class DiagnosticHttpHop(
    val number: Int,
    val status: Int,
    val url: String,
    val location: String?,
    val protocol: String?,
    val safeRequestHeaders: Map<String, String>,
    val contentType: String?,
    val contentEncoding: String?,
    val declaredContentLength: Long?,
    val safeInfrastructureHeaders: Map<String, String>,
    val outgoingCookies: List<DiagnosticCookie>,
    val setCookies: List<DiagnosticCookie>,
    val outgoingStore075CookieMatch: Store075CookieMatch,
    val setCookieStore075Match: Store075CookieMatch,
)

data class DiagnosticBodySignatures(
    val decodedBodyUtf8Bytes: Int,
    val looksLikeHtml: Boolean,
    val title: String?,
    val accessDeniedOrChallenge: Boolean,
    val challengeIndicators: List<String>,
    val containsNuxtData: Boolean,
    val containsRequestedObik: Boolean?,
    val containsCanonicalProductUrl: Boolean?,
    val canonicalUrl: String?,
    val containsSelectedStore: Boolean?,
    val containsStore075: Boolean?,
    val recognizedProductLinkCount: Int,
    val containsSearchResultsPhrase: Boolean?,
    val detectedSearchResultCount: Int?,
    val zeroResultPhraseNoResultsFound: Boolean?,
    val zeroResultPhraseProductsNotFound: Boolean?,
    val zeroResultPhraseNoResults: Boolean?,
    val containsZeroCountToken: Boolean?,
)

data class DiagnosticOperationSnapshot(
    val id: Long,
    val operation: ObiDiagnosticOperationType,
    val inputType: ObiDiagnosticInputType,
    val identifier: String,
    val timestampMillis: Long,
    val requestedUrl: String,
    val requestMethod: String,
    val userAgent: String?,
    val accept: String?,
    val acceptLanguage: String?,
    val durationMillis: Long?,
    val outgoingCookies: List<DiagnosticCookie>,
    val setCookies: List<DiagnosticCookie>,
    val store075CookieMatch: Store075CookieMatch,
    val hops: List<DiagnosticHttpHop>,
    val finalStatus: Int?,
    val finalUrl: String?,
    val bodySignatures: DiagnosticBodySignatures?,
    val parserStages: List<String>,
    val errorMappingTrace: List<String>,
)
