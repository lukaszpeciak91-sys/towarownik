package pl.lukaszpeciak.towarownik

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiStoreContextBehaviorTest {
    @Test
    fun `manual profile context reset invalidates and cancels stale search`() {
        val source = mainActivitySource()
        val body = functionBody(
            source = source,
            signature = "fun clearManualProfileContext() {",
        )

        assertTrue(body.contains("manualRequestGuard.invalidate()"))
        assertTrue(body.contains("manualJob?.cancel()"))
        assertTrue(body.contains("manualJob = null"))
        assertTrue(body.contains("manualState = ManualSearchUiState.Idle"))
    }

    @Test
    fun `conversation and global profile context changes clear manual search state`() {
        val source = mainActivitySource()

        assertTrue(
            functionBody(source, "fun newAdvisorCase() {")
                .contains("clearManualProfileContext()"),
        )
        assertTrue(
            functionBody(source, "fun openConversation(conversationId: Long) {")
                .contains("clearManualProfileContext()"),
        )
        assertTrue(
            functionBody(source, "fun deleteConversation(conversationId: Long) {")
                .contains("clearManualProfileContext()"),
        )
        assertTrue(
            functionBody(source, "fun applyGlobalWorkingProfile(")
                .contains("clearManualProfileContext()"),
        )
        assertFalse(source.contains("fun selectConversationStore("))
    }

    @Test
    fun `stale manual completion cannot restore result after profile change`() {
        val source = mainActivitySource()

        listOf(
            functionBody(source, "fun submitManualSearch() {"),
            functionBody(
                source,
                "fun selectManualResult(item: ManualSearchResultItem) {",
            ),
            functionBody(
                source,
                "fun showMoreManualResults() {",
            ),
        ).forEach { body ->
            assertTrue(body.contains("manualRequestGuard.invalidate()"))
            assertTrue(body.contains("manualRequestGuard.isTokenCurrent(generation)"))
            assertTrue(body.contains("manualWorkingProfile == profile"))
        }
    }

    @Test
    fun `normal manual search uses global profile while historical OBI action keeps its own branch`() {
        val source = mainActivitySource()
        val openManual = functionBody(
            source,
            "fun openManualSearch() {",
        )
        val openAction = functionBody(
            source,
            "fun openAdvisorSearchAction(",
        )
        val manualSurface = source
            .substringAfter("AppSurface.MANUAL_SEARCH -> {")
            .substringBefore("AppSurface.SETTINGS -> {")

        assertTrue(
            openManual.contains(
                "manualWorkingProfile = globalWorkingProfile",
            ),
        )
        assertTrue(
            openAction.contains(
                "providerId = OBI_PROVIDER_ID",
            ),
        )
        assertTrue(
            openAction.contains(
                "branchId = BranchId(request.storeNumber)",
            ),
        )
        assertFalse(
            openAction.contains(
                "globalWorkingProfile =",
            ),
        )
        assertTrue(
            manualSurface.contains(
                "workingProfile = manualWorkingProfile",
            ),
        )
    }

    @Test
    fun `conversation profile has no mutable store persistence job`() {
        val source = mainActivitySource()
        val cancelBody = suspendFunctionBody(
            source = source,
            signature = "suspend fun cancelAndRecoverActiveTurn(",
        )
        val repositorySource = File(
            projectRoot(),
            "app/src/main/java/pl/lukaszpeciak/towarownik/conversation/ConversationRepository.kt",
        ).readText()

        assertFalse(source.contains("storePersistJob"))
        assertFalse(repositorySource.contains("fun updateStoreNumber("))
        assertTrue(cancelBody.contains("advisorJob?.cancelAndJoin()"))
        assertTrue(cancelBody.contains("draftPersistJob?.cancelAndJoin()"))
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
