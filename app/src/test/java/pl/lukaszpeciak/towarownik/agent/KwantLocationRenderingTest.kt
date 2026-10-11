package pl.lukaszpeciak.towarownik.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KwantLocationRenderingTest {
    @Test fun verifiedPositiveZeroUnknownAndUnitRenderAsKwantBranches() {
        val rendered = renderAdvisorLocationDetails(AdvisorLocationEvidence(
            providerId = "kwant-pl", productId = "7027",
            status = "verified", reason = "partial_inventory",
            coverage = "partial",
            checkedIds = listOf("128", "205", "216"),
            returnedIds = listOf("128", "205"),
            missingIds = listOf("216"),
            locations = listOf(
                AdvisorLocationEntry("128", "Zamość", 8),
                AdvisorLocationEntry("205", "Nowy Sącz", 0),
                AdvisorLocationEntry("216", "Tarnów", null),
            ), verifiedAtMillis = 1234L, centralStock = 987654,
            stockUnit = "kg",
        ))
        assertTrue(rendered.contains("oddziały KWANT"))
        assertTrue(rendered.contains("Zamość — 8 kg"))
        assertTrue(rendered.contains("Nowy Sącz — 0 kg"))
        assertTrue(rendered.contains("Tarnów — brak potwierdzenia"))
        assertTrue(rendered.contains("wyniki niepełne"))
        assertTrue(rendered.contains("stan zweryfikowany"))
        assertFalse(rendered.contains("markety OBI"))
        assertFalse(rendered.contains("987654"))
    }

    @Test fun unknownKwantUnitMustNotBeGuessedFromProductOrBranch() {
        val text = renderAdvisorLocationDetails(AdvisorLocationEvidence(
            providerId = "kwant-pl", productId = "580",
            status = "verified", reason = null, coverage = "requested_subset",
            checkedIds = listOf("205"), returnedIds = listOf("205"),
            missingIds = emptyList(),
            locations = listOf(AdvisorLocationEntry("205", "Nowy Sącz", 10)),
            verifiedAtMillis = null, centralStock = null, stockUnit = null,
        ))
        assertTrue(text.contains("Nowy Sącz — 10"))
        assertFalse(text.contains("Nowy Sącz — 10 szt."))
    }

    @Test fun twoProductProtocolLimitStatesNoInventoryWasChecked() {
        val text = renderAdvisorLocationDetails(AdvisorLocationEvidence(
            providerId = "kwant-pl", productId = null,
            status = "unavailable", reason = "multi_product_tool_contract",
            coverage = "unknown", checkedIds = emptyList(), returnedIds = emptyList(),
            missingIds = emptyList(), locations = emptyList(),
            verifiedAtMillis = null, centralStock = null,
        ))
        assertTrue(text.contains("dwa produkty"))
        assertTrue(text.contains("Nie sprawdziłam"))
        assertFalse(text.contains("nie jest jeszcze zweryfikowany"))
    }
    @Test fun noTrustedRowsShowsUnknownInsteadOfAnInventedZero() {
        val text = renderAdvisorLocationDetails(AdvisorLocationEvidence(
            providerId = "kwant-pl", productId = "580",
            status = "unavailable", reason = "no_trusted_rows",
            coverage = "unknown", checkedIds = listOf("205"),
            returnedIds = emptyList(), missingIds = listOf("205"),
            locations = emptyList(), verifiedAtMillis = null,
            centralStock = null,
        ))
        assertTrue(text.contains("Stan pozostaje nieznany"))
        assertFalse(text.contains("0 szt."))
    }

}
