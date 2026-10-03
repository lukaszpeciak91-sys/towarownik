package pl.lukaszpeciak.towarownik.product.provider

import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import pl.lukaszpeciak.towarownik.product.TechnicalFact

internal val KWANT_PROVIDER_ID = ProviderId("kwant-pl")

internal data class KwantBranchMetadata(
    val departmentStockId: Int,
    val departmentStockName: String,
    val departmentStockPostcode: String,
    val departmentStockStreet: String,
) {
    val branchId: BranchId
        get() = BranchId(departmentStockId.toString())

    fun departmentCookieJson(): String =
        "{\"department_stock_id\":" + departmentStockId +
            ",\"department_stock_name\":" + jsonString(departmentStockName) +
            ",\"department_stock_postcode\":" + jsonString(departmentStockPostcode) +
            ",\"department_stock_street\":" + jsonString(departmentStockStreet) +
            "}"

    private fun jsonString(value: String): String =
        JsonPrimitive(value).toString()
}

internal sealed interface KwantFrontendResult {
    data class Success(
        val html: String,
        val finalUrl: String,
    ) : KwantFrontendResult

    data object NotFound : KwantFrontendResult

    data class Failure(
        val reason: String,
    ) : KwantFrontendResult
}

internal interface KwantFrontendClient {
    fun fetchBranchDirectory(): KwantFrontendResult

    fun fetchSearch(query: String): KwantFrontendResult

    fun fetchProduct(
        productUrl: String,
        departmentCookieJson: String,
    ): KwantFrontendResult
}

internal class KwantHttpFrontendClient(
    private val client: OkHttpClient = OkHttpClient(),
) : KwantFrontendClient {
    override fun fetchBranchDirectory(): KwantFrontendResult =
        execute(
            Request.Builder()
                .url("$KWANT_ORIGIN/lista-hurtowni-elektrycznych")
                .get()
                .build(),
        )

    override fun fetchSearch(query: String): KwantFrontendResult {
        val encoded = encodeComponent(query)
        return execute(
            Request.Builder()
                .url("$KWANT_ORIGIN/wyniki-wyszukiwania?phrase=$encoded")
                .get()
                .build(),
        )
    }

    override fun fetchProduct(
        productUrl: String,
        departmentCookieJson: String,
    ): KwantFrontendResult {
        if (!productUrl.startsWith("$KWANT_ORIGIN/produkt/")) {
            return KwantFrontendResult.Failure("Untrusted KWANT product URL")
        }
        return execute(
            Request.Builder()
                .url(productUrl)
                .header(
                    "Cookie",
                    "departmentCookie=" + encodeComponent(departmentCookieJson),
                )
                .get()
                .build(),
        )
    }

    private fun execute(request: Request): KwantFrontendResult =
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> KwantFrontendResult.NotFound
                    !response.isSuccessful ->
                        KwantFrontendResult.Failure(
                            "KWANT HTTP " + response.code,
                        )
                    else -> {
                        val finalUrl = response.request.url.toString()
                        if (!finalUrl.startsWith(KWANT_ORIGIN)) {
                            KwantFrontendResult.Failure(
                                "Unexpected KWANT response host",
                            )
                        } else {
                            KwantFrontendResult.Success(
                                html = response.body?.string().orEmpty(),
                                finalUrl = finalUrl,
                            )
                        }
                    }
                }
            }
        } catch (exception: Exception) {
            KwantFrontendResult.Failure(
                exception.message ?: "KWANT request failed",
            )
        }

    private fun encodeComponent(value: String): String =
        URLEncoder.encode(
            value,
            StandardCharsets.UTF_8.name(),
        ).replace("+", "%20")

    private companion object {
        const val KWANT_ORIGIN = "https://kwant.net.pl"
    }
}

