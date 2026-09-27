package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.VerifiedProductKey
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot

internal const val ADVISOR_PROXY_BASE_URL =
    "https://towarownik-proxy.lukaszpeciak91.workers.dev"

internal const val FIND_OBI_PRODUCTS = "find_obi_products"
internal const val MAX_LOCAL_TOOL_CALLS_PER_TURN = 2
internal const val MAX_TOOL_PRODUCTS = 5

internal data class AdvisorToolArguments(
    val query: String,
    val storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    val limit: Int,
)

internal data class AdvisorProductRef(
    val storeNumber: String,
    val obik: String,
) {
    val key: VerifiedProductKey
        get() = VerifiedProductKey(
            storeNumber = storeNumber,
            obik = obik,
        )
}

internal data class AdvisorTechnicalFact(
    val label: String,
    val value: String,
)

internal data class AdvisorVerifiedProduct(
    val obik: String,
    val name: String,
    val stock: Int?,
    val price: BigDecimal?,
    val brand: String? = null,
    val shortDescription: String? = null,
    val technicalFacts: List<AdvisorTechnicalFact> = emptyList(),
)

internal data class AdvisorVerifiedToolResult(
    val query: String,
    val storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    val products: List<AdvisorVerifiedProduct>,
)

internal enum class AdvisorRequestType {
    START,
    MESSAGE,
    CONTINUE,
}

internal data class AdvisorUsage(
    val model: String,
    val requestType: AdvisorRequestType,
    val inputTokens: Long,
    val cachedInputTokens: Long?,
    val cacheWriteTokens: Long? = null,
    val outputTokens: Long,
    val reasoningTokens: Long?,
    val totalTokens: Long,
    val estimatedCostUsd: BigDecimal?,
    val pricingVersion: String?,
)

internal sealed interface AdvisorToolContinuation {
    data class Verified(
        val result: AdvisorVerifiedToolResult,
    ) : AdvisorToolContinuation

    data class RejectedStore(
        val query: String,
        val storeNumber: String,
    ) : AdvisorToolContinuation
}

internal sealed interface AdvisorProxyResult {
    data class Answer(
        val responseId: String,
        val text: String,
        val productRefs: List<AdvisorProductRef>,
        val usage: AdvisorUsage? = null,
    ) : AdvisorProxyResult

    data class ToolRequest(
        val responseId: String,
        val callId: String,
        val arguments: AdvisorToolArguments,
        val usage: AdvisorUsage? = null,
    ) : AdvisorProxyResult
}

internal enum class AdvisorProxyFailureKind {
    NOT_CONFIGURED,
    AUTHENTICATION,
    NETWORK,
    SERVICE,
    PROTOCOL,
}

internal sealed interface AdvisorProxyCallResult {
    data class Success(val result: AdvisorProxyResult) : AdvisorProxyCallResult
    data class Failure(val kind: AdvisorProxyFailureKind) : AdvisorProxyCallResult
}

internal sealed interface AdvisorToolExecutionResult {
    data class Success(
        val result: AdvisorVerifiedToolResult,
        val snapshots: List<VerifiedProductSnapshot>,
    ) : AdvisorToolExecutionResult

    data object UnsupportedStore : AdvisorToolExecutionResult
    data object Failure : AdvisorToolExecutionResult
}
