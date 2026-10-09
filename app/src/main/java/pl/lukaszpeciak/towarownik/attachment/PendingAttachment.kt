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

    fun discardImportedCandidate(attachment: AdvisorAttachment) {
        if (ownedLocalId() == attachment.localId) return
        if (stagedLocalId() == attachment.localId) {
            preferences.edit().remove(KEY_STAGED_LOCAL_ID).apply()
        }
        if (retiredLocalId() == attachment.localId) {
            preferences.edit().remove(KEY_RETIRED_LOCAL_ID).apply()
        }
        runCatching { storage.delete(attachment.localId) }
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
    fun handoffToPersisted(localId: String): Boolean {
        if (ownedLocalId() != localId) return false
        return commitEditor(
            preferences.edit().remove(KEY_LOCAL_ID),
        )
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

internal class AttachmentImportGuard {
    private var generation = 0L

    fun begin(): Long {
        generation += 1L
        return generation
    }

    fun invalidate() {
        generation += 1L
    }

    fun isCurrent(token: Long): Boolean = token == generation
}

internal fun canSubmitAdvisorComposer(text: String, attachment: AdvisorAttachment?): Boolean =
    text.isNotBlank() || attachment != null

internal fun canSendAdvisorComposer(
    enabled: Boolean,
    importInProgress: Boolean,
    text: String,
    attachment: AdvisorAttachment?,
): Boolean =
    enabled &&
        !importInProgress &&
        canSubmitAdvisorComposer(text, attachment)

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


/**
 * Ordered multi-file composer ownership. The durable sets are written before a
 * private file is published, or before a pending file is retired; Room takes
 * ownership only after the entire USER turn commits.
 *
 * Legacy v1 keys remain readable during upgrade from the single-file composer.
 */
internal class MultiPendingAttachmentOwnership(
    private val storage: AttachmentStorage,
    private val preferences: SharedPreferences,
) {
    constructor(context: Context, storage: AttachmentStorage) : this(
        storage,
        context.getSharedPreferences("advisor_pending_attachment", Context.MODE_PRIVATE),
    )

    private fun ids(key: String): Set<String> =
        preferences.getStringSet(key, emptySet()).orEmpty().toSet()

    private fun editIds(key: String, values: Set<String>): SharedPreferences.Editor =
        preferences.edit().putStringSet(key, values.toSet())

    fun stageImportedCandidate(item: AdvisorAttachment): Boolean =
        editIds(STAGED, ids(STAGED) + item.localId).commit()

    fun discardImportedCandidate(item: AdvisorAttachment) {
        val id = item.localId
        if (id !in ids(OWNED)) {
            // Leave a durable marker if deletion fails (reconciliation retries).
            retireAndDelete(setOf(id), staged = true)
        }
    }

    /** Atomically publish a new ordered composer selection before retiring files. */
    fun publishSelection(
        next: List<AdvisorAttachment>,
        replaced: List<AdvisorAttachment>,
        candidate: AdvisorAttachment? = null,
    ): Boolean {
        if (next.size > 3 || next.map { it.localId }.distinct().size != next.size ||
            next.sumOf { it.byteSize } > 24L * 1024 * 1024
        ) return false
        val nextIds = next.map { it.localId }.toSet()
        if (candidate != null && candidate.localId !in ids(STAGED)) return false
        val retiredIds = replaced.map { it.localId }.toSet() - nextIds
        val editor = preferences.edit()
            .putStringSet(OWNED, nextIds)
            .putStringSet(RETIRED, ids(RETIRED) + retiredIds)
        if (candidate != null) {
            editor.putStringSet(STAGED, ids(STAGED) - candidate.localId)
        }
        if (!editor.commit()) return false
        cleanRetired(retiredIds)
        return true
    }

    /** Once Room has committed all rows, it is the sole owner of those files. */
    fun handoffToPersisted(items: List<AdvisorAttachment>): Boolean {
        val committed = items.map { it.localId }.toSet()
        return editIds(OWNED, ids(OWNED) - committed).commit()
    }

    /** Claim before the interrupted USER message is removed from Room. */
    fun claimRecovered(items: List<AdvisorAttachment>): Boolean {
        val updated = ids(OWNED) + items.map { it.localId }
        return editIds(OWNED, updated).commit()
    }

    fun clearPending(items: List<AdvisorAttachment>): Boolean =
        publishSelection(emptyList(), items)

    private fun retireAndDelete(localIds: Set<String>, staged: Boolean = false) {
        if (localIds.isEmpty()) return
        val editor = preferences.edit().putStringSet(RETIRED, ids(RETIRED) + localIds)
        if (staged) editor.putStringSet(STAGED, ids(STAGED) - localIds)
        if (editor.commit()) cleanRetired(localIds)
    }

    private fun cleanRetired(localIds: Set<String>) {
        for (id in localIds) {
            if (runCatching { storage.delete(id) }.getOrDefault(false)) {
                preferences.edit().putStringSet(RETIRED, ids(RETIRED) - id).commit()
            }
        }
    }

    suspend fun reconcileAfterStartup(
        restored: List<AdvisorAttachment>,
        isPersisted: suspend (String) -> Boolean,
    ): List<AdvisorAttachment> {
        val legacyOwned = listOfNotNull(preferences.getString("local_id", null))
        val legacyStaged = listOfNotNull(preferences.getString("staged_local_id", null))
        val legacyRetired = listOfNotNull(preferences.getString("retired_local_id", null))
        // Do not reclaim already persisted USER attachments from a stale saved snapshot.
        val usable = restored.take(3).filter {
            storage.exists(it.localId) && !isPersisted(it.localId)
        }.distinctBy { it.localId }
        val keep = usable.map { it.localId }.toSet()
        val leftovers = ids(OWNED) + ids(STAGED) + ids(RETIRED) +
            legacyOwned + legacyStaged + legacyRetired
        // One committed preferences transaction, so a crash cannot lose both
        // the restored owned set and pending retirement references.
        if (!preferences.edit()
                .putStringSet(OWNED, keep)
                .putStringSet(STAGED, emptySet())
                .putStringSet(RETIRED, leftovers - keep)
                .remove("local_id").remove("staged_local_id").remove("retired_local_id")
                .commit()
        ) return usable

        val removable = leftovers - keep
        for (id in removable) {
            if (!isPersisted(id)) cleanRetired(setOf(id))
            else preferences.edit().putStringSet(RETIRED, ids(RETIRED) - id).commit()
        }
        return usable
    }

    private companion object {
        const val OWNED = "multi_owned_ids"
        const val STAGED = "multi_staged_ids"
        const val RETIRED = "multi_retired_ids"
    }
}

internal const val MAX_ADVISOR_ATTACHMENTS = 3
internal const val MAX_ADVISOR_ATTACHMENT_TOTAL_BYTES = 24L * 1024 * 1024

internal fun appendOrReplaceAttachment(
    items: List<AdvisorAttachment>,
    candidate: AdvisorAttachment,
    replaceLocalId: String? = null,
): List<AdvisorAttachment>? {
    val index = replaceLocalId?.let { id -> items.indexOfFirst { it.localId == id } } ?: -1
    if (replaceLocalId != null && index < 0) return null
    if (items.any { it.localId == candidate.localId }) return null
    if (index < 0 && items.size >= MAX_ADVISOR_ATTACHMENTS) return null
    val next = items.toMutableList()
    if (index >= 0) next[index] = candidate else next.add(candidate)
    return next.takeIf {
        it.size <= MAX_ADVISOR_ATTACHMENTS &&
            it.sumOf { part -> part.byteSize } <= MAX_ADVISOR_ATTACHMENT_TOTAL_BYTES
    }
}

internal fun canSendAdvisorComposer(
    enabled: Boolean,
    importInProgress: Boolean,
    text: String,
    attachments: List<AdvisorAttachment>,
): Boolean =
    enabled && !importInProgress &&
        (text.isNotBlank() || attachments.isNotEmpty()) &&
        attachments.size <= MAX_ADVISOR_ATTACHMENTS &&
        attachments.sumOf { it.byteSize } <= MAX_ADVISOR_ATTACHMENT_TOTAL_BYTES

internal val PendingAttachmentsSaver = Saver<MutableState<List<AdvisorAttachment>>, List<Any?>>(
    save = { state ->
        state.value.flatMap { value ->
            listOf(
                value.type.name, value.displayName, value.mimeType, value.localId,
                value.byteSize, value.width, value.height, value.createdAt,
            )
        }
    },
    restore = { saved ->
        mutableStateOf(saved.chunked(8).mapNotNull { part ->
            if (part.size != 8) null else validatedAttachmentOrNull(
                type = part[0] as String, displayName = part[1] as String,
                mimeType = part[2] as String, localId = part[3] as String,
                byteSize = part[4] as Long, width = part[5] as Int?,
                height = part[6] as Int?, createdAt = part[7] as Long,
            )
        }.take(MAX_ADVISOR_ATTACHMENTS))
    },
)
