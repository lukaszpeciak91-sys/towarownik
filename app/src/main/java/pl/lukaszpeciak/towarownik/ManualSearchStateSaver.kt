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
    save = ::encodeManualSearchState,
    restore = ::decodeManualSearchState,
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
                put("message", persistable.message)
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
                root["message"]?.jsonPrimitive?.contentOrNull.orEmpty(),
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
                        ManualSearchResultItem(
                            obik = obik,
                            name = name,
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
