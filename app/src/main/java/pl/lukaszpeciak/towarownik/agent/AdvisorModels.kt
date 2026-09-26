package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal

internal const val ADVISOR_PROXY_BASE_URL =
    "https://towarownik-proxy.lukaszpeciak91.workers.dev"

internal const val FIND_AVAILABLE_OBI_075 = "find_available_obi_075"
internal const val MAX_LOCAL_TOOL_CALLS_PER_CASE = 2
internal const val MAX_TOOL_PRODUCTS = 5

internal data class AdvisorToolArguments(
    val query: String,
    val limit: Int,
)

internal data class AdvisorVerifiedProduct(
    val obik: String,
    val name: String,
    val stock: Int?,
    val price: BigDecimal?,
)

internal data class AdvisorVerifiedToolResult(
    val query: String,
    val products: List<AdvisorVerifiedProduct>,
)

internal sealed interface AdvisorProxyResult {
    data class Answer(
        val responseId: String,
        val text: String,
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
    data class Success(val result: AdvisorVerifiedToolResult) : AdvisorToolExecutionResult
    data object Failure : AdvisorToolExecutionResult
}
