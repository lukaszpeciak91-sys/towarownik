package pl.lukaszpeciak.towarownik.product.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchResolverTest {
    private val obiBranches =
        (ObiProductProvider().branches() as ProviderBranchResult.Available)
            .branches

    private val kwantBranches = listOf(
        ProviderBranch(
            branchId = BranchId("205"),
            name = "Nowy Sącz",
            address = "33-300 Tarnowska 149",
        ),
        ProviderBranch(
            branchId = BranchId("128"),
            name = "Zamość",
            address = "22-400 Braterstwa Broni 60",
        ),
        ProviderBranch(
            branchId = BranchId("210"),
            name = "Kraków",
            address = "31-980 Longinusa Podbipięty 96",
        ),
    )

    @Test
    fun `OBI exact market number still resolves`() {
        assertResolved(
            BranchResolver.resolve(
                userText = "Sprawdź w markecie 003",
                branches = obiBranches,
                currentBranchId = BranchId("075"),
            ),
            "003",
        )
    }

    @Test
    fun `OBI Krakow Wielicka resolves verified market 003`() {
        listOf(
            "czy jest w OBI Kraków Wielicka?",
            "OBI na Wielickiej",
            "w markecie na Wielickiej",
        ).forEach { text ->
            assertResolved(
                BranchResolver.resolve(
                    userText = text,
                    branches = obiBranches,
                    currentBranchId = BranchId("075"),
                ),
                "003",
            )
        }
    }

    @Test
    fun `OBI Krakow city alone is ambiguous across real markets`() {
        val resolution = BranchResolver.resolve(
            userText = "sprawdź OBI Kraków",
            branches = obiBranches,
            currentBranchId = BranchId("075"),
        ) as BranchResolution.Ambiguous

        assertEquals(
            setOf("003", "019", "059", "072"),
            resolution.candidates.map { it.branchId.value }.toSet(),
        )
        assertTrue(!resolution.allowsExplicitBranchHint)
    }

    @Test
    fun `natural metadata without branch intent is not mentioned`() {
        listOf(
            "szukam długą listwę",
            "potrzebuję produktu do Krakowa",
            "szukam produktu Wielicka",
            "model Kraków 400 V",
            "Wielicka",
            "Kraków",
        ).forEach { text ->
            assertEquals(
                text,
                BranchResolution.NotMentioned,
                BranchResolver.resolve(
                    userText = text,
                    branches = obiBranches,
                    currentBranchId = BranchId("075"),
                ),
            )
        }
    }

    @Test
    fun `exact OBI branch id stays authoritative without natural location intent`() {
        listOf(
            "003",
            "market 003",
        ).forEach { text ->
            assertResolved(
                BranchResolver.resolve(
                    userText = text,
                    branches = obiBranches,
                    currentBranchId = BranchId("075"),
                ),
                "003",
            )
        }
    }

    @Test
    fun `OBI current branch references resolve to 075`() {
        listOf(
            "u nas",
            "na naszym magazynie",
            "w Nowym Sączu",
            "w Nowym Saczu",
            "w Sączu",
            "w Saczu",
        ).forEach { text ->
            assertCurrent(
                BranchResolver.resolve(
                    userText = text,
                    branches = obiBranches,
                    currentBranchId = BranchId("075"),
                ),
                "075",
            )
        }
    }

    @Test
    fun `unknown explicit OBI location never resolves to current branch`() {
        assertEquals(
            BranchResolution.UnknownMention,
            BranchResolver.resolve(
                userText = "sprawdź w OBI Zakopane",
                branches = obiBranches,
                currentBranchId = BranchId("075"),
            ),
        )
    }

    @Test
    fun `KWANT Zamosc resolves verified branch 128 with explicit intent`() {
        listOf(
            "ile tego jest w Kwant Zamość?",
            "Kwant Zamość",
            "w hurtowni Zamość",
        ).forEach { text ->
            assertResolved(
                BranchResolver.resolve(
                    userText = text,
                    branches = kwantBranches,
                    currentBranchId = BranchId("205"),
                ),
                "128",
            )
        }
    }

    @Test
    fun `KWANT current Nowy Sacz aliases resolve to 205`() {
        listOf(
            "Kwant Nowy Sącz",
            "w Nowym Saczu",
            "w Sączu",
            "u nas",
        ).forEach { text ->
            assertCurrent(
                BranchResolver.resolve(
                    userText = text,
                    branches = kwantBranches,
                    currentBranchId = BranchId("205"),
                ),
                "205",
            )
        }
    }

    @Test
    fun `incidental KWANT branch metadata without intent does not route`() {
        listOf(
            "produkt Zamość 16 A",
            "model Tarnowska 149",
        ).forEach { text ->
            assertEquals(
                text,
                BranchResolution.NotMentioned,
                BranchResolver.resolve(
                    userText = text,
                    branches = kwantBranches,
                    currentBranchId = BranchId("205"),
                ),
            )
        }
    }

    @Test
    fun `unknown explicit KWANT location fails safely`() {
        assertEquals(
            BranchResolution.UnknownMention,
            BranchResolver.resolve(
                userText = "sprawdź w Kwant Zakopane",
                branches = kwantBranches,
                currentBranchId = BranchId("205"),
            ),
        )
    }

    @Test
    fun `provider directories stay isolated`() {
        val obiResolution = BranchResolver.resolve(
            userText = "w Kwant Zamość",
            branches = obiBranches,
            currentBranchId = BranchId("075"),
        )
        val kwantResolution = BranchResolver.resolve(
            userText = "OBI Wielicka",
            branches = kwantBranches,
            currentBranchId = BranchId("205"),
        )

        assertTrue(obiResolution !is BranchResolution.Resolved)
        assertTrue(kwantResolution !is BranchResolution.Resolved)
    }

    @Test
    fun `two explicit branch ids preserve safe per-call disambiguation`() {
        val resolution = BranchResolver.resolve(
            userText = "porównaj 074 i 075",
            branches = obiBranches,
            currentBranchId = BranchId("075"),
        ) as BranchResolution.Ambiguous

        assertTrue(resolution.allowsExplicitBranchHint)
        assertEquals(
            setOf("074", "075"),
            resolution.candidates.map { it.branchId.value }.toSet(),
        )
        assertEquals(
            BranchId("074"),
            BranchResolver.resolveHint(
                hint = "074",
                branches = obiBranches,
            )?.branchId,
        )
    }

    @Test
    fun `vague typo is not fuzzy matched`() {
        assertEquals(
            BranchResolution.NotMentioned,
            BranchResolver.resolve(
                userText = "Krakuff",
                branches = obiBranches,
                currentBranchId = BranchId("075"),
            ),
        )
    }

    private fun assertResolved(
        resolution: BranchResolution,
        expectedBranchId: String,
    ) {
        resolution as BranchResolution.Resolved
        assertEquals(
            BranchId(expectedBranchId),
            resolution.branch.branchId,
        )
    }

    private fun assertCurrent(
        resolution: BranchResolution,
        expectedBranchId: String,
    ) {
        resolution as BranchResolution.CurrentBranch
        assertEquals(
            BranchId(expectedBranchId),
            resolution.branch.branchId,
        )
    }
}
