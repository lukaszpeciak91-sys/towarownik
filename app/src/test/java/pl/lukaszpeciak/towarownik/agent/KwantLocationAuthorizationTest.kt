package pl.lukaszpeciak.towarownik.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch

/** USER-owned scope; never the model hint. Synthetic canonical directory. */
class KwantLocationAuthorizationTest {
    private val directory = listOf(
        ProviderBranch(BranchId("205"), "Nowy Sącz"),
        ProviderBranch(BranchId("128"), "Zamość"),
        ProviderBranch(BranchId("216"), "Tarnów"),
    )

    private fun allowed(text: String, hints: List<String> = emptyList()) =
        authorizeKwantLocations(text, hints, directory, BranchId("205"))
            as KwantAuthorizedLocations.Allowed

    private fun rejected(text: String, hints: List<String>): String =
        (authorizeKwantLocations(text, hints, directory, BranchId("205"))
            as KwantAuthorizedLocations.Denied).reason

    @Test fun onlyCurrentUserExplicitLocationCanChangeBranch() {
        for (request in listOf(
            "Sprawdź stan produktu 7027 w oddziale Zamość",
            "Sprawdź dostępność 7027 w Zamościu",
            "Sprawdź stan 7027 w oddziale w Zamościu",
            "a w Zamościu?",
        )) {
            val resolution = allowed(request, listOf("Zamość"))
            assertEquals(request, listOf(BranchId("128")), resolution.requested)
            assertEquals(request, false, resolution.nationwide)
        }
    }

    @Test fun branchIdAndLocativeAliasAreExactNotGuessed() {
        assertEquals(
            listOf(BranchId("216")),
            allowed("Jaki jest stan 921861 w Tarnowie?", listOf("216")).requested,
        )
        assertEquals(
            listOf(BranchId("128")),
            allowed("Sprawdź stan produktu 7027 w oddziale 128",
                listOf("128")).requested,
        )
        assertEquals(
            "unknown_location",
            rejected("Sprawdź stan 7027 w nieznanym mieście", emptyList()),
        )
        assertEquals(
            "unknown_location",
            rejected("Sprawdź stan 7027 w oddziale 999", emptyList()),
        )
    }

    @Test fun modelCannotExpandRestrictedBranchToWholeNetwork() {
        assertEquals(
            "location_not_authorized",
            rejected("Sprawdź stan 7027 w Zamościu", listOf("Tarnów")),
        )
        assertEquals(
            "unknown_location",
            rejected("Sprawdź stan 7027 w Zamościu", listOf("wszystkie")),
        )
        assertEquals(
            listOf(BranchId("205")),
            allowed("Sprawdź stan produktu 580").requested,
        )
    }

    @Test fun explicitNationwideRequestGivesAllCanonicalBranches() {
        for (request in listOf(
            "Sprawdź stan 7027 we wszystkich oddziałach",
            "Gdzie dostępny produkt 580 w oddziałach?",
            "Sprawdź dostępność 580 w całej sieci",
        )) {
            val resolution = allowed(request)
            assertTrue(request, resolution.nationwide)
            assertTrue(request, resolution.requested.isEmpty())
        }
    }

    @Test fun conflictingDirectoryFailsClosed() {
        val duplicated = directory + directory.last()
        assertEquals(
            "directory_unavailable",
            (authorizeKwantLocations("Sprawdź dostępność 7027",
                emptyList(), duplicated, BranchId("205"))
                as KwantAuthorizedLocations.Denied).reason,
        )
    }
    @Test fun explicitCityAndBranchIdChainsAreComplete() {
        for (request in listOf(
            "Sprawdź stan 7027 w Zamościu i Tarnowie",
            "Sprawdź stan 7027 w Zamościu oraz Tarnowie",
            "Sprawdź stan 7027 w Tarnowie, Zamościu",
            "Sprawdź stan 7027 w oddziale 128 i 216",
            "Sprawdź stan 7027 w Zamościu oraz oddziale 216",
        )) {
            val result = allowed(request)
            assertEquals(request, setOf(BranchId("128"), BranchId("216")),
                result.requested.toSet())
            assertEquals(request, 2, result.requested.size)
            assertEquals(request, false, result.nationwide)
        }
    }

    @Test fun unknownSecondLocationOrConflictingPairedLabelsRejectEverything() {
        for (request in listOf(
            "Sprawdź stan 7027 w Zamościu i Nieznanowie",
            "Sprawdź stan 7027 w Zamościu oraz oddziale 999",
            "Sprawdź stan 7027 w Tarnowie, 999",
            "Sprawdź stan 7027 w oddziale 128 i 999",
            "Sprawdź stan 7027 w oddziale 128 Tarnów",
            "Sprawdź stan 7027 w Zamościu oddział 216",
            "Sprawdź stan 7027 w oddziale 128 w Tarnowie",
        )) {
            val outcome = authorizeKwantLocations(
                request, emptyList(), directory, BranchId("205"),
            )
            assertTrue(request, outcome is KwantAuthorizedLocations.Denied)
        }
    }

    @Test fun modelHintsMayNotExpandTwoUserAuthorizedBranches() {
        val text = "Sprawdź stan 7027 w Zamościu i Tarnowie"
        assertEquals(setOf(BranchId("128"), BranchId("216")),
            allowed(text, listOf("Tarnów")).requested.toSet())
        assertEquals("location_not_authorized",
            rejected(text, listOf("Nowy Sącz")))
        assertEquals("unknown_location",
            rejected(text, listOf("wszystkie")))
    }

    @Test fun measurementAnnotationsDoNotBecomeExtraLocationOrOverrideVerifiedScope() {
        for (request in listOf(
            "Sprawdź stan produktu 7035 w oddziale Zamość, potrzebuję 1000 W, 25 m i 5 szt.",
            "Sprawdź stan produktu 7035 w oddziale Zamość, 1000 W, 25 m i 5 szt.",
            "Sprawdź 1000 W w oddziale Zamość",
        )) {
            assertEquals(request, listOf(BranchId("128")), allowed(request).requested)
        }
        assertEquals("unknown_location", rejected(
            "Sprawdź stan 7035 w Zamościu, 1000 W i Tarnowie",
            emptyList(),
        ))
    }

}
