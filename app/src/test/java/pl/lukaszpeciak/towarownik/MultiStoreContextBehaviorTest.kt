package pl.lukaszpeciak.towarownik

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiStoreContextBehaviorTest {
    @Test
    fun `manual store context reset invalidates and cancels stale search`() {
        val source = mainActivitySource()
        val body = functionBody(
            source = source,
            signature = "fun clearManualStoreContext() {",
        )

        assertTrue(body.contains("manualRequestGuard.invalidate()"))
        assertTrue(body.contains("manualJob?.cancel()"))
        assertTrue(body.contains("manualJob = null"))
        assertTrue(body.contains("manualState = ManualSearchUiState.Idle"))
    }

    @Test
    fun `conversation and store context changes clear manual search state`() {
        val source = mainActivitySource()

        assertTrue(
            functionBody(source, "fun newAdvisorCase() {")
                .contains("clearManualStoreContext()"),
        )
        assertTrue(
            functionBody(source, "fun openConversation(conversationId: Long) {")
                .contains("clearManualStoreContext()"),
        )
        assertTrue(
            functionBody(source, "fun deleteConversation(conversationId: Long) {")
                .contains("clearManualStoreContext()"),
        )
        assertTrue(
            functionBody(source, "fun selectConversationStore(storeNumber: String) {")
                .contains("clearManualStoreContext()"),
        )
    }

    @Test
    fun `stale manual completion cannot restore result after store change`() {
        val source = mainActivitySource()

        listOf(
            functionBody(source, "fun submitManualSearch() {"),
            functionBody(
                source,
                "fun selectManualResult(item: ManualSearchResultItem) {",
            ),
        ).forEach { body ->
            assertTrue(body.contains("manualRequestGuard.invalidate()"))
            assertTrue(body.contains("manualRequestGuard.isTokenCurrent(generation)"))
            assertTrue(body.contains("selectedStoreNumber == storeNumber"))
        }
    }

    @Test
    fun `pending store persistence completes before leaving conversation`() {
        val source = mainActivitySource()
        val body = suspendFunctionBody(
            source = source,
            signature = "suspend fun cancelAndRecoverActiveTurn(",
        )

        assertTrue(body.contains("storePersistJob?.join()"))
        assertTrue(body.contains("storePersistJob = null"))
        assertFalse(body.contains("storePersistJob?.cancelAndJoin()"))
        assertFalse(body.contains("storePersistJob?.cancel()"))
        assertTrue(body.contains("advisorJob?.cancelAndJoin()"))
        assertTrue(body.contains("draftPersistJob?.cancelAndJoin()"))
    }

    @Test
    fun `legacy search controller has one InvalidStore branch per lookup when`() {
        val source = File(
            projectRoot(),
            "app/src/main/java/pl/lukaszpeciak/towarownik/ProductSearchController.kt",
        ).readText()
        val resolveEan = source
            .substringAfter("private fun resolveEan(")
            .substringBefore("private fun resolveSearch(")

        assertTrue(
            resolveEan.contains(
                "is ProductLookupResult.InvalidStore ->",
            ),
        )
        assertTrue(
            resolveEan.indexOf(
                "is ProductLookupResult.InvalidStore ->",
            ) == resolveEan.lastIndexOf(
                "is ProductLookupResult.InvalidStore ->",
            ),
        )
    }

    private fun mainActivitySource(): String =
        File(
            projectRoot(),
            "app/src/main/java/pl/lukaszpeciak/towarownik/MainActivity.kt",
        ).readText()

    private fun functionBody(
        source: String,
        signature: String,
    ): String =
        source.substringAfter(signature)
            .substringBefore("\n    }")

    private fun suspendFunctionBody(
        source: String,
        signature: String,
    ): String {
        val start = source.indexOf(signature)
        check(start >= 0)
        val nextFunction = source.indexOf("\n    fun ", start)
        check(nextFunction > start)
        return source.substring(start, nextFunction)
    }

    private fun projectRoot(): File {
        var current = File(
            requireNotNull(System.getProperty("user.dir")),
        )
        repeat(3) {
            if (File(current, "app/src/main").isDirectory) {
                return current
            }
            current = current.parentFile ?: return@repeat
        }
        error("Project root not found")
    }
}