internal class KwantProductProvider(
    private val frontend: KwantFrontendClient = KwantHttpFrontendClient(),
    private val parser: KwantFrontendParser = KwantFrontendParser(),
) : ProductProvider {
    override val providerId: ProviderId = KWANT_PROVIDER_ID

    private val productUrls = ConcurrentHashMap<String, String>()

    override fun search(
        query: String,
        maxResults: Int,
    ): ProviderSearchResult {
        require(maxResults > 0)
        val normalized = query.trim()
        if (normalized.isEmpty()) return ProviderSearchResult.NotFound

        return when (val response = frontend.fetchSearch(normalized)) {
            is KwantFrontendResult.Success -> {
                val parsed = parser.parseSearch(response.html, normalized)
                if (parsed.isEmpty()) {
                    ProviderSearchResult.NotFound
                } else {
                    parsed.forEach { candidate ->
                        productUrls[candidate.productId] = candidate.productUrl
                    }
                    ProviderSearchResult.Candidates(
                        items = parsed
                            .take(maxResults)
                            .map { candidate ->
                                ProviderProductCandidate(
                                    ref = ProductRef(
                                        providerId = providerId,
                                        productId = candidate.productId,
                                    ),
                                    name = candidate.name,
                                )
                            },
                        reportedTotalCount = parsed.size,
                    )
                }
            }

            KwantFrontendResult.NotFound -> ProviderSearchResult.NotFound

            is KwantFrontendResult.Failure ->
                ProviderSearchResult.Unavailable(
                    failure = ProductProviderFailure.NETWORK,
                    reason = response.reason,
                )
        }
    }

    override fun lookup(
        ref: ProductRef,
        branchId: BranchId,
    ): ProviderLookupResult {
        if (ref.providerId != providerId) {
            return ProviderLookupResult.WrongProvider(ref)
        }
        if (!PRODUCT_ID.matches(ref.productId)) {
            return ProviderLookupResult.InvalidProductId(ref)
        }

        val branch = when (val resolved = resolveBranch(branchId)) {
            is BranchResolution.Found -> resolved.metadata
            BranchResolution.Invalid ->
                return ProviderLookupResult.InvalidBranch(branchId)
            is BranchResolution.Failure ->
                return ProviderLookupResult.Unavailable(
                    failure = ProductProviderFailure.NETWORK,
                    reason = resolved.reason,
                )
        }

        val productUrl = productUrls[ref.productId]
            ?: resolveProductUrl(ref.productId)
            ?: return ProviderLookupResult.Unavailable(
                failure = ProductProviderFailure.DATA,
                reason = "KWANT product URL could not be resolved",
            )

        return when (
            val response = frontend.fetchProduct(
                productUrl = productUrl,
                departmentCookieJson = branch.departmentCookieJson(),
            )
        ) {
            is KwantFrontendResult.Success -> {
                val product = parser.parseProduct(
                    html = response.html,
                    finalUrl = response.finalUrl,
                    expectedProductId = ref.productId,
                    branch = branch,
                ) ?: return ProviderLookupResult.Unavailable(
                    failure = ProductProviderFailure.DATA,
                    reason = "KWANT product payload could not be parsed",
                )
                ProviderLookupResult.Found(
                    product.copy(
                        ref = ref,
                        branchId = branchId,
                    ),
                )
            }

            KwantFrontendResult.NotFound ->
                ProviderLookupResult.Unavailable(
                    failure = ProductProviderFailure.NOT_FOUND,
                    reason = "KWANT product not found",
                )

            is KwantFrontendResult.Failure ->
                ProviderLookupResult.Unavailable(
                    failure = ProductProviderFailure.NETWORK,
                    reason = response.reason,
                )
        }
    }

    private fun resolveProductUrl(productId: String): String? {
        val response = frontend.fetchSearch(productId)
        if (response !is KwantFrontendResult.Success) return null
        val match = parser.parseSearch(response.html, productId)
            .firstOrNull { it.productId == productId }
            ?: return null
        productUrls[productId] = match.productUrl
        return match.productUrl
    }

    private fun resolveBranch(branchId: BranchId): BranchResolution =
        when (val response = frontend.fetchBranchDirectory()) {
            is KwantFrontendResult.Success -> {
                val branch = parser.parseBranches(response.html)
                    .firstOrNull { it.branchId == branchId }
                if (branch == null) {
                    BranchResolution.Invalid
                } else {
                    BranchResolution.Found(branch)
                }
            }

            KwantFrontendResult.NotFound -> BranchResolution.Invalid

            is KwantFrontendResult.Failure ->
                BranchResolution.Failure(response.reason)
        }

    private sealed interface BranchResolution {
        data class Found(
            val metadata: KwantBranchMetadata,
        ) : BranchResolution

        data object Invalid : BranchResolution

        data class Failure(
            val reason: String,
        ) : BranchResolution
    }

    private companion object {
        val PRODUCT_ID = Regex("[0-9]+")
    }
}

internal data class KwantSearchCandidate(
    val productId: String,
    val name: String?,
    val productUrl: String,
    val code: String?,
)

internal class KwantFrontendParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parseBranches(html: String): List<KwantBranchMetadata> {
        val pageProps = pageProps(html) ?: return emptyList()
        val departments = (pageProps["departments"] as? JsonObject)
            ?.get("list") as? JsonArray
            ?: return emptyList()

