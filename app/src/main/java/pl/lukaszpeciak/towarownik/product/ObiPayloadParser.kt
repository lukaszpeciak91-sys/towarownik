package pl.lukaszpeciak.towarownik.product

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticRecorder
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnostics

class ObiPayloadParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val diagnostics: ObiDiagnosticRecorder = ObiDiagnostics.recorder,
) {
    fun parse(
        html: String,
        expectedObik: String,
        storeNumber: String,
        diagnosticId: Long? = null,
    ): Result<LocalProduct> {
        diagnostics.parserStage(
            diagnosticId,
            "NUXT_PRESENT=${html.contains("__NUXT_DATA__")}",
        )

        val result = runCatching {
            val nuxt = try {
                parseScript(html, "__NUXT_DATA__")
            } catch (exception: Exception) {
                diagnostics.parserStage(diagnosticId, "NUXT_JSON_PARSE_FAILED")
                throw exception
            } ?: run {
                diagnostics.parserStage(diagnosticId, "NUXT_JSON_PARSE_FAILED")
                error("Missing OBI Nuxt payload")
            }
            diagnostics.parserStage(diagnosticId, "NUXT_JSON_PARSE_OK")

            val decoded = NuxtDecoder(nuxt).decode()
            val objects = decoded.objects().toList()

            val liveProduct = objects.firstOrNull { candidate ->
                candidate.matchesProduct(expectedObik) &&
                    candidate.liveStoreNumber() == storeNumber
            }
            val legacyContext = objects.firstOrNull { candidate ->
                val selectedStore = candidate["selectedStore"] as? JsonObject
                val selectedProduct = candidate["product"] as? JsonObject
                selectedStore?.string("storeNumber") == storeNumber &&
                    selectedProduct?.matchesProduct(expectedObik) == true
            }

            if (diagnosticId != null) {
                val productIdMatch = objects.any { candidate ->
                    candidate.matchesProduct(expectedObik) ||
                        (candidate["product"] as? JsonObject)?.matchesProduct(expectedObik) == true
                }
                val storeMatch = liveProduct != null || legacyContext != null
                diagnostics.parserStage(
                    diagnosticId,
                    if (productIdMatch) "PRODUCT_ID_MATCH" else "PRODUCT_ID_MATCH_FAILED",
                )
                diagnostics.parserStage(
                    diagnosticId,
                    if (storeMatch) "STORE_075_MATCH" else "STORE_075_MATCH_FAILED",
                )
            }

            val product: JsonObject
            val localArticleData: JsonObject
            when {
                liveProduct != null -> {
                    product = liveProduct
                    val store = product["store"] as? JsonObject
                        ?: error("Selected OBI store data is missing")
                    localArticleData = store["articleData"] as? JsonObject
                        ?: error("Selected OBI store article data is missing")
                }

                legacyContext != null -> {
                    product = legacyContext["product"] as JsonObject
                    localArticleData = product
                }

                else -> error(
                    "Expected product $expectedObik for selected OBI store $storeNumber is missing from OBI payload structure",
                )
            }

            val name = PRODUCT_NAME_KEYS.firstNotNullOfOrNull(product::string)
                ?: jsonLdProduct(html, expectedObik)?.string("name")
                ?: error("Product name is missing")
            val canonicalUrl = product.string("canonicalUrl")
                ?: product.string("productUrl")
                ?: canonicalLink(html)
                ?: product.string("prettyUrl")?.asObiUrl()
                ?: product.string("skuUrl")?.asObiUrl()
                ?: "https://www.obi.pl/p/$expectedObik"
            val pricing = localArticleData["pricing"] as? JsonObject
            val grossPrice = pricing?.decimal("grossPrice")
            val stock = localArticleData.nonNegativeInt("stock")
            val ean = EAN_KEYS.firstNotNullOfOrNull(product::stringOrSingletonString)
                ?: jsonLdProduct(html, expectedObik)?.let { EAN_KEYS.firstNotNullOfOrNull(it::string) }

            diagnostics.parserStage(
                diagnosticId,
                if (localArticleData["stock"] != null) "STOCK_PRESENT" else "STOCK_MISSING",
            )
            diagnostics.parserStage(
                diagnosticId,
                if (pricing?.get("grossPrice") != null) "PRICE_PRESENT" else "PRICE_MISSING",
            )

            LocalProduct(expectedObik, name, stock, grossPrice, canonicalUrl, ean, storeNumber)
        }

        result.onSuccess {
            diagnostics.parserStage(diagnosticId, "FINAL_PARSE_RESULT=SUCCESS")
        }.onFailure {
            diagnostics.parserStage(diagnosticId, "FINAL_PARSE_RESULT=FAILED")
        }
        return result
    }

    private fun parseScript(html: String, id: String): JsonElement? {
        val opening = Regex("<script\\b[^>]*\\bid=[\\\"']${Regex.escape(id)}[\\\"'][^>]*>", RegexOption.IGNORE_CASE)
            .find(html) ?: return null
        val end = html.indexOf("</script>", opening.range.last + 1, ignoreCase = true).takeIf { it >= 0 } ?: return null
        return json.parseToJsonElement(html.substring(opening.range.last + 1, end).trim())
    }

    private fun jsonLdProduct(html: String, obik: String): JsonObject? {
        val scripts = Regex("<script\\b[^>]*type=[\\\"']application/ld\\+json[\\\"'][^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return scripts.findAll(html).mapNotNull { match ->
            runCatching { json.parseToJsonElement(match.groupValues[1].trim()) }.getOrNull()
        }.flatMap { it.objects() }.firstOrNull { objectValue ->
            objectValue.string("@type") == "Product" && objectValue.matchesProduct(obik)
        }
    }

    private fun canonicalLink(html: String): String? =
        Regex("<link\\b(?=[^>]*rel=[\\\"']canonical[\\\"'])[^>]*href=[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

    private fun JsonObject.matchesProduct(obik: String): Boolean =
        PRODUCT_NUMBER_KEYS.any { key -> string(key) == obik }

    private fun JsonObject.liveStoreNumber(): String? {
        val store = get("store") as? JsonObject ?: return null
        val information = store["information"] as? JsonObject ?: return null
        return information.string("storeId") ?: information.string("storeNumber")
    }

    private class NuxtDecoder(private val root: JsonElement) {
        private val flattened = root as? JsonArray

        fun decode(): JsonElement =
            if (flattened == null) {
                root
            } else {
                resolve(flattened.firstOrNull() ?: JsonNull, true, mutableSetOf())
            }

        private fun resolve(
            element: JsonElement,
            referencesAllowed: Boolean,
            visiting: MutableSet<Int>,
        ): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { resolve(it.value, true, visiting) })

            is JsonArray -> {
                val wrapperReference = element.wrapperReference()
                if (
                    wrapperReference != null &&
                    wrapperReference in flattened!!.indices &&
                    visiting.add(wrapperReference)
                ) {
                    resolve(flattened[wrapperReference], false, visiting).also {
                        visiting.remove(wrapperReference)
                    }
                } else {
                    JsonArray(element.map { resolve(it, true, visiting) })
                }
            }

            is JsonPrimitive -> {
                val reference = if (referencesAllowed && !element.isString) element.intOrNull else null
                if (reference != null && reference in flattened!!.indices && visiting.add(reference)) {
                    resolve(flattened[reference], false, visiting).also { visiting.remove(reference) }
                } else {
                    element
                }
            }

            else -> element
        }

        private fun JsonArray.wrapperReference(): Int? {
            if (size < 2) return null
            val wrapper = (get(0) as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.contentOrNull
            if (wrapper !in REFERENCE_WRAPPERS) return null
            return (get(1) as? JsonPrimitive)
                ?.takeUnless { it.isString }
                ?.intOrNull
        }
    }

    private companion object {
        val REFERENCE_WRAPPERS = setOf("Ref", "ShallowRef")
        val PRODUCT_NUMBER_KEYS = listOf("skuId", "obik", "productNumber", "articleNumber", "sku")
        val PRODUCT_NAME_KEYS = listOf("productTitle", "productTitleTab", "name", "productName")
        val EAN_KEYS = listOf("articleEanEcms", "ean", "gtin13", "gtin")
    }
}

private fun JsonElement.objects(): Sequence<JsonObject> = sequence {
    when (this@objects) {
        is JsonObject -> {
            yield(this@objects)
            values.forEach { yieldAll(it.objects()) }
        }

        is JsonArray -> forEach { yieldAll(it.objects()) }
        else -> Unit
    }
}

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)

private fun JsonObject.stringOrSingletonString(key: String): String? =
    when (val value = get(key)) {
        is JsonPrimitive -> value.contentOrNull?.takeIf(String::isNotBlank)
        is JsonArray -> value.singleOrNull()
            ?.let { it as? JsonPrimitive }
            ?.takeIf { it.isString }
            ?.contentOrNull
            ?.takeIf(String::isNotBlank)
        else -> null
    }

private fun JsonObject.nonNegativeInt(key: String): Int? =
    (get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }

private fun JsonObject.decimal(key: String): BigDecimal? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }

private fun String.asObiUrl(): String =
    when {
        startsWith("https://") || startsWith("http://") -> this
        startsWith("/") -> "https://www.obi.pl$this"
        else -> "https://www.obi.pl/$this"
    }
