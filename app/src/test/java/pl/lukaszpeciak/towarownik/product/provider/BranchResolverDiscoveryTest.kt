package pl.lukaszpeciak.towarownik.product.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class BranchResolverDiscoveryTest {
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

    @Test fun `all canonical KWANT cities and reviewed locatives resolve directory identities`() {
        val locatives = listOf("Białymstoku","Bochni","Brzesku","Bydgoszczy","Chełmie","Dąbrowie Tarnowskiej","Kielcach","Krakowie","Krośnie","Lublinie","Łodzi","Mielcu","Nowym Sączu","Ostrołęce","Piasecznie","Rzeszowie","Suwałkach","Tarnowie","Warszawie","Włocławku","Zamościu")
        directory.zip(locatives).forEach { (branch,locative) ->
            for(place in listOf(branch.name,locative,locative.lowercase())) {
                val resolution=BranchResolver.resolve("sprawdź stan w $place",directory,BranchId("205"))
                val actual=when(resolution) {
                    is BranchResolution.Resolved -> resolution.branch.branchId
                    is BranchResolution.CurrentBranch -> resolution.branch.branchId
                    else -> null
                }
                assertEquals(place,branch.branchId,actual)
            }
        }
    }
    @Test fun `aliases only bind to canonical names actually present in directory`() {
        assertEquals(BranchResolution.UnknownMention, BranchResolver.resolve("sprawdź w Bochni",directory.filterNot{it.name=="Bochnia"},BranchId("205")))
        assertEquals(BranchResolution.UnknownMention, BranchResolver.resolve("sprawdź w Krakufie",directory,BranchId("205")))
    }
}
