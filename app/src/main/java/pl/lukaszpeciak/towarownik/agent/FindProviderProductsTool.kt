package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProductProviderRegistry
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.ProviderLookupResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderLookupScopeResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderSearchResult

internal class FindProviderProductsTool(
    private val providers: ProductProviderRegistry =
        ProductProviderRegistry.production(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        arguments: AdvisorToolArguments,
    ): AdvisorToolExecutionResult {
        if (!arguments.isValidBatch()) {
            return AdvisorToolExecutionResult.Failure
        }

        return try {
            withContext(ioDispatcher) {
                executeBlocking(arguments)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            AdvisorToolExecutionResult.Failure
        }
    }

    private fun AdvisorToolArguments.isValidBatch(): Boolean =
        providerId.isNotBlank() &&
            storeNumber.isNotBlank() &&
            queries.isNotEmpty() &&
            queries.size <= MAX_TOOL_QUERIES &&
            queries.sumOf { it.limit } <= MAX_TOOL_PRODUCTS &&
            queries.all {
                it.query.isNotBlank() &&
                    it.query.length <= MAX_TOOL_QUERY_CHARS &&
                    it.limit in 1..MAX_TOOL_PRODUCTS
            }

    private fun executeBlocking(
        arguments: AdvisorToolArguments,
    ): AdvisorToolExecutionResult {
        val providerId = runCatching {
            ProviderId(arguments.providerId)
        }.getOrElse {
            return AdvisorToolExecutionResult.Failure
        }
        var branchId = runCatching {
            BranchId(arguments.branchId)
        }.getOrElse {
            return AdvisorToolExecutionResult.UnsupportedStore
        }
        val provider = runCatching {
            providers.resolve(providerId)
        }.getOrElse {
            return AdvisorToolExecutionResult.Failure
        }

        arguments.requestedBranch?.let { requested ->
            branchId = when (val branches = provider.branches()) {
                is pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult.Available -> {
                    val normalized = requested.normalizedBranchName()
                    val matches = branches.branches.filter {
                        it.name.normalizedBranchName() == normalized
                    }
                    if (matches.size != 1) {
                        return AdvisorToolExecutionResult.UnsupportedStore
                    }
                    matches.single().branchId
                }
                is pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult.Unavailable ->
                    return AdvisorToolExecutionResult.Failure
            }
        }

        val lookup = when (val scope = provider.openLookupScope(branchId)) {
            is ProviderLookupScopeResult.Available -> scope.lookup
            ProviderLookupScopeResult.InvalidBranch ->
                return AdvisorToolExecutionResult.UnsupportedStore
            is ProviderLookupScopeResult.Unavailable ->
                return AdvisorToolExecutionResult.Failure
        }

        val groupedResults = mutableListOf<AdvisorVerifiedQueryResult>()
        val snapshots = mutableListOf<VerifiedProductSnapshot>()
        val searchActions = mutableListOf<AdvisorSearchAction>()

        arguments.queries.forEach { requested ->
            val search = try {
                provider.search(
                    query = requested.query,
                    maxResults = requested.limit,
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                groupedResults += unavailable(requested.query)
                return@forEach
            }

            when (search) {
                ProviderSearchResult.NotFound -> {
                    groupedResults += notFound(requested.query)
                }

                is ProviderSearchResult.Unavailable -> {
                    groupedResults += unavailable(requested.query)
                }

                is ProviderSearchResult.Candidates -> {
                    val boundedCandidateCount = minOf(
                        requested.limit,
                        search.items.size,
                    )
                    val reportedTotal = search.reportedTotalCount
                    if (
                        providerId == OBI_PROVIDER_ID &&
                        requested.limit > 1 &&
                        reportedTotal != null &&
                        reportedTotal > boundedCandidateCount
                    ) {
                        searchActions += AdvisorSearchAction(
                            query = requested.query,
                            storeNumber = branchId.value,
                            reportedTotalCount = reportedTotal,
                        )
                    }

                    val verified = mutableListOf<AdvisorVerifiedProduct>()
                    var lookupFailed = false

                    search.items
                        .take(requested.limit)
                        .forEach { candidate ->
                            when (
                                val lookup = try {
                                    lookup(candidate.ref)
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (_: Exception) {
                                    lookupFailed = true
                                    return@forEach
                                }
                            ) {
                                is ProviderLookupResult.Found -> {
                                    val product = lookup.product
                                    verified += product.toAdvisorVerifiedProduct(requested.query)
                                    snapshots += product.toSnapshot(now())
                                }

                                is ProviderLookupResult.InvalidBranch ->
                                    return AdvisorToolExecutionResult
                                        .UnsupportedStore

                                is ProviderLookupResult.WrongProvider,
                                is ProviderLookupResult.InvalidProductId,
                                is ProviderLookupResult.Unavailable ->
                                    lookupFailed = true
                            }
                        }

                    groupedResults += when {
                        verified.isNotEmpty() ->
                            AdvisorVerifiedQueryResult(
                                query = requested.query,
                                status = AdvisorQueryResultStatus.VERIFIED,
                                products = verified,
                            )

                        lookupFailed -> unavailable(requested.query)
                        else -> notFound(requested.query)
                    }
                }
            }
        }

        return AdvisorToolExecutionResult.Success(
            result = AdvisorVerifiedToolResult(
                providerId = providerId.value,
                storeNumber = branchId.value,
                results = groupedResults,
            ),
            snapshots = snapshots,
            searchActions = searchActions,
        )
    }

    private fun notFound(query: String): AdvisorVerifiedQueryResult =
        AdvisorVerifiedQueryResult(
            query = query,
            status = AdvisorQueryResultStatus.NOT_FOUND,
            products = emptyList(),
        )

    private fun pl.lukaszpeciak.towarownik.product.provider.ProviderProduct
        .toSnapshot(verifiedAt: Long) = VerifiedProductSnapshot(
        obik = ref.productId,
        productId = ref.productId,
        providerId = ref.providerId.value,
        branchId = branchId.value,
        articleNumber = articleNumber,
        name = name,
        stock = stock,
        centralStock = centralStock,
        grossPrice = grossPrice,
        productUrl = productUrl,
        primaryImageUrl = primaryImageUrl,
        verifiedAt = verifiedAt,
        storeNumber = branchId.value,
        priceScope = priceScope,
    )

    private fun unavailable(query: String): AdvisorVerifiedQueryResult =
        AdvisorVerifiedQueryResult(
            query = query,
            status = AdvisorQueryResultStatus.UNAVAILABLE,
            products = emptyList(),
        )

    private fun String.normalizedBranchName(): String =
        java.text.Normalizer.normalize(trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("\\s+"), " ")
}
