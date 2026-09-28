package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.product.ProductLookupRepository
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchRepository
import pl.lukaszpeciak.towarownik.product.ProductSearchResult
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.isSupportedObiStoreNumber
import pl.lukaszpeciak.towarownik.product.toVerifiedProductSnapshot

internal class FindObiProductsTool(
    private val searchProducts: (String) -> ProductSearchResult =
        ProductSearchRepository()::search,
    private val lookupObik: (String, String) -> ProductLookupResult =
        { obik, storeNumber ->
            ProductLookupRepository().lookupObik(obik, storeNumber)
        },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(
        arguments: AdvisorToolArguments,
    ): AdvisorToolExecutionResult {
        if (!arguments.isValidBatch()) {
            return AdvisorToolExecutionResult.Failure
        }
        if (!isSupportedObiStoreNumber(arguments.storeNumber)) {
            return AdvisorToolExecutionResult.UnsupportedStore
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
        val groupedResults = mutableListOf<AdvisorVerifiedQueryResult>()
        val snapshots = mutableListOf<VerifiedProductSnapshot>()

        arguments.queries.forEach { requested ->
            val search = try {
                searchProducts(requested.query)
            } catch (exception: CancellationException) {
                throw exception
            } catch (_: Exception) {
                groupedResults += unavailable(requested.query)
                return@forEach
            }

            when (search) {
                ProductSearchResult.NotFound -> {
                    groupedResults += notFound(requested.query)
                }

                is ProductSearchResult.Unavailable -> {
                    groupedResults += unavailable(requested.query)
                }

                is ProductSearchResult.Candidates -> {
                    val verified = mutableListOf<AdvisorVerifiedProduct>()
                    var lookupFailed = false

                    search.items
                        .take(requested.limit)
                        .forEach { candidate ->
                            val lookup = try {
                                lookupObik(
                                    candidate.obik,
                                    arguments.storeNumber,
                                )
                            } catch (exception: CancellationException) {
                                throw exception
                            } catch (_: Exception) {
                                lookupFailed = true
                                return@forEach
                            }

                            when (lookup) {
                                is ProductLookupResult.Found -> {
                                    val product = lookup.product
                                    verified += AdvisorVerifiedProduct(
                                        obik = product.obik,
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
                                    )
                                    snapshots +=
                                        product.toVerifiedProductSnapshot(
                                            verifiedAt = now(),
                                        )
                                }

                                is ProductLookupResult.InvalidStore ->
                                    return AdvisorToolExecutionResult.UnsupportedStore

                                is ProductLookupResult.InvalidObik,
                                is ProductLookupResult.Unavailable ->
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
                storeNumber = arguments.storeNumber,
                results = groupedResults,
            ),
            snapshots = snapshots,
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
