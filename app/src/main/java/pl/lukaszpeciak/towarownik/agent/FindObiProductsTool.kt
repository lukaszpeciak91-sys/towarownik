package pl.lukaszpeciak.towarownik.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lukaszpeciak.towarownik.product.ProductLookupFailure
import pl.lukaszpeciak.towarownik.product.ProductLookupRepository
import pl.lukaszpeciak.towarownik.product.ProductLookupResult
import pl.lukaszpeciak.towarownik.product.ProductSearchInput
import pl.lukaszpeciak.towarownik.product.ProductSearchRepository
import pl.lukaszpeciak.towarownik.product.ProductSearchResult
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.classifyProductSearchInput
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
        val searchActions = mutableListOf<AdvisorSearchAction>()

        arguments.queries.forEach { requested ->
            val classified = classifyExactToolObik(requested.query)
            if (classified != null) {
                groupedResults += verifyExactObik(
                    query = requested.query,
                    obik = classified.value,
                    storeNumber = arguments.storeNumber,
                    snapshots = snapshots,
                )
                return@forEach
            }
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
                    val boundedCandidateCount = minOf(
                        requested.limit,
                        search.items.size,
                    )
                    if (
                        requested.limit > 1 &&
                        search.reportedTotalCount > boundedCandidateCount
                    ) {
                        searchActions += AdvisorSearchAction(
                            query = requested.query,
                            storeNumber = arguments.storeNumber,
                            reportedTotalCount = search.reportedTotalCount,
                        )
                    }

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
            searchActions = searchActions,
        )
    }

    private fun classifyExactToolObik(
        rawQuery: String,
    ): ProductSearchInput.Obik? {
        val classified = classifyProductSearchInput(rawQuery)
        if (classified is ProductSearchInput.Obik) {
            return classified
        }

        val normalized =
            pl.lukaszpeciak.towarownik.product
                .normalizeProductSearchInput(rawQuery)
        val labeled = EXACT_OBIK_TOOL_QUERY.matchEntire(normalized)
            ?: return null
        return classifyProductSearchInput(
            labeled.groupValues[1],
        ) as? ProductSearchInput.Obik
    }

    private fun verifyExactObik(
        query: String,
        obik: String,
        storeNumber: String,
        snapshots: MutableList<VerifiedProductSnapshot>,
    ): AdvisorVerifiedQueryResult {
        val lookup = try {
            lookupObik(obik, storeNumber)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            return unavailable(query)
        }
        return when (lookup) {
            is ProductLookupResult.Found -> {
                val product = lookup.product
                snapshots += product.toVerifiedProductSnapshot(now())
                AdvisorVerifiedQueryResult(
                    query = query,
                    status = AdvisorQueryResultStatus.VERIFIED,
                    products = listOf(product.toAdvisorVerifiedProduct()),
                )
            }
            is ProductLookupResult.InvalidStore -> unavailable(query)
            is ProductLookupResult.InvalidObik -> notFound(query)
            is ProductLookupResult.Unavailable ->
                when (lookup.failure) {
                    ProductLookupFailure.NOT_FOUND -> notFound(query)
                    ProductLookupFailure.NETWORK,
                    ProductLookupFailure.DATA -> unavailable(query)
                }
        }
    }

    private fun pl.lukaszpeciak.towarownik.product.LocalProduct
        .toAdvisorVerifiedProduct(): AdvisorVerifiedProduct =
        AdvisorVerifiedProduct(
            obik = obik,
            name = name,
            brand = brand,
            shortDescription = shortDescription,
            technicalFacts = technicalFacts.map {
                AdvisorTechnicalFact(it.label, it.value)
            },
            stock = stock,
            price = grossPrice,
        )

    private fun notFound(query: String): AdvisorVerifiedQueryResult =
        AdvisorVerifiedQueryResult(
            query = query,
            status = AdvisorQueryResultStatus.NOT_FOUND,
            products = emptyList(),
        )

    private companion object {
        val EXACT_OBIK_TOOL_QUERY =
            Regex("""(?i)^OBIK\s*:?\s*(\d{7})$""")
    }

    private fun unavailable(query: String): AdvisorVerifiedQueryResult =
        AdvisorVerifiedQueryResult(
            query = query,
            status = AdvisorQueryResultStatus.UNAVAILABLE,
            products = emptyList(),
        )
}
