package pl.lukaszpeciak.towarownik.product

import java.math.BigDecimal
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnosticRecorder
import pl.lukaszpeciak.towarownik.diagnostics.ObiDiagnostics

data class TechnicalFact(
    val label: String,
    val value: String,
)

data class LocalProduct(
    val obik: String,
    val name: String,
    val stock: Int?,
    val grossPrice: BigDecimal?,
    val productUrl: String,
    internal val ean: String?,
    val storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    val brand: String? = null,
    val shortDescription: String? = null,
    val technicalFacts: List<TechnicalFact> = emptyList(),
)

enum class ProductLookupFailure {
    NETWORK,
    NOT_FOUND,
    DATA,
}

sealed interface ProductLookupResult {
    data class Found(val product: LocalProduct) : ProductLookupResult
    data class InvalidObik(val input: String) : ProductLookupResult
    data class InvalidStore(val storeNumber: String) : ProductLookupResult
    data class Unavailable(
        val failure: ProductLookupFailure,
        internal val reason: String,
    ) : ProductLookupResult
}

class ProductLookupRepository(
    private val httpClient: ObiHttpClient = ObiHttpClient(),
    private val parser: ObiPayloadParser = ObiPayloadParser(),
    private val diagnostics: ObiDiagnosticRecorder = ObiDiagnostics.recorder,
) {
    fun lookupObik(
        obik: String,
        storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    ): ProductLookupResult {
        if (!OBIK_PATTERN.matches(obik)) {
            return ProductLookupResult.InvalidObik(obik)
        }
        if (!isSupportedObiStoreNumber(storeNumber)) {
            return ProductLookupResult.InvalidStore(storeNumber)
        }

        return when (val response = httpClient.fetchProduct(obik, storeNumber)) {
            is ObiHttpResult.Success -> {
                val parsed = parser.parse(
                    html = response.html,
                    expectedObik = obik,
                    storeNumber = storeNumber,
                    diagnosticId = response.diagnosticId,
                )
                parsed.fold(
                    onSuccess = {
                        diagnostics.mappingTrace(
                            response.diagnosticId,
                            "ProductLookupResult.Found",
                        )
                        diagnostics.finish(response.diagnosticId)
                        ProductLookupResult.Found(it)
                    },
                    onFailure = { exception ->
                        diagnostics.mappingTrace(
                            response.diagnosticId,
                            "ProductLookupFailure.DATA",
                        )
                        diagnostics.mappingTrace(
                            response.diagnosticId,
                            "UI DATA_ERROR",
                        )
                        diagnostics.finish(response.diagnosticId)
                        ProductLookupResult.Unavailable(
                            failure = ProductLookupFailure.DATA,
                            reason = exception.message
                                ?: "OBI payload could not be parsed",
                        )
                    },
                )
            }

            is ObiHttpResult.Failure -> {
                val failure = when (response.kind) {
                    ObiHttpFailureKind.TRANSPORT,
                    ObiHttpFailureKind.SERVER ->
                        ProductLookupFailure.NETWORK
                    ObiHttpFailureKind.NOT_FOUND ->
                        ProductLookupFailure.NOT_FOUND
                    ObiHttpFailureKind.DATA ->
                        ProductLookupFailure.DATA
                }
                diagnostics.mappingTrace(
                    response.diagnosticId,
                    "ProductLookupFailure.$failure",
                )
                diagnostics.mappingTrace(
                    response.diagnosticId,
                    when (failure) {
                        ProductLookupFailure.NETWORK -> "UI NETWORK_ERROR"
                        ProductLookupFailure.NOT_FOUND -> "UI NOT_FOUND"
                        ProductLookupFailure.DATA -> "UI DATA_ERROR"
                    },
                )
                diagnostics.finish(response.diagnosticId)
                ProductLookupResult.Unavailable(
                    failure = failure,
                    reason = response.reason,
                )
            }
        }
    }

    private companion object {
        val OBIK_PATTERN = Regex("[0-9]{7}")
    }
}
