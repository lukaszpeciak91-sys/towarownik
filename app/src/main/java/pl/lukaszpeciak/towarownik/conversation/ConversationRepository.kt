package pl.lukaszpeciak.towarownik.conversation

import java.math.BigDecimal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot

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
    val messages: List<PersistedMessage>,
)

internal data class PersistedMessage(
    val id: Long,
    val role: String,
    val text: String,
    val createdAt: Long,
    val products: List<VerifiedProductSnapshot>,
)

internal data class UserTurnStart(
    val conversationId: Long,
    val previousResponseId: String?,
)

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
    ): UserTurnStart {
        val normalized = normalizeConversationText(text)
        require(normalized.isNotBlank())

        return if (conversationId == null) {
            val (newId, previousResponseId) =
                dao.createWithFirstUserMessage(
                    title = deriveConversationTitle(normalized),
                    text = normalized,
                    createdAt = createdAt,
                )
            UserTurnStart(
                conversationId = newId,
                previousResponseId = previousResponseId,
            )
        } else {
            UserTurnStart(
                conversationId = conversationId,
                previousResponseId = dao.appendUserMessage(
                    conversationId = conversationId,
                    text = normalized,
                    createdAt = createdAt,
                ),
            )
        }
    }

    suspend fun completeAssistantTurn(
        conversationId: Long,
        text: String,
        finalResponseId: String,
        products: List<VerifiedProductSnapshot> = emptyList(),
        createdAt: Long = now(),
    ) {
        dao.completeAssistantTurn(
            conversationId = conversationId,
            text = text,
            createdAt = createdAt,
            lastResponseId = finalResponseId,
            products = products,
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
                                verifiedAt = product.verifiedAt,
                            )
                        },
                )
            },
    )

private val CONVERSATION_WHITESPACE = Regex("""[\s\p{Cc}]+""")