        return departments.mapNotNull { element ->
            val branch = element as? JsonObject ?: return@mapNotNull null
            val id = branch.int("department_id") ?: return@mapNotNull null
            val name = branch.string("name") ?: return@mapNotNull null
            val postcode = branch.string("postcode") ?: return@mapNotNull null
            val street = branch.string("street") ?: return@mapNotNull null
            KwantBranchMetadata(
                departmentStockId = id,
                departmentStockName = name,
                departmentStockPostcode = postcode,
                departmentStockStreet = street,
            )
        }
    }

    fun parseSearch(
        html: String,
        query: String,
    ): List<KwantSearchCandidate> {
        val pageProps = pageProps(html) ?: return emptyList()
        val groups = pageProps["categoriesFacetsProducts"] as? JsonArray
            ?: return emptyList()
        val candidates = mutableListOf<KwantSearchCandidate>()

        groups.forEach { groupElement ->
            val group = groupElement as? JsonObject ?: return@forEach
            val items = group["list"] as? JsonArray ?: return@forEach
            items.forEach { itemElement ->
                val item = itemElement as? JsonObject ?: return@forEach
                val id = item.int("id")?.toString() ?: return@forEach
                val slug = item.string("slug") ?: return@forEach
                candidates += KwantSearchCandidate(
                    productId = id,
                    name = item.string("name"),
                    productUrl = "https://kwant.net.pl/produkt/$slug",
                    code = item.string("code"),
                )
            }
        }

        val normalizedQuery = query.trim().lowercase()
        val tokens = normalizedQuery
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

        return candidates
            .distinctBy(KwantSearchCandidate::productId)
            .sortedByDescending { candidate ->
                val haystack = listOfNotNull(
                    candidate.code,
                    candidate.name,
                    candidate.productId,
                ).joinToString(" ").lowercase()
                when {
                    candidate.productId == normalizedQuery -> 1000
                    candidate.code
                        ?.lowercase()
                        ?.contains(normalizedQuery) == true -> 900
                    candidate.name
                        ?.lowercase()
                        ?.contains(normalizedQuery) == true -> 800
                    else -> tokens.count(haystack::contains)
                }
            }
    }

    fun parseProduct(
        html: String,
        finalUrl: String,
        expectedProductId: String,
        branch: KwantBranchMetadata,
    ): ProviderProduct? {
        val pageProps = pageProps(html) ?: return null
        val product = pageProps["product"] as? JsonObject ?: return null
        val productId = product.int("id")?.toString() ?: return null
        if (productId != expectedProductId) return null

        val name = product.string("name") ?: return null
        val price = product.decimal("gross_price")
            ?: product.decimal("grossPrice")
        val description = product.string("description")
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val facts = (product["attributes"] as? JsonArray)
            ?.mapNotNull { attributeElement ->
                val attribute = attributeElement as? JsonObject
                    ?: return@mapNotNull null
                val label = attribute.string("name")
                    ?: return@mapNotNull null
                val value = attribute.string("value")
                    ?: return@mapNotNull null
                TechnicalFact(label = label, value = value)
            }
            .orEmpty()
        val image = (product["main_image"] as? JsonObject)?.string("href")
            ?: (product["mainImage"] as? JsonObject)?.string("url")

        return ProviderProduct(
            ref = ProductRef(KWANT_PROVIDER_ID, productId),
            branchId = branch.branchId,
            name = name,
            stock = selectedBranchStock(html, branch.departmentStockName),
            grossPrice = price,
            priceScope = price?.let { ProviderPriceScope.ONLINE },
            productUrl = finalUrl.substringBefore("?"),
            ean = product.string("ean"),
            articleNumber = product.string("code"),
            brand = (product["producer"] as? JsonObject)?.string("name"),
            shortDescription = description,
            technicalFacts = facts,
            primaryImageUrl = image,
        )
    }

    private fun selectedBranchStock(
        html: String,
        branchName: String,
    ): Int? {
        val match = Regex(
            Regex.escape(branchName) +
                """\s*:\s*<span[^>]*>\s*([0-9]+)\s*""",
            RegexOption.IGNORE_CASE,
        ).find(html) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun pageProps(html: String): JsonObject? {
        val match = NEXT_DATA.find(html) ?: return null
        val root = runCatching {
            json.parseToJsonElement(match.groupValues[1]).jsonObject
        }.getOrNull() ?: return null
        return ((root["props"] as? JsonObject)
            ?.get("pageProps")) as? JsonObject
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.decimal(key: String): BigDecimal? =
        (this[key] as? JsonPrimitive)
            ?.contentOrNull
            ?.toBigDecimalOrNull()

    private companion object {
        val NEXT_DATA = Regex(
            """<script[^>]+id=["']__NEXT_DATA__["'][^>]*>(.*?)</script>""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL,
            ),
        )
    }
}
