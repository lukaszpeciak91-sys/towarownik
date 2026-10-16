package pl.lukaszpeciak.towarownik

import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Test
import pl.lukaszpeciak.towarownik.agent.AdvisorLocationEntry
import pl.lukaszpeciak.towarownik.agent.AdvisorLocationEvidence

class AdvisorResponseReportEvidenceTest {
    private fun evidence(id: String = "580", branch: String = "205") = AdvisorLocationEvidence(
        providerId = "kwant-pl", productId = id, status = "unavailable", reason = "transport",
        coverage = "unknown", checkedIds = listOf(branch), returnedIds = emptyList(), missingIds = listOf(branch),
        locations = listOf(AdvisorLocationEntry(branch, "SECRET_DESCRIPTION_CANARY", null)), verifiedAtMillis = null, centralStock = null,
    )

    @Test fun successiveMessagesAndConversationsRetainTheirOwnImmutableSnapshot() {
        val store = AdvisorResponseReportEvidenceStore()
        store.record(33, 10, listOf(evidence()))
        val pinned = store.get(33, 10)
        store.record(33, 11, listOf(evidence("7027", "204")))
        store.record(34, 10, listOf(evidence("9999", "204")))
        store.record(33, 10, listOf(evidence("8888")))
        assertEquals(pinned, store.get(33, 10))
        assertTrue(store.get(33, 10)!!.contains("product_id=580"))
        assertFalse(store.get(33, 10)!!.contains("7027"))
        assertNull(store.get(34, 11))
        store.clearConversation(34)
        assertNull(store.get(34, 10))
        assertEquals(pinned, store.get(33, 10))
    }

    @Test fun boundedStorageEvictsOldMessagesAndProcessRecreationHasNoFallback() {
        val store = AdvisorResponseReportEvidenceStore()
        repeat(17) { store.record(33, it + 1L, List(4) { evidence() }) }
        assertNull(store.get(33, 1))
        assertEquals(3, Regex("tool=find_product_locations").findAll(store.get(33, 17)!!).count())
        assertNull(AdvisorResponseReportEvidenceStore().get(33, 17))
    }

    @Test fun noDescriptionsUnsafeIdentifiersArbitraryReasonsOrRawJsonAreRetained() {
        val store = AdvisorResponseReportEvidenceStore()
        store.record(33, 10, listOf(evidence().copy(
            reason = "SECRET_REASON_CANARY", coverage = "SECRET_COVERAGE_CANARY",
            checkedIds = listOf("205", "SECRET_ID_CANARY"), authorizedBranchIds = listOf("205", "SECRET_ID_CANARY"),
        )))
        val text = store.get(33, 10)!!
        assertTrue(text.contains("reason=unrecognized"))
        assertFalse(text.contains("SECRET_"))
        assertFalse(text.contains("{"))
        store.record(33, 11, listOf(evidence("SECRET_PRODUCT_CANARY")))
        store.record(33, 12, listOf(evidence().copy(providerId = "obi-pl")))
        assertNull(store.get(33, 11)); assertNull(store.get(33, 12))
    }

    @Test fun concurrentCompletionsCannotOverwriteAnotherMessagesAttribution() {
        val store = AdvisorResponseReportEvidenceStore()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit { repeat(10) { store.record(33, 10, listOf(evidence("580", "205"))) } }
            val second = executor.submit { repeat(10) { store.record(33, 11, listOf(evidence("7027", "204"))) } }
            first.get(); second.get()
            assertTrue(store.get(33, 10)!!.contains("product_id=580"))
            assertTrue(store.get(33, 11)!!.contains("product_id=7027"))
            assertFalse(store.get(33, 10)!!.contains("7027"))
        } finally { executor.shutdownNow() }
    }
}
