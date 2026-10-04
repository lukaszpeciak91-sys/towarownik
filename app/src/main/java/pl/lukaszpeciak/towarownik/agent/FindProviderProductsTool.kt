package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProductProviderFailure
import pl.lukaszpeciak.towarownik.product.provider.ProductProviderRegistry
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranchResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.ProviderLookupResult
import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope
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
        val branchId = runCatching {
            BranchId(arguments.branchId)
        }.getOrElse {
            return AdvisorToolExecutionResult.UnsupportedStore
        }
        val provider = runCatching {
            providers.resolve(providerId)
        }.getOrElse {
            return AdvisorToolExecutionResult.Failure
        }

        when (val branches = provider.branches()) {
            is ProviderBranchResult.Available -> {
                if (branches.branches.none { it.branchId == branchId }) {
                    return AdvisorToolExecutionResult.UnsupportedStore
                }
            }
            is ProviderBranchResult.Unavailable ->
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
                                    provider.lookup(
                                        ref = candidate.ref,
                                        branchId = branchId,
                                    )
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (_: Exception) {
                                    lookupFailed = true
                                    return@forEach
                                }
                            ) {
                                is ProviderLookupResult.Found -> {
                                    val product = lookup.product
                                    verified += AdvisorVerifiedProduct(
                                        obik = product.ref.productId,
                                        productId = product.ref.productId,
                                        articleNumber = product.articleNumber,
                                        priceScope = product.priceScope,
                                        name = product.name,
                                        brand = product.brand,
                                        shortDescription =
                                            product.shortDescription,
                                        technicalFacts =
                                            product.technicalFacts.map {
                                                AdvisorTechnicalFact(
                                                    label = it.label,
                                                    value = it.value,
                                                )
                                            },
                                        stock = product.stock,
                                        price = product.grossPrice,
                                        priceScope = when (product.priceScope) {
                                            ProviderPriceScope.BRANCH -> "branch"
                                            ProviderPriceScope.ONLINE -> "online"
                                            null -> null
                                        },
                                    )
                                    snapshots += VerifiedProductSnapshot(
                                        obik = product.ref.productId,
                                        productId = product.ref.productId,
                                        providerId = product.ref.providerId.value,
                                        branchId = product.branchId.value,
                                        articleNumber = product.articleNumber,
                                        name = product.name,
                                        stock = product.stock,
                                        grossPrice = product.grossPrice,
                                        productUrl = product.productUrl,
                                        primaryImageUrl =
                                            product.primaryImageUrl,
                                        verifiedAt = now(),
                                        storeNumber = product.branchId.value,
                                    )
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

    private fun unavailable(query: String): AdvisorVerifiedQueryResult =
        AdvisorVerifiedQueryResult(
            query = query,
            status = AdvisorQueryResultStatus.UNAVAILABLE,
            products = emptyList(),
        )
}
