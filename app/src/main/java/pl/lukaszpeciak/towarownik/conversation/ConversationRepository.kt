package pl.lukaszpeciak.towarownik.conversation

import java.math.BigDecimal
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.lukaszpeciak.towarownik.attachment.AdvisorAttachment
import pl.lukaszpeciak.towarownik.attachment.AttachmentStorage
import pl.lukaszpeciak.towarownik.attachment.validatedAttachmentOrNull
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.isSupportedObiStoreNumber
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.DEFAULT_WORKING_PROFILE
import pl.lukaszpeciak.towarownik.product.provider.KWANT_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
import pl.lukaszpeciak.towarownik.product.provider.ProviderPriceScope
import pl.lukaszpeciak.towarownik.product.provider.WorkingProfile

internal const val CONVERSATION_TITLE_MAX_CHARS = 50
internal const val CONVERSATION_RETENTION_DAYS = 30L
internal const val CONVERSATION_RETENTION_MILLIS =
    CONVERSATION_RETENTION_DAYS * 24L * 60L * 60L * 1000L

internal data class ConversationSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
)

internal data class PersistedConversation(
    val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastResponseId: String?,
    val draft: String,
    val workingProfile: WorkingProfile = DEFAULT_WORKING_PROFILE,
    val storeNumber: String = workingProfile.branchId.value,
    val messages: List<PersistedMessage>,
)

internal data class PersistedWebSource(
    val title: String,
    val url: String,
    val startIndex: Int? = null,
    val endIndex: Int? = null,
)

internal data class PersistedSearchAction(
    val query: String,
    val storeNumber: String,
    val reportedTotalCount: Int,
)

internal fun persistedSearchActionOrNull(
    query: String,
    storeNumber: String,
    reportedTotalCount: Int,
): PersistedSearchAction? {
    if (
        query.isBlank() ||
        query.length > 200 ||
        !isSupportedObiStoreNumber(storeNumber) ||
        reportedTotalCount <= 0
    ) {
        return null
    }
    return PersistedSearchAction(
        query = query,
        storeNumber = storeNumber,
        reportedTotalCount = reportedTotalCount,
    )
}

internal fun persistedWebSourceOrNull(
    title: String,
    url: String,
    startIndex: Int? = null,
    endIndex: Int? = null,
): PersistedWebSource? {
    val normalizedTitle = title
        .replace(CONVERSATION_WHITESPACE, " ")
        .trim()
        .takeIf { it.isNotEmpty() && it.length <= 200 }
        ?: return null
    if (url.length !in 1..2048) return null
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (
        uri.scheme?.lowercase() != "https" ||
        uri.host.isNullOrBlank()
    ) {
        return null
    }
    if (
        !(
            (startIndex == null && endIndex == null) ||
                (
                    startIndex != null &&
                        endIndex != null &&
                        startIndex >= 0 &&
                        endIndex > startIndex
                )
        )
    ) {
        return null
    }
    return PersistedWebSource(
        title = normalizedTitle,
        url = url,
        startIndex = startIndex,
        endIndex = endIndex,
    )
}

internal data class PersistedMessage(
    val id: Long,
    val role: String,
    val text: String,
    val createdAt: Long,
    val products: List<VerifiedProductSnapshot>,
    val sources: List<PersistedWebSource> = emptyList(),
    val searchActions: List<PersistedSearchAction> = emptyList(),
    val attachment: AdvisorAttachment? = null,
)

internal data class UserTurnStart(
    val conversationId: Long,
    val previousResponseId: String?,
    val workingProfile: WorkingProfile,
) {
    val storeNumber: String
        get() = workingProfile.branchId.value
}

