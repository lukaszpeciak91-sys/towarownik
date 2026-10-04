package pl.lukaszpeciak.towarownik.product.provider

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WorkingProfileRepositoryTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(
            "working-profile",
            Context.MODE_PRIVATE,
        ).edit().clear().commit()
    }

    @Test
    fun `default profile is OBI 075`() {
        val repository = WorkingProfileRepository.production(context)

        assertEquals(DEFAULT_WORKING_PROFILE, repository.load())
        assertEquals(OBI_PROVIDER_ID, repository.load().providerId)
        assertEquals(BranchId("075"), repository.load().branchId)
    }

    @Test
    fun `provider and branch persist across repository recreation`() {
        WorkingProfileRepository.production(context).save(
            WorkingProfile(
                providerId = KWANT_PROVIDER_ID,
                branchId = BranchId("205"),
            ),
        )

        val recreated = WorkingProfileRepository.production(context)

        assertEquals(
            WorkingProfile(
                providerId = KWANT_PROVIDER_ID,
                branchId = BranchId("205"),
            ),
            recreated.load(),
        )
    }

    @Test
    fun `branch switch persists independently inside selected provider`() {
        val repository = WorkingProfileRepository.production(context)
        repository.save(
            WorkingProfile(
                providerId = OBI_PROVIDER_ID,
                branchId = BranchId("074"),
            ),
        )

        assertEquals(
            WorkingProfile(
                providerId = OBI_PROVIDER_ID,
                branchId = BranchId("074"),
            ),
            WorkingProfileRepository.production(context).load(),
        )
    }
}
