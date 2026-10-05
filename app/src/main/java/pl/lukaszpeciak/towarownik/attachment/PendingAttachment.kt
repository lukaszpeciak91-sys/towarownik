package pl.lukaszpeciak.towarownik.attachment

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
