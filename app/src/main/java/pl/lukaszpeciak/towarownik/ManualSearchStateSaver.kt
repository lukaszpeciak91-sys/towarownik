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

internal val ManualSearchUiStateSaver = Saver<ManualSearchUiState, String>(
    save = { state -> encodeManualSearchState(state) },
    restore = { raw -> decodeManualSearchState(raw) },
)

private fun encodeManualSearchState(state: ManualSearchUiState): String {
    val persistable = if (state is ManualSearchUiState.Loading) {
        ManualSearchUiState.Idle
    } else {
        state
    }

    return buildJsonObject {
        when (persistable) {
            ManualSearchUiState.Idle -> {
                put("type", "idle")
            }

            ManualSearchUiState.Loading -> {
                put("type", "idle")
            }

            is ManualSearchUiState.Error -> {
                put("type", "error")
                put("error", persistable.error.name)
            }

            is ManualSearchUiState.Product -> {
                put("type", "product")
                put("name", persistable.item.name)
                put("obik", persistable.item.obik)
                put(
                    "price",
                    persistable.item.grossPrice
                        ?.let { JsonPrimitive(it.toPlainString()) }
                        ?: JsonNull,
                )
                put(
                    "stock",
                    persistable.item.stock
                        ?.let(::JsonPrimitive)
                        ?: JsonNull,
                )
                put("url", persistable.item.productUrl)
            }

            is ManualSearchUiState.SearchResults -> {
                put("type", "results")
                put("reportedTotal", persistable.reportedTotalCount)
                put("visibleCount", persistable.visibleCount)
                put(
                    "items",
                    buildJsonArray {
                        persistable.items.forEach { item ->
                            add(
                                buildJsonObject {
                                    put("obik", item.obik)
                                    put(
                                        "name",
                                        item.name
                                            ?.let(::JsonPrimitive)
                                            ?: JsonNull,
                                    )
                                    put("store", item.storeNumber)
                                    when (
                                        val enrichment =
                                            item.enrichment
                                    ) {
                                        ManualResultEnrichment.Pending,
                                        ManualResultEnrichment.Loading -> {
                                            put(
                                                "enrichment",
                                                "pending",
                                            )
                                        }

                                        ManualResultEnrichment.Unavailable -> {
                                            put(
                                                "enrichment",
                                                "unavailable",
                                            )
                                        }

                                        is ManualResultEnrichment.Verified -> {
                                            put(
                                                "enrichment",
                                                "verified",
                                            )
                                            put(
                                                "verifiedName",
                                                enrichment.product.name,
                                            )
                                            put(
                                                "verifiedStore",
                                                enrichment.product.storeNumber,
                                            )
                                            put(
                                                "verifiedUrl",
                                                enrichment.product.productUrl,
                                            )
                                            put(
                                                "verifiedStock",
                                                enrichment.product.stock
                                                    ?.let(::JsonPrimitive)
                                                    ?: JsonNull,
                                            )
                                            put(
                                                "verifiedPrice",
                                                enrichment.product.grossPrice
                                                    ?.let {
                                                        JsonPrimitive(
                                                            it.toPlainString(),
                                                        )
                                                    }
                                                    ?: JsonNull,
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

private fun decodeManualSearchState(raw: String): ManualSearchUiState =
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
            "product" -> ManualSearchUiState.Product(
                VerifiedProductUiModel(
                    name = checkNotNull(root["name"]?.jsonPrimitive?.contentOrNull),
                    obik = checkNotNull(root["obik"]?.jsonPrimitive?.contentOrNull),
                    grossPrice = root["price"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let(::BigDecimal),
                    stock = root["stock"]
                        ?.takeUnless { it is JsonNull }
                        ?.jsonPrimitive
                        ?.intOrNull,
                    productUrl = checkNotNull(root["url"]?.jsonPrimitive?.contentOrNull),
                ),
            )
            "results" -> {
                val items = (root["items"] as? JsonArray)
                    ?.mapNotNull { element ->
                        val item = element as? JsonObject ?: return@mapNotNull null
                        val obik = item["obik"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?: return@mapNotNull null
                        val name = item["name"]
                            ?.takeUnless { it is JsonNull }
                            ?.jsonPrimitive
                            ?.contentOrNull
                        val storeNumber = item["store"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?: pl.lukaszpeciak.towarownik.product
                                .DEFAULT_OBI_STORE_NUMBER
                        val enrichment = when (
                            item["enrichment"]
                                ?.jsonPrimitive
                                ?.contentOrNull
                        ) {
                            "verified" -> {
                                val verifiedName =
                                    item["verifiedName"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                val verifiedUrl =
                                    item["verifiedUrl"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                val verifiedStore =
                                    item["verifiedStore"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                if (
                                    verifiedName != null &&
                                    verifiedUrl != null &&
                                    verifiedStore != null
                                ) {
                                    ManualResultEnrichment.Verified(
                                        VerifiedProductUiModel(
                                            name = verifiedName,
                                            obik = obik,
                                            grossPrice =
                                                item["verifiedPrice"]
                                                    ?.takeUnless {
                                                        it is JsonNull
                                                    }
                                                    ?.jsonPrimitive
                                                    ?.contentOrNull
                                                    ?.let(::BigDecimal),
                                            stock =
                                                item["verifiedStock"]
                                                    ?.takeUnless {
                                                        it is JsonNull
                                                    }
                                                    ?.jsonPrimitive
                                                    ?.intOrNull,
                                            productUrl = verifiedUrl,
                                            storeNumber = verifiedStore,
                                        ),
                                    )
                                } else {
                                    ManualResultEnrichment.Pending
                                }
                            }

                            "unavailable" ->
                                ManualResultEnrichment.Unavailable

                            else -> ManualResultEnrichment.Pending
                        }
                        ManualSearchResultItem(
                            obik = obik,
                            name = name,
                            storeNumber = storeNumber,
                            enrichment = enrichment,
                        )
                    }
                    .orEmpty()

                ManualSearchUiState.SearchResults(
                    items = items,
                    reportedTotalCount = root["reportedTotal"]
                        ?.jsonPrimitive
                        ?.intOrNull
                        ?: items.size,
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