internal class ConversationRepository(
    private val dao: ConversationDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val attachmentStorage: AttachmentStorage? = null,
) {
    fun observeConversations(
        phrase: String,
    ): Flow<List<ConversationSummary>> {
        val normalized = phrase.trim()
        val source = if (normalized.isBlank()) {
            dao.observeAllConversations()
        } else {
            dao.observeMatchingConversations(
                pattern = "%${escapeLike(normalized)}%",
            )
        }

        return source.map { conversations ->
            conversations.map(ConversationEntity::toSummary)
        }
    }

    suspend fun cleanupExpiredConversations(
        nowMillis: Long = now(),
    ): Int {
        val cutoff = nowMillis - CONVERSATION_RETENTION_MILLIS
        val attachmentIds = dao.getAttachmentIdsUpdatedBefore(cutoff)
        val deleted = dao.deleteConversationsUpdatedBefore(cutoff)
        runCatching { attachmentStorage?.deleteAll(attachmentIds) }
        return deleted
    }

    suspend fun deleteConversation(
        conversationId: Long,
    ): Boolean {
        val attachmentIds = dao.getAttachmentIds(conversationId)
        val deleted = dao.deleteConversation(conversationId) > 0
        if (deleted) runCatching { attachmentStorage?.deleteAll(attachmentIds) }
        return deleted
    }

    suspend fun loadMostRecentRecoveringInterrupted():
        PersistedConversation? {
        val recent = dao.getMostRecentConversation() ?: return null
        recoverInterruptedTurnAndCleanup(recent.id)
        return load(recent.id)
    }

    suspend fun loadRecoveringInterrupted(
        conversationId: Long,
    ): PersistedConversation? {
        recoverInterruptedTurnAndCleanup(conversationId)
        return load(conversationId)
    }

    suspend fun load(
        conversationId: Long,
    ): PersistedConversation? =
        dao.getConversationWithMessages(conversationId)?.toPersisted(attachmentStorage)

    suspend fun beginUserTurn(
        conversationId: Long?,
        text: String,
        createdAt: Long = now(),
        workingProfile: WorkingProfile = DEFAULT_WORKING_PROFILE,
        attachment: AdvisorAttachment? = null,
    ): UserTurnStart {
        val normalized = normalizeConversationText(text)
        require(normalized.isNotBlank())

        return if (conversationId == null) {
            val (newId, previousResponseId) =
                dao.createWithFirstUserMessage(
                    title = deriveConversationTitle(normalized),
                    text = normalized,
                    createdAt = createdAt,
                    providerId = workingProfile.providerId.value,
                    branchId = workingProfile.branchId.value,
                    attachment = attachment,
                )
            UserTurnStart(
                conversationId = newId,
                previousResponseId = previousResponseId,
                workingProfile = workingProfile,
            )
        } else {
            UserTurnStart(
                conversationId = conversationId,
                previousResponseId = dao.appendUserMessage(
                    conversationId = conversationId,
                    text = normalized,
                    createdAt = createdAt,
                    expectedProviderId = workingProfile.providerId.value,
                    expectedBranchId = workingProfile.branchId.value,
                    attachment = attachment,
                ),
                workingProfile = workingProfile,
            )
        }
    }

    suspend fun beginUserTurn(
        conversationId: Long?,
        text: String,
        storeNumber: String,
        createdAt: Long = now(),
    ): UserTurnStart {
        require(isSupportedObiStoreNumber(storeNumber))
        return beginUserTurn(
            conversationId = conversationId,
            text = text,
            createdAt = createdAt,
            workingProfile = WorkingProfile(
                providerId = OBI_PROVIDER_ID,
                branchId = BranchId(storeNumber),
            ),
        )
    }

    suspend fun completeAssistantTurn(
        conversationId: Long,
        text: String,
        finalResponseId: String,
        createdAt: Long = now(),
        products: List<VerifiedProductSnapshot> = emptyList(),
        sources: List<PersistedWebSource> = emptyList(),
        searchActions: List<PersistedSearchAction> = emptyList(),
    ) {
        dao.completeAssistantTurn(
            conversationId = conversationId,
            text = text,
            createdAt = createdAt,
            lastResponseId = finalResponseId,
            products = products,
            sources = sources,
            searchActions = searchActions,
        )
    }

    suspend fun isAttachmentPersisted(
        localId: String,
    ): Boolean = dao.hasAttachmentLocalId(localId)

    suspend fun recoverInterruptedTurn(
        conversationId: Long,
    ): PersistedConversation? {
        recoverInterruptedTurnAndCleanup(conversationId)
        return load(conversationId)
    }

    private suspend fun recoverInterruptedTurnAndCleanup(
        conversationId: Long,
    ) {
        val attachmentId =
            dao.getLastMessageAttachmentId(conversationId)
        val recovered = dao.recoverInterruptedTurn(
            conversationId = conversationId,
            recoveredAt = now(),
        )
        if (recovered && attachmentId != null) {
            runCatching {
                attachmentStorage?.delete(attachmentId)
            }
        }
    }

    suspend fun updateDraft(
        conversationId: Long,
        draft: String,
    ) {
        dao.updateDraft(
            conversationId = conversationId,
            draft = draft,
            updatedAt = now(),
        )
    }
}

