package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.product.ProductLookupRepository
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchRepository
import pl.lukaszpeciak.towarownik.product.ProductSearchResult

internal class FindAvailableObi075Tool(
    private val searchProducts: (String) -> ProductSearchResult =
        ProductSearchRepository()::search,
    private val lookupObik: (String) -> ProductLookupResult =
        ProductLookupRepository()::lookupObik,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun execute(
        arguments: AdvisorToolArguments,
    ): AdvisorToolExecutionResult {
        if (
            arguments.query.isBlank() ||
            arguments.limit !in 1..MAX_TOOL_PRODUCTS
        ) {
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

    private fun executeBlocking(
        arguments: AdvisorToolArguments,
    ): AdvisorToolExecutionResult {
        return when (val search = searchProducts(arguments.query)) {
            ProductSearchResult.NotFound -> {
                AdvisorToolExecutionResult.Success(
                    AdvisorVerifiedToolResult(
                        query = arguments.query,
                        products = emptyList(),
                    ),
                )
            }

            is ProductSearchResult.Unavailable -> {
                AdvisorToolExecutionResult.Failure
            }

            is ProductSearchResult.Candidates -> {
                val verified = mutableListOf<AdvisorVerifiedProduct>()
                var lookupFailed = false

                search.items
                    .take(arguments.limit.coerceAtMost(MAX_TOOL_PRODUCTS))
                    .forEach { candidate ->
                        val lookup = try {
                            lookupObik(candidate.obik)
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
                                    stock = product.stock,
                                    price = product.grossPrice,
                                )
                            }

                            is ProductLookupResult.InvalidObik,
                            is ProductLookupResult.Unavailable -> {
                                lookupFailed = true
                            }
                        }
                    }

                when {
                    verified.isNotEmpty() -> {
                        AdvisorToolExecutionResult.Success(
                            AdvisorVerifiedToolResult(
                                query = arguments.query,
                                products = verified,
                            ),
                        )
                    }

                    lookupFailed -> {
                        AdvisorToolExecutionResult.Failure
                    }

                    else -> {
                        AdvisorToolExecutionResult.Success(
                            AdvisorVerifiedToolResult(
                                query = arguments.query,
                                products = emptyList(),
                            ),
                        )
                    }
                }
            }
        }
    }
}
