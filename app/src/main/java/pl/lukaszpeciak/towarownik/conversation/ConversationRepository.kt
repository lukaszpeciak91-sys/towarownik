package pl.lukaszpeciak.towarownik.conversation

import java.math.BigDecimal
import java.net.URI
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.product.isSupportedObiStoreNumber
import pl.lukaszpeciak.towarownik.product.provider.BranchId
import pl.lukaszpeciak.towarownik.product.provider.DEFAULT_WORKING_PROFILE
import pl.lukaszpeciak.towarownik.product.provider.OBI_PROVIDER_ID
import pl.lukaszpeciak.towarownik.product.provider.ProviderId
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
    ): Int =
        dao.deleteConversationsUpdatedBefore(
            cutoffExclusive = nowMillis - CONVERSATION_RETENTION_MILLIS,
        )

    suspend fun deleteConversation(
        conversationId: Long,
    ): Boolean =
        dao.deleteConversation(conversationId) > 0

    suspend fun loadMostRecentRecoveringInterrupted():
        PersistedConversation? {
        val recent = dao.getMostRecentConversation() ?: return null
        dao.recoverInterruptedTurn(
            conversationId = recent.id,
            recoveredAt = now(),
        )
        return load(recent.id)
    }

    suspend fun loadRecoveringInterrupted(
        conversationId: Long,
    ): PersistedConversation? {
        dao.recoverInterruptedTurn(
            conversationId = conversationId,
            recoveredAt = now(),
        )
        return load(conversationId)
    }

    suspend fun load(
        conversationId: Long,
    ): PersistedConversation? =
        dao.getConversationWithMessages(conversationId)?.toPersisted()

    suspend fun beginUserTurn(
        conversationId: Long?,
        text: String,
        createdAt: Long = now(),
        workingProfile: WorkingProfile = DEFAULT_WORKING_PROFILE,
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

    suspend fun updateStoreNumber(
        conversationId: Long,
        storeNumber: String,
    ): Boolean {
        require(isSupportedObiStoreNumber(storeNumber))
        return dao.updateStoreNumber(
            conversationId = conversationId,
            storeNumber = storeNumber,
            updatedAt = now(),
        ) > 0
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

    suspend fun recoverInterruptedTurn(
        conversationId: Long,
    ): PersistedConversation? {
        dao.recoverInterruptedTurn(
            conversationId = conversationId,
            recoveredAt = now(),
        )
        return load(conversationId)
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

private fun ConversationWithMessages.toPersisted(): PersistedConversation =
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
                                grossPrice = product.grossPrice?.let(::BigDecimal),
                                productUrl = product.productUrl,
                                primaryImageUrl = product.imageUrl,
                                verifiedAt = product.verifiedAt,
                                storeNumber = product.storeNumber,
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
                )
            },
    )

private val CONVERSATION_WHITESPACE = Regex("""[\s\p{Cc}]+""")
