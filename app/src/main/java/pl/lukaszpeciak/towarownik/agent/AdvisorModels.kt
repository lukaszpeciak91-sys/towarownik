package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import pl.lukaszpeciak.towarownik.product.VerifiedProductKey
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot

internal const val ADVISOR_PROXY_BASE_URL =
    "https://towarownik-proxy.lukaszpeciak91.workers.dev"

internal const val FIND_OBI_PRODUCTS = "find_obi_products"
internal const val MAX_LOCAL_TOOL_CALLS_PER_TURN = 2
internal const val MAX_TOOL_PRODUCTS = 5

internal data class AdvisorToolArguments(
    val query: String,
    val storeNumber: String,
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

internal data class AdvisorVerifiedProduct(
    val obik: String,
    val name: String,
    val stock: Int?,
    val price: BigDecimal?,
)

internal data class AdvisorVerifiedToolResult(
    val query: String,
    val storeNumber: String,
    val products: List<AdvisorVerifiedProduct>,
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
    ) : AdvisorProxyResult

    data class ToolRequest(
        val responseId: String,
        val callId: String,
        val arguments: AdvisorToolArguments,
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
