package pl.lukaszpeciak.towarownik.agent

import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.WorkingProfile

class CoreV2AndroidToolBridgeTest {
    private val profile = WorkingProfile(ProviderId("kwant-pl"), BranchId("205"))
    private val scope = CoreV2AndroidToolBridge.Scope.currentOnly(profile)
    private fun request(provider: String = "kwant-pl", branch: String = "205") =
        CoreV2AndroidToolBridge.Request(provider, branch, "Hager", 1)

    @Test fun unauthorizedProviderAndBranchNeverExecute() = runBlocking {
        var executions = 0
        val bridge = CoreV2AndroidToolBridge { executions++; AdvisorToolExecutionResult.Failure }
        assertEquals(CoreV2AndroidToolBridge.Status.REJECTED,
            bridge.execute(scope, request(provider = "obi-pl")).status)
        assertEquals(CoreV2AndroidToolBridge.Status.REJECTED,
            bridge.execute(scope, request(branch = "204")).status)
        assertEquals(0, executions)
    }

    @Test fun malformedRequestNeverExecutes() = runBlocking {
        var executions = 0
        val bridge = CoreV2AndroidToolBridge { executions++; AdvisorToolExecutionResult.Failure }
        val result = bridge.execute(scope, CoreV2AndroidToolBridge.Request("kwant-pl", "205", "", 99))
        assertEquals(CoreV2AndroidToolBridge.Status.REJECTED, result.status)
        assertEquals(0, executions)
    }

    @Test fun verifiedZeroAndNullRemainDistinctAndOnlinePriceScopeRetained() = runBlocking {
        for (stock in listOf(0, null)) {
            val candidate = AdvisorVerifiedProduct(
                obik = "580", productId = "580", name = "Hager", stock = stock,
                price = BigDecimal("15.25"), priceScope = "online",
            )
            val snap = VerifiedProductSnapshot(
                obik = "580", productId = "580", providerId = "kwant-pl",
                branchId = "205", storeNumber = "205", name = "Hager",
                stock = stock, grossPrice = BigDecimal("15.25"),
                priceScope = pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope.ONLINE,
                productUrl = "https://example.invalid/item", verifiedAt = 1234L,
            )
            val bridge = CoreV2AndroidToolBridge {
                AdvisorToolExecutionResult.Success(
                    AdvisorVerifiedToolResult(
                        storeNumber = "205", providerId = "kwant-pl",
                        results = listOf(AdvisorVerifiedQueryResult("Hager", AdvisorQueryResultStatus.VERIFIED, listOf(candidate))),
                    ), listOf(snap),
                )
            }
            val result = bridge.execute(scope, request())
            assertEquals(CoreV2AndroidToolBridge.Status.SUCCESS, result.status)
            assertEquals(stock, result.products.single().stock)
            assertEquals("online", result.products.single().priceScope)
            assertEquals("15.25", result.products.single().price)
        }
    }

    @Test fun crossBranchOrUnmatchedSnapshotNeverYieldsProducts() = runBlocking {
        for (wrongBranch in listOf("204", "205")) {
            val snapshot = VerifiedProductSnapshot(
                obik = "580", productId = if (wrongBranch == "205") "fake" else "580",
                providerId = "kwant-pl", branchId = wrongBranch, storeNumber = wrongBranch,
                name = "Hager", stock = 5, grossPrice = null,
                productUrl = "https://example.invalid/item", verifiedAt = 1234L,
            )
            val bridge = CoreV2AndroidToolBridge {
                AdvisorToolExecutionResult.Success(
                    AdvisorVerifiedToolResult(
                        providerId = "kwant-pl", storeNumber = "205",
                        results = listOf(AdvisorVerifiedQueryResult(
                            "Hager", AdvisorQueryResultStatus.VERIFIED,
                            listOf(AdvisorVerifiedProduct("580", "Hager", stock = 5, price = null)),
                        )),
                    ), listOf(snapshot),
                )
            }
            val result = bridge.execute(scope, request())
            assertEquals(CoreV2AndroidToolBridge.Status.FAILURE, result.status)
            assertEquals(0, result.products.size)
        }
    }

    @Test fun noResultAndUnavailableRemainDifferent() = runBlocking {
        for ((status, expected) in listOf(
            AdvisorQueryResultStatus.NOT_FOUND to CoreV2AndroidToolBridge.Status.NOT_FOUND,
            AdvisorQueryResultStatus.UNAVAILABLE to CoreV2AndroidToolBridge.Status.UNAVAILABLE,
        )) {
            val bridge = CoreV2AndroidToolBridge {
                AdvisorToolExecutionResult.Success(
                    AdvisorVerifiedToolResult(providerId = "kwant-pl", storeNumber = "205",
                        results = listOf(AdvisorVerifiedQueryResult("Hager", status, emptyList()))),
                    emptyList(),
                )
            }
            val result = bridge.execute(scope, request())
            assertEquals(expected, result.status)
            assertEquals(emptyList<CoreV2AndroidToolBridge.Product>(), result.products)
        }
    }
}
