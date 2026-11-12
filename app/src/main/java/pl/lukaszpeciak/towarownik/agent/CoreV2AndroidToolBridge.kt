package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.WorkingProfile

/**
 * Isolated Core V2 local bridge. No production controller or Worker route imports it.
 *
 * [scope] MUST be built by trusted application state/policy, not deserialized from
 * OpenAI arguments or inferred from USER text. Only the current WorkingProfile
 * branch is admitted by [currentOnly]; alternative branches require an independently
 * granted scope from a future authenticated transport.
 */
internal class CoreV2AndroidToolBridge(
    private val executeProvider: suspend (AdvisorToolArguments) -> AdvisorToolExecutionResult,
) {
    data class Scope private constructor(
        val providerId: String,
        val currentBranchId: String,
        val allowedBranchIds: Set<String>,
    ) {
        companion object {
            fun currentOnly(profile: WorkingProfile): Scope =
                Scope(profile.providerId.value, profile.branchId.value, setOf(profile.branchId.value))
        }

        internal fun permits(request: Request): Boolean =
            request.providerId == providerId &&
                request.branchId in allowedBranchIds &&
                providerId in setOf("obi-pl", "kwant-pl")
    }

    data class Request(
        val providerId: String,
        val branchId: String,
        val query: String,
        val limit: Int,
    )

    enum class Status { SUCCESS, NOT_FOUND, UNAVAILABLE, REJECTED, UNKNOWN, FAILURE }

    data class Product(
        val providerId: String,
        val branchId: String,
        val productId: String,
        val name: String,
        val stock: Int?,
        val price: String?,
        val priceScope: String?,
        val verifiedAtMillis: Long,
    )

    data class Result(
        val status: Status,
        val providerId: String,
        val branchId: String,
        val query: String,
        val products: List<Product> = emptyList(),
        val reason: String? = null,
        val coverage: String = "requested_subset",
    )

    suspend fun execute(scope: Scope, request: Request): Result {
        fun rejected(reason: String) =
            Result(Status.REJECTED, scope.providerId, scope.currentBranchId, request.query, reason = reason)
        if (!scope.permits(request)) return rejected("unauthorized_provider_or_branch")
        if (request.query.isBlank() || request.query.length > MAX_TOOL_QUERY_CHARS ||
            request.limit !in 1..MAX_TOOL_PRODUCTS) return rejected("invalid_arguments")
        val arguments = AdvisorToolArguments(
            storeNumber = request.branchId,
            queries = listOf(AdvisorToolQuery(request.query, request.limit)),
            providerId = request.providerId,
        )
        val execution = try {
            executeProvider(arguments)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return Result(Status.UNAVAILABLE, request.providerId, request.branchId,
                request.query, reason = "provider_execution_unavailable", coverage = "unknown")
        }
        return when (execution) {
            is AdvisorToolExecutionResult.Success -> {
                val wire = execution.result
                if (wire.providerId != request.providerId || wire.branchId != request.branchId ||
                    wire.results.size != 1 || wire.results.single().query != request.query) {
                    return Result(Status.FAILURE, request.providerId, request.branchId,
                        request.query, reason = "provider_result_identity_mismatch", coverage = "unknown")
                }
                val group = wire.results.single()
                val snapshots = execution.snapshots
                val verified = group.products
                if (verified.size > request.limit || snapshots.size > request.limit ||
                    (group.status == AdvisorQueryResultStatus.VERIFIED && verified.isEmpty()) ||
                    (group.status != AdvisorQueryResultStatus.VERIFIED &&
                        (verified.isNotEmpty() || snapshots.isNotEmpty()))) {
                    return Result(Status.FAILURE, request.providerId, request.branchId,
                        request.query, reason = "inconsistent_provider_evidence", coverage = "unknown")
                }
                if (group.status != AdvisorQueryResultStatus.VERIFIED) {
                    return Result(
                        when (group.status) {
                            AdvisorQueryResultStatus.NOT_FOUND -> Status.NOT_FOUND
                            AdvisorQueryResultStatus.UNAVAILABLE -> Status.UNAVAILABLE
                            else -> Status.UNKNOWN
                        },
                        request.providerId, request.branchId, request.query,
                        coverage = if (group.status == AdvisorQueryResultStatus.UNAVAILABLE) "unknown" else "requested_subset",
                    )
                }
                val snapshotsById = snapshots.associateBy { it.effectiveProductId }
                if (snapshotsById.size != snapshots.size || snapshots.size != verified.size ||
                    verified.map { it.productId }.toSet().size != verified.size) {
                    return Result(Status.FAILURE, request.providerId, request.branchId,
                        request.query, reason = "duplicate_or_missing_provider_proof", coverage = "unknown")
                }
                val products = verified.map { candidate ->
                    val snapshot: VerifiedProductSnapshot = snapshotsById[candidate.productId]
                        ?: return Result(Status.FAILURE, request.providerId, request.branchId,
                            request.query, reason = "unproven_product_identity", coverage = "unknown")
                    if (snapshot.providerId != request.providerId ||
                        snapshot.effectiveBranchId != request.branchId ||
                        snapshot.effectiveProductId != candidate.productId ||
                        snapshot.stock != candidate.stock ||
                        (snapshot.priceScope?.name?.lowercase() != candidate.priceScope) ||
                        snapshot.grossPrice != candidate.price ||
                        snapshot.verifiedAt <= 0L) {
                        return Result(Status.FAILURE, request.providerId, request.branchId,
                            request.query, reason = "unproven_product_facts", coverage = "unknown")
                    }
                    Product(
                        providerId = snapshot.providerId,
                        branchId = snapshot.branchId,
                        productId = snapshot.effectiveProductId,
                        name = snapshot.name.take(300),
                        stock = snapshot.stock,
                        price = snapshot.grossPrice?.toPlainString(),
                        priceScope = snapshot.priceScope?.name?.lowercase(),
                        verifiedAtMillis = snapshot.verifiedAt,
                    )
                }
                Result(Status.SUCCESS, request.providerId, request.branchId, request.query,
                    products = products.distinctBy { it.productId })
            }
            AdvisorToolExecutionResult.UnsupportedStore ->
                rejected("invalid_provider_branch")
            else -> Result(Status.UNAVAILABLE, request.providerId, request.branchId,
                request.query, reason = "provider_unavailable", coverage = "unknown")
        }
    }
}