internal fun deriveConversationTitle(
    firstUserMessage: String,
): String {
    val normalized = normalizeConversationText(firstUserMessage)
    if (normalized.length <= CONVERSATION_TITLE_MAX_CHARS) {
        return normalized
    }

    return normalized
        .take(CONVERSATION_TITLE_MAX_CHARS - 1)
        .trimEnd() + "…"
}

private fun normalizeConversationText(value: String): String =
    value.replace(CONVERSATION_WHITESPACE, " ").trim()

private fun escapeLike(value: String): String =
    buildString {
        value.forEach { character ->
            when (character) {
                '\\', '%', '_' -> append('\\')
            }
            append(character)
        }
    }

private fun ConversationEntity.toSummary(): ConversationSummary =
    ConversationSummary(
        id = id,
        title = title,
        updatedAt = updatedAt,
    )

private fun ConversationWithMessages.toPersisted(
    attachmentStorage: AttachmentStorage?,
): PersistedConversation =
    PersistedConversation(
        id = conversation.id,
        title = conversation.title,
        createdAt = conversation.createdAt,
        updatedAt = conversation.updatedAt,
        lastResponseId = conversation.lastResponseId,
        draft = conversation.draft,
        workingProfile = WorkingProfile(
            providerId = ProviderId(conversation.providerId),
            branchId = BranchId(conversation.branchId),
        ),
        storeNumber = conversation.storeNumber,
        messages = messages
            .sortedWith(
                compareBy<MessageWithProducts> { it.message.createdAt }
                    .thenBy { it.message.id },
            )
            .map { item ->
                val message = item.message
                PersistedMessage(
                    id = message.id,
                    role = message.role,
                    text = message.text,
                    createdAt = message.createdAt,
                    products = item.products
                        .sortedBy { it.position }
                        .map { product ->
                            VerifiedProductSnapshot(
                                obik = product.obik,
                                name = product.name,
                                stock = product.stock,
                                centralStock = product.centralStock,
                                grossPrice = product.grossPrice?.let(::BigDecimal),
                                productUrl = product.productUrl,
                                primaryImageUrl = product.imageUrl,
                                verifiedAt = product.verifiedAt,
                                storeNumber = product.storeNumber,
                                providerId = product.providerId,
                                productId = product.productId,
                                branchId = product.branchId,
                                articleNumber = product.articleNumber,
                                priceScope =
                                    if (
                                        product.grossPrice != null &&
                                        product.providerId ==
                                        KWANT_PROVIDER_ID.value
                                    ) {
                                        ProviderPriceScope.ONLINE
                                    } else {
                                        null
                                    },
                            )
                        },
                    sources = item.sources
                        .sortedBy { it.position }
                        .mapNotNull { source ->
                            persistedWebSourceOrNull(
                                title = source.title,
                                url = source.url,
                                startIndex = source.startIndex,
                                endIndex = source.endIndex,
                            )
                        },
                    searchActions = item.searchActions
                        .sortedBy { it.position }
                        .mapNotNull { action ->
                            persistedSearchActionOrNull(
                                query = action.query,
                                storeNumber = action.storeNumber,
                                reportedTotalCount = action.reportedTotalCount,
                            )
                        },
                    attachment = item.attachments.singleOrNull()?.let { value ->
                        validatedAttachmentOrNull(
                            type = value.type,
                            displayName = value.displayName,
                            mimeType = value.mimeType,
                            localId = value.localId,
                            byteSize = value.byteSize,
                            width = value.width,
                            height = value.height,
                            createdAt = value.createdAt,
                        )?.takeIf { attachment ->
                            attachmentStorage?.exists(attachment.localId) != false
                        }
                    },
                )
            },
    )

private val CONVERSATION_WHITESPACE = Regex("""[\s\p{Cc}]+""")
