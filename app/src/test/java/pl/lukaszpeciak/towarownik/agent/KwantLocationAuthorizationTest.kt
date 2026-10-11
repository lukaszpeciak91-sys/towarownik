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
}
