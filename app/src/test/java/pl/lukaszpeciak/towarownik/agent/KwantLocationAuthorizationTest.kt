package pl.lukaszpeciak.towarownik.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.ProviderBranch

/** USER-owned scope; never the model hint. Synthetic canonical directory. */
class KwantLocationAuthorizationTest {
    // Names/IDs are fixture evidence from the public 21-branch directory
    // (research #111 and canonical KWANT branch pages), not runtime routing.
    private val directory = listOf(
        ProviderBranch(BranchId("216"), "Białystok"),
        ProviderBranch(BranchId("203"), "Bochnia"),
        ProviderBranch(BranchId("202"), "Brzesko"),
        ProviderBranch(BranchId("211"), "Bydgoszcz"),
        ProviderBranch(BranchId("23"), "Chełm"),
        ProviderBranch(BranchId("201"), "Dąbrowa Tarnowska"),
        ProviderBranch(BranchId("213"), "Kielce"),
        ProviderBranch(BranchId("210"), "Kraków"),
        ProviderBranch(BranchId("215"), "Krosno"),
        ProviderBranch(BranchId("131"), "Lublin"),
        ProviderBranch(BranchId("154"), "Łódź"),
        ProviderBranch(BranchId("206"), "Mielec"),
        ProviderBranch(BranchId("205"), "Nowy Sącz"),
        ProviderBranch(BranchId("152"), "Ostrołęka"),
        ProviderBranch(BranchId("214"), "Piaseczno"),
        ProviderBranch(BranchId("20"), "Rzeszów"),
        ProviderBranch(BranchId("161"), "Suwałki"),
        ProviderBranch(BranchId("204"), "Tarnów"),
        ProviderBranch(BranchId("153"), "Warszawa"),
        ProviderBranch(BranchId("141"), "Włocławek"),
        ProviderBranch(BranchId("128"), "Zamość"),
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
            listOf(BranchId("204")),
            allowed("Jaki jest stan 921861 w Tarnowie?", listOf("204")).requested,
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
            "Sprawdź stan 7027 w oddziale 128 i 204",
            "Sprawdź stan 7027 w Zamościu oraz oddziale 204",
        )) {
            val result = allowed(request)
            assertEquals(request, setOf(BranchId("128"), BranchId("204")),
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
            "Sprawdź stan 7027 w Zamościu oddział 204",
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
        assertEquals(setOf(BranchId("128"), BranchId("204")),
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

    @Test fun allTwentyOneVerifiedCitiesHaveBoundedCanonicalLocatives() {
        val cases = listOf(
            Triple("w Białymstoku", "216", "Białystok"),
            Triple("w Bochni", "203", "Bochnia"),
            Triple("w Brzesku", "202", "Brzesko"),
            Triple("w Bydgoszczy", "211", "Bydgoszcz"),
            Triple("w Chełmie", "23", "Chełm"),
            Triple("w Dąbrowie Tarnowskiej", "201", "Dąbrowa Tarnowska"),
            Triple("w Kielcach", "213", "Kielce"),
            Triple("w Krakowie", "210", "Kraków"),
            Triple("w Krośnie", "215", "Krosno"),
            Triple("w Lublinie", "131", "Lublin"),
            Triple("w Łodzi", "154", "Łódź"),
            Triple("w Mielcu", "206", "Mielec"),
            Triple("w Nowym Sączu", "205", "Nowy Sącz"),
            Triple("w Ostrołęce", "152", "Ostrołęka"),
            Triple("w Piasecznie", "214", "Piaseczno"),
            Triple("w Rzeszowie", "20", "Rzeszów"),
            Triple("w Suwałkach", "161", "Suwałki"),
            Triple("w Tarnowie", "204", "Tarnów"),
            Triple("w Warszawie", "153", "Warszawa"),
            Triple("we Włocławku", "141", "Włocławek"),
            Triple("w Zamościu", "128", "Zamość"),
        )
        assertEquals(21, directory.size)
        for ((wording, id, name) in cases) {
            val user = "Sprawdź stan produktu 7027 $wording"
            val result = allowed(user, listOf(name))
            assertEquals(wording, listOf(BranchId(id)), result.requested)
            assertEquals(wording, false, result.nationwide)
        }
    }

    @Test fun inflectedMultipleCitiesAndMixedNumericScopesAreComplete() {
        val cases = listOf(
            "Sprawdź stan 7027 w Białymstoku i Tarnowie",
            "Sprawdź stan 7027 w Białymstoku oraz Tarnowie",
            "Sprawdź stan 7027 w Tarnowie, Białymstoku",
            "Sprawdź stan 7027 w oddziale 216 i 204",
            "Sprawdź stan 7027 w Białymstoku oraz oddziale 204",
        )
        for (user in cases) {
            val result = allowed(user, listOf("216"))
            assertEquals(user, setOf(BranchId("216"), BranchId("204")),
                result.requested.toSet())
        }
        assertEquals(setOf(BranchId("141"), BranchId("161")),
            allowed("Sprawdź stan 7027 we Włocławku i Suwałkach").requested.toSet())
    }

    @Test fun unknownOrConflictingLocativesRejectBeforeAnyRead() {
        for (text in listOf(
            "Sprawdź stan 7027 w Białymstoku i Nieznanowie",
            "Sprawdź stan 7027 w Bochni i oddziale 999",
            "Sprawdź stan 7027 w oddziale 216 Tarnów",
            "Sprawdź stan 7027 w Białymstoku oddział 204",
            "Sprawdź stan 7027 w Piasecznie i Wrocławiu",
        )) {
            val answer = authorizeKwantLocations(text, emptyList(),
                directory, BranchId("205"))
            assertTrue(text, answer is KwantAuthorizedLocations.Denied)
        }
    }

    @Test fun locationAliasBindsToRuntimeDirectoryIdentityNotStaticMap() {
        val remappedOnlyInSyntheticFixture = directory.map {
            if (it.name == "Białystok") it.copy(branchId = BranchId("901"))
            else it
        }
        val outcome = authorizeKwantLocations(
            "Sprawdź stan 7027 w Białymstoku", listOf("Białystok"),
            remappedOnlyInSyntheticFixture, BranchId("205"),
        ) as KwantAuthorizedLocations.Allowed
        assertEquals(listOf(BranchId("901")), outcome.requested)
        assertEquals("unknown_location",
            (authorizeKwantLocations(
                "Sprawdź stan 7027 w Białymstoku", listOf("216"),
                remappedOnlyInSyntheticFixture, BranchId("205"),
            ) as KwantAuthorizedLocations.Denied).reason)
    }

}
