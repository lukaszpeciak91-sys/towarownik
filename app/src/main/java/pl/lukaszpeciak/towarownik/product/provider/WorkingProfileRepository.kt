package pl.lukaszpeciak.towarownik.product.provider

import android.content.Context

internal class WorkingProfileRepository private constructor(
    private val preferences: android.content.SharedPreferences,
) {
    fun load(): WorkingProfile {
        val provider = preferences.getString(KEY_PROVIDER_ID, null)
        val branch = preferences.getString(KEY_BRANCH_ID, null)
        if (provider.isNullOrBlank() || branch.isNullOrBlank()) {
            return DEFAULT_WORKING_PROFILE
        }
        return runCatching {
            WorkingProfile(
                providerId = ProviderId(provider),
                branchId = BranchId(branch),
            )
        }.getOrDefault(DEFAULT_WORKING_PROFILE)
    }

    fun save(profile: WorkingProfile) {
        preferences.edit()
            .putString(KEY_PROVIDER_ID, profile.providerId.value)
            .putString(KEY_BRANCH_ID, profile.branchId.value)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "working-profile"
        private const val KEY_PROVIDER_ID = "provider_id"
        private const val KEY_BRANCH_ID = "branch_id"

        fun production(context: Context): WorkingProfileRepository =
            WorkingProfileRepository(
                context.applicationContext.getSharedPreferences(
                    PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ),
            )

        internal fun forPreferences(
            preferences: android.content.SharedPreferences,
        ): WorkingProfileRepository =
            WorkingProfileRepository(preferences)
    }
}
