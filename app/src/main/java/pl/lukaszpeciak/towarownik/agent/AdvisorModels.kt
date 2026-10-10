package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.VerifiedProductKey
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID

internal const val ADVISOR_PROXY_BASE_URL =
    "https://towarownik-proxy.lukaszpeciak91.workers.dev"

internal const val ADVISOR_PROTOCOL_VERSION = 3
internal const val OBI_ADVISOR_PROTOCOL_VERSION = 2
internal const val MULTIMODAL_ADVISOR_PROTOCOL_VERSION = 4
internal const val MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION = 5
internal const val FIND_OBI_PRODUCTS = "find_obi_products"
internal const val FIND_PRODUCTS = "find_products"
internal const val FIND_PRODUCT_LOCATIONS = "find_product_locations"

internal enum class AdvisorTransportContract(
    val protocolVersion: Int,
    val toolName: String,
) {
    OBI_V2(
        protocolVersion = OBI_ADVISOR_PROTOCOL_VERSION,
        toolName = FIND_OBI_PRODUCTS,
    ),
    PROVIDER_V3(
        protocolVersion = ADVISOR_PROTOCOL_VERSION,
        toolName = FIND_PRODUCTS,
    ),
    PROVIDER_V4(
        protocolVersion = MULTIMODAL_ADVISOR_PROTOCOL_VERSION,
        toolName = FIND_PRODUCTS,
    ),
    PROVIDER_V5(
        protocolVersion = MULTI_ATTACHMENT_ADVISOR_PROTOCOL_VERSION,
        toolName = FIND_PRODUCTS,
    ),
}

internal const val MAX_LOCAL_TOOL_CALLS_PER_TURN = 3
internal const val MAX_TOOL_PRODUCTS = 5
internal const val MAX_TOOL_QUERIES = 5
internal const val MAX_TOOL_QUERY_CHARS = 200

internal data class AdvisorToolQuery(
    val query: String,
    val limit: Int,
)

internal data class AdvisorToolArguments(
    val storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    val queries: List<AdvisorToolQuery>,
    val providerId: String = OBI_PROVIDER_ID.value,
    val requestedBranch: String? = null,
) {
    val branchId: String
        get() = storeNumber

    constructor(
        query: String,
        storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
        limit: Int,
    ) : this(
        storeNumber = storeNumber,
        queries = listOf(
            AdvisorToolQuery(
                query = query,
                limit = limit,
            ),
        ),
    )

    val query: String
        get() = queries.joinToString(" | ") { it.query }

    val limit: Int
        get() = queries.sumOf { it.limit }
}

internal data class AdvisorSearchAction(
    val query: String,
    val storeNumber: String,
    val reportedTotalCount: Int,
)

