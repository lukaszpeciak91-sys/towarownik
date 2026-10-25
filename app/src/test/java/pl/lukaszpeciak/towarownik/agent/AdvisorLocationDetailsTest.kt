package pl.lukaszpeciak.towarownik.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.OBI_STORES

class AdvisorLocationDetailsTest {
    @Test fun `full checked OBI scope stays visible even when model output is short`() {
        val ids = (1..61).map { it.toString().padStart(3, '0') }
        val evidence = AdvisorLocationEvidence(
            providerId = "obi-pl", productId = "3496072",
            status = "verified", reason = "partial_inventory",
            coverage = "partial", checkedIds = ids,
            returnedIds = ids.take(60), missingIds = ids.takeLast(1),
            locations = ids.mapIndexed { i, id ->
                AdvisorLocationEntry(
                    id,
                    "Synthetic city $id — synthetic address",
                    if (i == 60) null else if (i == 0) 0 else 4,
                )
            },
            verifiedAtMillis = 1700000000000L, centralStock = null,
        )
        val summary = renderAdvisorLocationDetails(evidence)
        val lines = summary.lines()
        assertEquals(64, lines.size) // heading, 61 locations, incompleteness note, timestamp
        assertTrue(lines[0].contains("wyniki niepełne"))
        assertFalse(lines[0].contains("sprawdzono"))
        assertTrue(lines.any { it.startsWith("Synthetic city 061") && it.contains("Nie udało się potwierdzić stanu") })
        assertTrue(lines.any { it.startsWith("Synthetic city 001") && it.contains("Brak na stanie (0 szt.)") })
        assertFalse(lines.any { it.startsWith("Synthetic city 061") && it.contains("0 szt.") })
    }

    @Test fun `all confirmed other markets have every canonical ID in plain chat list`() {
        val ids = (1..61).map { it.toString().padStart(3, '0') }
        val evidence = AdvisorLocationEvidence(
            providerId = "obi-pl", productId = "3496072",
            status = "verified", reason = null,
            coverage = "all_other_locations",
            checkedIds = ids, returnedIds = ids, missingIds = emptyList(),
            locations = ids.map { AdvisorLocationEntry(it, "Fixture market $it", 0) },
            verifiedAtMillis = 1700000000000L, centralStock = null,
        )
        val rendered = renderAdvisorLocationDetails(evidence)
        assertTrue(rendered.contains("Wszystkie pozostałe markety OBI"))
        for (id in ids) assertTrue(rendered.lines().any { it.contains("($id) —") })
        assertFalse(rendered.contains("Nie udało się potwierdzić stanu"))
    }

    @Test fun `untrusted or mismatched market identities are never rendered`() {
        val evidence = AdvisorLocationEvidence(
            providerId = "obi-pl", productId = "3496072",
            status = "verified", reason = null,
            coverage = "requested_subset",
            checkedIds = listOf("075"),
            returnedIds = listOf("075"), missingIds = emptyList(),
            locations = listOf(AdvisorLocationEntry("999", "Injected", 1)),
            verifiedAtMillis = 1700000000000L, centralStock = null,
        )
        assertEquals("", renderAdvisorLocationDetails(evidence))
    }
    @Test fun `partial nationwide lookup is still explicitly labelled all other stores`() {
        val ids = OBI_STORES.map { it.storeNumber }.filterNot { it == "075" }
        assertEquals(61, ids.size)
        val missing = ids.takeLast(10)
        val evidence = AdvisorLocationEvidence(
            providerId = "obi-pl", productId = "3496072",
            status = "verified", reason = "partial_inventory",
            coverage = "partial",
            checkedIds = ids, returnedIds = ids.dropLast(10), missingIds = missing,
            locations = ids.map { id ->
                AdvisorLocationEntry(id, "Fixture market " + id, if (id in missing) null else if (id == ids[0]) 0 else 4)
            },
            verifiedAtMillis = 1700000000000L, centralStock = null,
        )
        val rendered = renderAdvisorLocationDetails(evidence)
        assertTrue(rendered.startsWith("Wszystkie pozostałe markety OBI — wyniki niepełne"))
        assertTrue(rendered.contains("Nie udało się potwierdzić wszystkich stanów"))
        assertEquals(64, rendered.lines().size)
        assertTrue(rendered.lines().any { it.contains("(" + missing[0] + ") —") && it.contains("Nie udało się potwierdzić stanu") })
    }



    @Test fun naturalSingleAndMultiKwantPreserveVerifiedUnitAndUnknown() {
        val entries = listOf(
            AdvisorLocationEntry("201", "Dąbrowa Tarnowska", 15),
            AdvisorLocationEntry("205", "Nowy Sącz", 0),
            AdvisorLocationEntry("204", "Tarnów", null),
        )
        fun evidence(items: List<AdvisorLocationEntry>) = AdvisorLocationEvidence(
            providerId = "kwant-pl", productId = "580", status = "verified",
            reason = null, coverage = "requested_subset",
            checkedIds = items.map { it.branchId },
            returnedIds = items.filter { it.stock != null }.map { it.branchId },
            missingIds = items.filter { it.stock == null }.map { it.branchId },
            locations = items, verifiedAtMillis = null, centralStock = null,
            stockUnit = "szt.",
        )
        assertEquals("Dąbrowa Tarnowska (201) — 15 szt.",
            renderAdvisorLocationDetails(evidence(entries.take(1))))
        val multi = renderAdvisorLocationDetails(evidence(entries))
        assertTrue(multi.contains("Dąbrowa Tarnowska (201) — 15 szt."))
        assertTrue(multi.contains("Nowy Sącz (205) — Brak na stanie (0 szt.)."))
        assertTrue(multi.contains("Tarnów (204) — Nie udało się potwierdzić stanu."))
        assertTrue(multi.contains("wyniki niepełne"))
        assertFalse(multi.contains("dodatni stan:"))
    }

    @Test fun rejectedMiejscePiastowe054RendersPreciseNoHttpClarification() {
        val evidence = AdvisorLocationEvidence(
            providerId = "obi-pl", productId = "6117543",
            status = "rejected",
            reason = "location_conflict_054_vs_052",
            coverage = "unknown",
            checkedIds = emptyList(), returnedIds = emptyList(),
            missingIds = emptyList(), locations = emptyList(),
            verifiedAtMillis = null, centralStock = null,
        )
        val explanation = renderAdvisorLocationDetails(evidence)
        assertTrue(explanation.contains("054"))
        assertTrue(explanation.contains("052"))
        assertTrue(explanation.contains("Miejsce Piastowe"))
        assertTrue(explanation.contains("Potwierdź"))
        assertTrue(explanation.contains("Nie wykonano sprawdzenia stanów"))
        assertTrue(renderAdvisorLocationDetails(
            evidence.copy(reason = "unknown_location"),
        ).contains("Nie rozpoznałam wskazanej lokalizacji"))
    }


    @Test fun kwantUnverifiedExtendedHasActionableUnavailableMessage() {
        val evidence = AdvisorLocationEvidence(
            providerId = "kwant-pl", productId = null,
            status = "unavailable", reason = "unverified_request_contract",
            coverage = "unknown", checkedIds = emptyList(),
            returnedIds = emptyList(), missingIds = emptyList(),
            locations = emptyList(), verifiedAtMillis = null,
            centralStock = null,
        )
        val rendered = renderAdvisorLocationDetails(evidence)
        assertTrue(rendered.contains("KWANT"))
        assertTrue(rendered.contains("profilu pracy"))
        assertTrue(rendered.contains("Nie wykonano sprawdzenia"))
        assertFalse(rendered.contains("0 szt."))
    }

}
