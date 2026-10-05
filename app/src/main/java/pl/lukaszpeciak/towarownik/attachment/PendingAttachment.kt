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
    private val commitEditor: (SharedPreferences.Editor) -> Boolean = {
        it.commit()
    },
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

    /**
     * Records ownership before AttachmentStorage publishes the durable file.
     * A failed commit aborts publication, so no final unsent file can exist
     * without a recoverable ownership marker.
     */
    fun stageImportedCandidate(attachment: AdvisorAttachment): Boolean =
        commitEditor(
            preferences.edit()
                .putString(KEY_STAGED_LOCAL_ID, attachment.localId),
        )

    /**
     * Durably switches ownership to the imported file before callers expose it
     * as the active Compose pending attachment or release the previous file.
     */
    fun activateImportedCandidate(
        attachment: AdvisorAttachment,
        previous: AdvisorAttachment?,
    ): Boolean {
        if (stagedLocalId() != attachment.localId) {
            runCatching { storage.delete(attachment.localId) }
            return false
        }

        val previousId = previous
            ?.localId
            ?.takeIf { it != attachment.localId }
        val editor = preferences.edit()
            .putString(KEY_LOCAL_ID, attachment.localId)
            .remove(KEY_STAGED_LOCAL_ID)
        if (previousId != null) {
            editor.putString(KEY_RETIRED_LOCAL_ID, previousId)
        } else {
            editor.remove(KEY_RETIRED_LOCAL_ID)
        }

        if (!commitEditor(editor)) {
            runCatching { storage.delete(attachment.localId) }
            clearStagedBestEffort(attachment.localId)
            return false
        }

        if (previousId != null) {
            val deleted = runCatching {
                storage.delete(previousId)
            }.getOrDefault(false)
            if (deleted) {
                clearRetiredBestEffort(previousId)
            }
        }
        return true
    }

    fun markPending(attachment: AdvisorAttachment): Boolean {
        if (ownedLocalId() == attachment.localId) return true
        return commitEditor(
            preferences.edit()
                .putString(KEY_LOCAL_ID, attachment.localId),
        )
    }

    /**
     * Releases pending ownership only after the caller has durably persisted the
     * attachment with its USER message. The private file is deliberately left
     * in place because Room now owns its lifecycle.
     */
    fun handoffToPersisted(localId: String) {
        check(ownedLocalId() == localId) {
            "Attachment is not pending"
        }
        check(
            commitEditor(preferences.edit().remove(KEY_LOCAL_ID)),
        ) {
            "Could not release pending attachment ownership"
        }
    }

    fun clearIfOwned(localId: String) {
        if (ownedLocalId() == localId) {
            preferences.edit().remove(KEY_LOCAL_ID).apply()
        }
        if (stagedLocalId() == localId) {
            preferences.edit().remove(KEY_STAGED_LOCAL_ID).apply()
        }
        if (retiredLocalId() == localId) {
            preferences.edit().remove(KEY_RETIRED_LOCAL_ID).apply()
        }
    }

    fun ownedLocalId(): String? =
        preferences.getString(KEY_LOCAL_ID, null)

    internal fun stagedLocalId(): String? =
        preferences.getString(KEY_STAGED_LOCAL_ID, null)

    internal fun retiredLocalId(): String? =
        preferences.getString(KEY_RETIRED_LOCAL_ID, null)

    suspend fun reconcileAfterStartup(
        restored: AdvisorAttachment?,
        isPersisted: suspend (String) -> Boolean,
    ): AdvisorAttachment? {
        val owned = ownedLocalId()
        val staged = stagedLocalId()
        val retired = retiredLocalId()
        val usableRestored = restored?.takeIf {
            storage.exists(it.localId)
        }

        if (usableRestored != null) {
            if (owned != usableRestored.localId) {
                if (!markPending(usableRestored)) {
                    if (!isPersisted(usableRestored.localId)) {
                        runCatching {
                            storage.delete(usableRestored.localId)
                        }
                    }
                    return null
                }
            }

            listOfNotNull(owned, staged, retired)
                .distinct()
                .filter { it != usableRestored.localId }
                .forEach { localId ->
                    if (!isPersisted(localId)) {
                        runCatching { storage.delete(localId) }
                    }
                }

            preferences.edit()
                .remove(KEY_STAGED_LOCAL_ID)
                .remove(KEY_RETIRED_LOCAL_ID)
                .apply()
            return usableRestored
        }

        listOfNotNull(owned, staged, retired)
            .distinct()
            .forEach { localId ->
                if (!isPersisted(localId)) {
                    runCatching { storage.delete(localId) }
                }
            }
        preferences.edit()
            .remove(KEY_LOCAL_ID)
            .remove(KEY_STAGED_LOCAL_ID)
            .remove(KEY_RETIRED_LOCAL_ID)
            .apply()
        return null
    }

    private fun clearStagedBestEffort(localId: String) {
        if (stagedLocalId() == localId) {
            preferences.edit().remove(KEY_STAGED_LOCAL_ID).apply()
        }
    }

    private fun clearRetiredBestEffort(localId: String) {
        if (retiredLocalId() == localId) {
            preferences.edit().remove(KEY_RETIRED_LOCAL_ID).apply()
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "advisor_pending_attachment"
        const val KEY_LOCAL_ID = "local_id"
        const val KEY_STAGED_LOCAL_ID = "staged_local_id"
        const val KEY_RETIRED_LOCAL_ID = "retired_local_id"
    }
}

internal fun canSubmitAdvisorComposer(text: String, attachment: AdvisorAttachment?): Boolean =
    text.isNotBlank() || attachment != null

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
