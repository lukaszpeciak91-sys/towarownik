package pl.lukaszpeciak.towarownik

import androidx.compose.runtime.saveable.Saver
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProductRef
import pl.lukaszpeciak.towarownik.product.provider.ProviderId

internal val ManualSearchUiStateSaver = Saver<ManualSearchUiState, String>(
    save = { state -> encodeManualSearchState(state) },
    restore = { raw -> decodeManualSearchState(raw) },
)

internal fun encodeManualSearchState(state: ManualSearchUiState): String {
    val persistable = if (state is ManualSearchUiState.Loading) {
        ManualSearchUiState.Idle
    } else {
        state
    }

    return buildJsonObject {
        when (persistable) {
            ManualSearchUiState.Idle,
            ManualSearchUiState.Loading -> put("type", "idle")

            is ManualSearchUiState.Error -> {
                put("type", "error")
                put("error", persistable.error.name)
            }

            is ManualSearchUiState.Product -> {
                put("type", "product")
                putProduct("product", persistable.item)
            }

            is ManualSearchUiState.SearchResults -> {
                put("type", "results")
                put(
                    "reportedTotal",
                    persistable.reportedTotalCount
                        ?.let(::JsonPrimitive)
                        ?: JsonNull,
                )
                put("visibleCount", persistable.visibleCount)
                put(
                    "items",
                    buildJsonArray {
                        persistable.items.forEachIndexed { index, item ->
                            add(
                                buildJsonObject {
                                    put("providerId", item.ref.providerId.value)
                                    put("productId", item.ref.productId)
                                    put("branchId", item.branchId.value)
                                    putNullable("branchLabel", item.branchLabel)
                                    putNullable("articleNumber", item.articleNumber)
                                    putNullable("name", item.name)
                                    when (val enrichment = item.enrichment) {
                                        ManualResultEnrichment.Pending,
                                        ManualResultEnrichment.Loading -> {
                                            put(
                                                "enrichment",
                                                if (index < persistable.visibleCount) {
                                                    "unavailable"
                                                } else {
                                                    "pending"
                                                },
                                            )
                                        }

                                        ManualResultEnrichment.Unavailable ->
                                            put("enrichment", "unavailable")

                                        is ManualResultEnrichment.Verified -> {
                                            put("enrichment", "verified")
                                            putProduct(
                                                "verifiedProduct",
                                                enrichment.product,
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }
    }.toString()
}

internal fun decodeManualSearchState(raw: String): ManualSearchUiState =
    runCatching {
        val root = Json.parseToJsonElement(raw) as JsonObject
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "idle" -> ManualSearchUiState.Idle
            "error" -> ManualSearchUiState.Error(
                root["error"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let { value ->
                        runCatching { SearchUiError.valueOf(value) }.getOrNull()
                    }
                    ?: SearchUiError.LOOKUP,
            )

            "product" -> {
                val product = root.product("product")
                    ?: root.legacyProduct()
                    ?: return@runCatching ManualSearchUiState.Idle
                ManualSearchUiState.Product(product)
            }

            "results" -> {
                val items = (root["items"] as? JsonArray)
                    ?.mapNotNull { element ->
                        val item = element as? JsonObject
                            ?: return@mapNotNull null
                        val providerId = item.string("providerId")
                            ?: OBI_PROVIDER_ID.value
                        val productId = item.string("productId")
                            ?: item.string("obik")
                            ?: return@mapNotNull null
                        val branchId = item.string("branchId")
                            ?: item.string("store")
                            ?: DEFAULT_OBI_STORE_NUMBER
                        val enrichment = when (item.string("enrichment")) {
                            "verified" ->
                                item.product("verifiedProduct")
                                    ?.let(ManualResultEnrichment::Verified)
                                    ?: ManualResultEnrichment.Pending
                            "unavailable" -> ManualResultEnrichment.Unavailable
                            else -> ManualResultEnrichment.Pending
                        }

                        ManualSearchResultItem(
                            ref = ProductRef(
                                providerId = ProviderId(providerId),
                                productId = productId,
                            ),
                            name = item.string("name"),
                            branchId = BranchId(branchId),
                            branchLabel = item.string("branchLabel"),
                            articleNumber = item.string("articleNumber"),
                            enrichment = enrichment,
                        )
                    }
                    .orEmpty()

                ManualSearchUiState.SearchResults(
                    items = items,
                    reportedTotalCount = root["reportedTotal"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive
                        ?.intOrNull,
                    visibleCount = root["visibleCount"]
                        ?.jsonPrimitive
                        ?.intOrNull
                        ?.coerceIn(0, items.size)
                        ?: minOf(MANUAL_RESULTS_PAGE_SIZE, items.size),
                )
            }

            else -> ManualSearchUiState.Idle
        }
    }.getOrDefault(ManualSearchUiState.Idle)

private fun kotlinx.serialization.json.JsonObjectBuilder.putProduct(
    key: String,
    product: VerifiedProductUiModel,
) {
    put(
        key,
        buildJsonObject {
            put("name", product.name)
            put("providerId", product.providerId)
            put("productId", product.productId)
            put("branchId", product.branchId)
            putNullable("branchLabel", product.branchLabel)
            putNullable("articleNumber", product.articleNumber)
            put("obik", product.obik)
            put("store", product.storeNumber)
            put("url", product.productUrl)
            putNullable("imageUrl", product.primaryImageUrl)
            putNullable("price", product.grossPrice?.toPlainString())
            put(
                "stock",
                product.stock?.let(::JsonPrimitive) ?: JsonNull,
            )
            put(
                "centralStock",
                product.centralStock?.let(::JsonPrimitive) ?: JsonNull,
            )
            put(
                "verifiedAt",
                product.verifiedAt?.let(::JsonPrimitive) ?: JsonNull,
            )
        },
    )
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(
    key: String,
    value: String?,
) {
    put(key, value?.let(::JsonPrimitive) ?: JsonNull)
}

private fun JsonObject.string(key: String): String? =
    this[key]
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.contentOrNull

private fun JsonObject.product(key: String): VerifiedProductUiModel? {
    val value = this[key] as? JsonObject ?: return null
    val name = value.string("name") ?: return null
    val productId = value.string("productId")
        ?: value.string("obik")
        ?: return null
    val providerId = value.string("providerId") ?: OBI_PROVIDER_ID.value
    val branchId = value.string("branchId")
        ?: value.string("store")
        ?: DEFAULT_OBI_STORE_NUMBER
    val url = value.string("url") ?: return null

    return VerifiedProductUiModel(
        name = name,
        obik = value.string("obik") ?: productId,
        grossPrice = value.string("price")?.let(::BigDecimal),
        stock = value["stock"]
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.intOrNull,
        centralStock = value["centralStock"]
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.intOrNull,
        productUrl = url,
        verifiedAt = value.string("verifiedAt")?.toLongOrNull(),
        storeNumber = value.string("store") ?: branchId,
        primaryImageUrl = value.string("imageUrl"),
        providerId = providerId,
        productId = productId,
        branchId = branchId,
        articleNumber = value.string("articleNumber"),
        branchLabel = value.string("branchLabel"),
    )
}


private fun JsonObject.legacyProduct(): VerifiedProductUiModel? {
    val name = string("name") ?: return null
    val obik = string("obik") ?: return null
    val url = string("url") ?: return null
    val store = string("store") ?: DEFAULT_OBI_STORE_NUMBER
    return VerifiedProductUiModel(
        name = name,
        obik = obik,
        grossPrice = string("price")?.let(::BigDecimal),
        stock = this["stock"]
            ?.takeUnless { it is JsonNull }
            ?.jsonPrimitive
            ?.intOrNull,
        productUrl = url,
        verifiedAt = string("verifiedAt")?.toLongOrNull(),
        storeNumber = store,
        primaryImageUrl = string("imageUrl"),
        providerId = OBI_PROVIDER_ID.value,
        productId = obik,
        branchId = store,
    )
}