internal data class AdvisorProductRef(
    val storeNumber: String,
    val obik: String,
    val providerId: String = OBI_PROVIDER_ID.value,
) {
    val branchId: String
        get() = storeNumber

    val productId: String
        get() = obik

    val key: VerifiedProductKey
        get() = VerifiedProductKey(
            storeNumber = branchId,
            obik = productId,
            providerId = providerId,
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
    val centralStock: Int? = null,
    val price: BigDecimal?,
    val brand: String? = null,
    val productId: String = obik,
    val articleNumber: String? = null,
    val priceScope: String? = null,
    val shortDescription: String? = null,
    val technicalFacts: List<AdvisorTechnicalFact> = emptyList(),
)

internal enum class AdvisorQueryResultStatus {
    VERIFIED,
    NOT_FOUND,
    UNAVAILABLE,
}

internal data class AdvisorVerifiedQueryResult(
    val query: String,
    val status: AdvisorQueryResultStatus,
    val products: List<AdvisorVerifiedProduct>,
)

internal data class AdvisorVerifiedToolResult(
    val storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    val results: List<AdvisorVerifiedQueryResult>,
    val providerId: String = OBI_PROVIDER_ID.value,
) {
    val branchId: String
        get() = storeNumber

    constructor(
        query: String,
        storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
        products: List<AdvisorVerifiedProduct>,
    ) : this(
        storeNumber = storeNumber,
        results = listOf(
            AdvisorVerifiedQueryResult(
                query = query,
                status = if (products.isEmpty()) {
                    AdvisorQueryResultStatus.NOT_FOUND
                } else {
                    AdvisorQueryResultStatus.VERIFIED
                },
                products = products,
            ),
        ),
    )

    val query: String
        get() = results.joinToString(" | ") { it.query }

    val products: List<AdvisorVerifiedProduct>
        get() = results.flatMap { it.products }
}

internal enum class AdvisorRequestType {
    START,
    MESSAGE,
    CONTINUE,
}

internal data class AdvisorWebSource(
    val title: String,
    val url: String,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
)

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

internal data class AdvisorLocationArguments(
    val providerId: String,
    val productId: String,
    val locations: List<String>,
)

internal data class AdvisorLocationEntry(
    val branchId: String,
    val name: String,
    val stock: Int?,
)

internal data class AdvisorLocationEvidence(
    val providerId: String,
    val productId: String?,
    val status: String,
    val reason: String?,
    val coverage: String,
    val checkedIds: List<String>,
    val returnedIds: List<String>,
    val missingIds: List<String>,
    val locations: List<AdvisorLocationEntry>,
    val verifiedAtMillis: Long?,
    val centralStock: Int?,
)

internal sealed interface AdvisorToolContinuation {
    data class Locations(val evidence: AdvisorLocationEvidence) : AdvisorToolContinuation

    data class Verified(
        val result: AdvisorVerifiedToolResult,
    ) : AdvisorToolContinuation

    data class RejectedStore(
        val queries: List<AdvisorToolQuery>,
        val storeNumber: String,
        val providerId: String = OBI_PROVIDER_ID.value,
    ) : AdvisorToolContinuation {
        constructor(
            query: String,
            storeNumber: String,
        ) : this(
            queries = listOf(
                AdvisorToolQuery(
                    query = query,
                    limit = 1,
                ),
            ),
            storeNumber = storeNumber,
        )

        val query: String
            get() = queries.joinToString(" | ") { it.query }
    }

    data class LocalToolLimitReached(
        val queries: List<AdvisorToolQuery>,
        val storeNumber: String,
        val providerId: String = OBI_PROVIDER_ID.value,
    ) : AdvisorToolContinuation {
        constructor(
            query: String,
            storeNumber: String,
        ) : this(
            queries = listOf(
                AdvisorToolQuery(
                    query = query,
                    limit = 1,
                ),
            ),
            storeNumber = storeNumber,
        )

        val query: String
            get() = queries.joinToString(" | ") { it.query }
    }
}

internal sealed interface AdvisorProxyResult {
    data class LocationToolRequest(
        val responseId: String,
        val callId: String,
        val arguments: AdvisorLocationArguments,
        val webSearchCalls: Long = 0,
        val usage: AdvisorUsage? = null,
    ) : AdvisorProxyResult

    data class Answer(
        val responseId: String,
        val text: String,
        val productRefs: List<AdvisorProductRef>,
        val sources: List<AdvisorWebSource> = emptyList(),
        val webSearchCalls: Long = 0,
        val usage: AdvisorUsage? = null,
    ) : AdvisorProxyResult

    data class ToolRequest(
        val responseId: String,
        val callId: String,
        val arguments: AdvisorToolArguments,
        val webSearchCalls: Long = 0,
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

internal data class AdvisorFailureDiagnostic(
    val kind: AdvisorProxyFailureKind,
    val httpStatus: Int? = null,
    val proxyErrorCode: String? = null,
    val endpoint: String? = null,
    val traceId: String? = null,
) {
    fun userCode(): String? =
        listOfNotNull(
            httpStatus?.toString(),
            proxyErrorCode,
        ).takeIf { it.isNotEmpty() }
            ?.joinToString("/")

    fun reportValue(): String =
        buildList {
            add("kind=${kind.name}")
            httpStatus?.let { add("http=$it") }
            proxyErrorCode?.let { add("proxy=$it") }
            endpoint?.let { add("endpoint=$it") }
            advisorTraceIdOrNull(traceId)?.let { add("trace=$it") }
        }.joinToString(" ")
}

internal sealed interface AdvisorProxyCallResult {
    data class Success(
        val result: AdvisorProxyResult,
        val traceId: String? = null,
    ) : AdvisorProxyCallResult

    data class Failure(
        val kind: AdvisorProxyFailureKind,
        val httpStatus: Int? = null,
        val proxyErrorCode: String? = null,
        val endpoint: String? = null,
        val traceId: String? = null,
    ) : AdvisorProxyCallResult {
        fun diagnosticOrNull(): AdvisorFailureDiagnostic? =
            if (
                httpStatus == null &&
                proxyErrorCode == null &&
                endpoint == null &&
                traceId == null
            ) {
                null
            } else {
                AdvisorFailureDiagnostic(
                    kind = kind,
                    httpStatus = httpStatus,
                    proxyErrorCode = proxyErrorCode,
                    endpoint = endpoint,
                    traceId = advisorTraceIdOrNull(traceId),
                )
            }
    }
}

internal sealed interface AdvisorToolExecutionResult {
    data class Success(
        val result: AdvisorVerifiedToolResult,
        val snapshots: List<VerifiedProductSnapshot>,
        val searchActions: List<AdvisorSearchAction> = emptyList(),
    ) : AdvisorToolExecutionResult

    data object UnsupportedStore : AdvisorToolExecutionResult
    data object Failure : AdvisorToolExecutionResult
}
