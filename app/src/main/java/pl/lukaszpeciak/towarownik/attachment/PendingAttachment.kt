package pl.lukaszpeciak.towarownik.attachment

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver

internal class PendingAttachmentState(
    private val storage: AttachmentStorage,
    initial: AdvisorAttachment? = null,
) {
    var attachment: AdvisorAttachment? = initial
        private set

    fun replace(next: AdvisorAttachment?) {
        if (next?.localId == attachment?.localId) return
        val previous = attachment
        attachment = next
        previous?.let { storage.delete(it.localId) }
    }

    fun remove() = replace(null)
}

internal class PendingAttachmentOwnership(
    private val storage: AttachmentStorage,
    private val preferences: SharedPreferences,
) {
    constructor(
        context: Context,
        storage: AttachmentStorage,
    ) : this(
        storage = storage,
        preferences = context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        ),
    )

    fun markPending(attachment: AdvisorAttachment) {
        preferences.edit()
            .putString(KEY_LOCAL_ID, attachment.localId)
            .apply()
    }

    fun clearIfOwned(localId: String) {
        if (ownedLocalId() == localId) {
            preferences.edit().remove(KEY_LOCAL_ID).apply()
        }
    }

    fun ownedLocalId(): String? =
        preferences.getString(KEY_LOCAL_ID, null)

    suspend fun reconcileAfterStartup(
        restored: AdvisorAttachment?,
        isPersisted: suspend (String) -> Boolean,
    ): AdvisorAttachment? {
        val owned = ownedLocalId()
        val usableRestored = restored?.takeIf {
            storage.exists(it.localId)
        }

        if (usableRestored != null) {
            if (
                owned != null &&
                owned != usableRestored.localId &&
                !isPersisted(owned)
            ) {
                runCatching { storage.delete(owned) }
            }
            markPending(usableRestored)
            return usableRestored
        }

        if (owned != null) {
            if (!isPersisted(owned)) {
                runCatching { storage.delete(owned) }
            }
            preferences.edit().remove(KEY_LOCAL_ID).apply()
        }
        return null
    }

    private companion object {
        const val PREFERENCES_NAME = "advisor_pending_attachment"
        const val KEY_LOCAL_ID = "local_id"
    }
}

internal fun canSubmitAdvisorComposer(text: String, attachment: AdvisorAttachment?): Boolean =
    attachment == null && text.isNotBlank()

internal fun advisorSubmissionUsesTextTransport(attachment: AdvisorAttachment?): Boolean = attachment == null

internal val PendingAttachmentSaver = Saver<MutableState<AdvisorAttachment?>, List<Any?>>(
    save = { state ->
        state.value?.let { value ->
            listOf(
                value.type.name, value.displayName, value.mimeType, value.localId,
                value.byteSize, value.width, value.height, value.createdAt,
            )
        } ?: emptyList()
    },
    restore = { saved ->
        mutableStateOf(
            if (saved.isEmpty()) null else validatedAttachmentOrNull(
                type = saved[0] as String,
                displayName = saved[1] as String,
                mimeType = saved[2] as String,
                localId = saved[3] as String,
                byteSize = saved[4] as Long,
                width = saved[5] as Int?,
                height = saved[6] as Int?,
                createdAt = saved[7] as Long,
            ),
        )
    },
)
