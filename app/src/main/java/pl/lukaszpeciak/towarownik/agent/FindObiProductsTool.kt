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
        if (
            arguments.query.isBlank() ||
            arguments.limit !in 1..MAX_TOOL_PRODUCTS
        ) {
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

    private fun executeBlocking(
        arguments: AdvisorToolArguments,
    ): AdvisorToolExecutionResult {
        return when (val search = searchProducts(arguments.query)) {
            ProductSearchResult.NotFound -> success(
                arguments = arguments,
                products = emptyList(),
                snapshots = emptyList(),
            )

            is ProductSearchResult.Unavailable ->
                AdvisorToolExecutionResult.Failure

            is ProductSearchResult.Candidates -> {
                val verified = mutableListOf<AdvisorVerifiedProduct>()
                val snapshots = mutableListOf<VerifiedProductSnapshot>()
                var lookupFailed = false

                search.items
                    .take(arguments.limit.coerceAtMost(MAX_TOOL_PRODUCTS))
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

                when {
                    verified.isNotEmpty() -> success(
                        arguments = arguments,
                        products = verified,
                        snapshots = snapshots,
                    )

                    lookupFailed ->
                        AdvisorToolExecutionResult.Failure

                    else -> success(
                        arguments = arguments,
                        products = emptyList(),
                        snapshots = emptyList(),
                    )
                }
            }
        }
    }

    private fun success(
        arguments: AdvisorToolArguments,
        products: List<AdvisorVerifiedProduct>,
        snapshots: List<VerifiedProductSnapshot>,
    ): AdvisorToolExecutionResult.Success =
        AdvisorToolExecutionResult.Success(
            result = AdvisorVerifiedToolResult(
                query = arguments.query,
                storeNumber = arguments.storeNumber,
                products = products,
            ),
            snapshots = snapshots,
        )
}
