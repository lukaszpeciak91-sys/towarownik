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

class ObiPayloadParser(private val json: Json = Json { ignoreUnknownKeys = true }) {
    fun parse(html: String, expectedObik: String, storeNumber: String): Result<LocalProduct> = runCatching {
        val nuxt = parseScript(html, "__NUXT_DATA__") ?: error("Missing OBI Nuxt payload")
        val decoded = NuxtDecoder(nuxt).decode()
        val selectedContext = decoded.objects().firstOrNull { candidate ->
            val selectedStore = candidate["selectedStore"] as? JsonObject
            val selectedProduct = candidate["product"] as? JsonObject
            selectedStore?.string("storeNumber") == storeNumber &&
                selectedProduct != null &&
                PRODUCT_NUMBER_KEYS.any { key -> selectedProduct.string(key) == expectedObik }
        } ?: error("Product $expectedObik for selected OBI store $storeNumber is absent from OBI payload")
        val product = selectedContext["product"] as JsonObject

        val name = PRODUCT_NAME_KEYS.firstNotNullOfOrNull(product::string)
            ?: jsonLdProduct(html, expectedObik)?.string("name")
            ?: error("Product name is missing")
        val canonicalUrl = product.string("canonicalUrl")
            ?: product.string("productUrl")
            ?: canonicalLink(html)
            ?: "https://www.obi.pl/p/$expectedObik"
        val pricing = product["pricing"] as? JsonObject
        val grossPrice = pricing?.decimal("grossPrice")
        val stock = when (val value = product["stock"]) {
            is JsonPrimitive -> value.intOrNull?.takeIf { it >= 0 }
            else -> null
        }
        val ean = EAN_KEYS.firstNotNullOfOrNull(product::string)
            ?: jsonLdProduct(html, expectedObik)?.let { EAN_KEYS.firstNotNullOfOrNull(it::string) }

        LocalProduct(expectedObik, name, stock, grossPrice, canonicalUrl, ean, storeNumber)
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
            objectValue.string("@type") == "Product" && PRODUCT_NUMBER_KEYS.any { objectValue.string(it) == obik }
        }
    }

    private fun canonicalLink(html: String): String? =
        Regex("<link\\b(?=[^>]*rel=[\\\"']canonical[\\\"'])[^>]*href=[\\\"']([^\\\"']+)", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)

    private class NuxtDecoder(private val root: JsonElement) {
        private val flattened = root as? JsonArray

        fun decode(): JsonElement = if (flattened == null) root else resolve(flattened.firstOrNull() ?: JsonNull, true, mutableSetOf())

        private fun resolve(element: JsonElement, referencesAllowed: Boolean, visiting: MutableSet<Int>): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { resolve(it.value, true, visiting) })
            is JsonArray -> JsonArray(element.map { resolve(it, true, visiting) })
            is JsonPrimitive -> {
                val reference = if (referencesAllowed && !element.isString) element.intOrNull else null
                if (reference != null && reference in flattened!!.indices && visiting.add(reference)) {
                    resolve(flattened[reference], false, visiting).also { visiting.remove(reference) }
                } else element
            }
            else -> element
        }
    }

    private companion object {
        val PRODUCT_NUMBER_KEYS = listOf("obik", "productNumber", "articleNumber", "sku")
        val PRODUCT_NAME_KEYS = listOf("name", "productName")
        val EAN_KEYS = listOf("ean", "gtin13", "gtin")
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

private fun JsonObject.decimal(key: String): BigDecimal? =
    (get(key) as? JsonPrimitive)?.contentOrNull?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 }
