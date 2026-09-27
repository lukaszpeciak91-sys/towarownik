package pl.lukaszpeciak.towarownik.conversation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot

@Dao
internal abstract class ConversationDao {
    @Query(
        """
        SELECT * FROM conversations
        ORDER BY updatedAt DESC, id DESC
        """,
    )
    abstract fun observeAllConversations(): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT c.* FROM conversations c
        WHERE c.title LIKE :pattern ESCAPE '\' COLLATE NOCASE
           OR EXISTS (
                SELECT 1 FROM messages m
                WHERE m.conversationId = c.id
                  AND m.text LIKE :pattern ESCAPE '\' COLLATE NOCASE
           )
        ORDER BY c.updatedAt DESC, c.id DESC
        """,
    )
    abstract fun observeMatchingConversations(
        pattern: String,
    ): Flow<List<ConversationEntity>>

    @Query(
        """
        SELECT * FROM conversations
        ORDER BY updatedAt DESC, id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun getMostRecentConversation(): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    abstract suspend fun getConversation(
        conversationId: Long,
    ): ConversationEntity?

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    abstract suspend fun deleteConversation(
        conversationId: Long,
    ): Int

    @Query("DELETE FROM conversations WHERE updatedAt < :cutoffExclusive")
    abstract suspend fun deleteConversationsUpdatedBefore(
        cutoffExclusive: Long,
    ): Int

    @Transaction
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    abstract suspend fun getConversationWithMessages(
        conversationId: Long,
    ): ConversationWithMessages?

    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY createdAt DESC, id DESC
        LIMIT 1
        """,
    )
    protected abstract suspend fun getLastMessage(
        conversationId: Long,
    ): MessageEntity?

    @Insert
    protected abstract suspend fun insertConversation(
        conversation: ConversationEntity,
    ): Long

    @Insert
    protected abstract suspend fun insertMessage(
        message: MessageEntity,
    ): Long

    @Insert
    protected abstract suspend fun insertMessageProducts(
        products: List<MessageProductEntity>,
    )

    @Query("DELETE FROM messages WHERE id = :messageId")
    protected abstract suspend fun deleteMessage(
        messageId: Long,
    )

    @Query(
        """
        UPDATE conversations
        SET draft = :draft,
            updatedAt = :updatedAt
        WHERE id = :conversationId
        """,
    )
    abstract suspend fun updateDraft(
        conversationId: Long,
        draft: String,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE conversations
        SET storeNumber = :storeNumber,
            updatedAt = :updatedAt
        WHERE id = :conversationId
        """,
    )
    abstract suspend fun updateStoreNumber(
        conversationId: Long,
        storeNumber: String,
        updatedAt: Long,
    ): Int

    @Query(
        """
        UPDATE conversations
        SET draft = '',
            updatedAt = :updatedAt
        WHERE id = :conversationId
        """,
    )
    protected abstract suspend fun markUserTurnStarted(
        conversationId: Long,
        updatedAt: Long,
    )

    @Query(
        """
        UPDATE conversations
        SET lastResponseId = :lastResponseId,
            draft = '',
            updatedAt = :updatedAt
        WHERE id = :conversationId
        """,
    )
    protected abstract suspend fun markAssistantTurnCompleted(
        conversationId: Long,
        lastResponseId: String,
        updatedAt: Long,
    )

    @Transaction
    open suspend fun createWithFirstUserMessage(
        title: String,
        text: String,
        createdAt: Long,
        storeNumber: String,
    ): Pair<Long, String?> {
        val conversationId = insertConversation(
            ConversationEntity(
                title = title,
                createdAt = createdAt,
                updatedAt = createdAt,
                lastResponseId = null,
                draft = "",
                storeNumber = storeNumber,
            ),
        )
        insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = MESSAGE_ROLE_USER,
                text = text,
                createdAt = createdAt,
            ),
        )
        return conversationId to null
    }

    @Transaction
    open suspend fun appendUserMessage(
        conversationId: Long,
        text: String,
        createdAt: Long,
        expectedStoreNumber: String,
    ): String? {
        val conversation = checkNotNull(getConversation(conversationId))
        require(conversation.storeNumber == expectedStoreNumber)
        insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = MESSAGE_ROLE_USER,
                text = text,
                createdAt = createdAt,
            ),
        )
        markUserTurnStarted(
            conversationId = conversationId,
            updatedAt = createdAt,
        )
        return conversation.lastResponseId
    }

    @Transaction
    open suspend fun completeAssistantTurn(
        conversationId: Long,
        text: String,
        createdAt: Long,
        lastResponseId: String,
        products: List<VerifiedProductSnapshot>,
    ) {
        checkNotNull(getConversation(conversationId))
        require(products.size <= 5)
        require(products.map { it.key }.distinct().size == products.size)
        val messageId = insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = MESSAGE_ROLE_ASSISTANT,
                text = text,
                createdAt = createdAt,
            ),
        )
        if (products.isNotEmpty()) {
            insertMessageProducts(
                products.mapIndexed { position, product ->
                    MessageProductEntity(
                        messageId = messageId,
                        position = position,
                        obik = product.obik,
                        name = product.name,
                        stock = product.stock,
                        grossPrice = product.grossPrice?.toPlainString(),
                        productUrl = product.productUrl,
                        verifiedAt = product.verifiedAt,
                        storeNumber = product.storeNumber,
                    )
                },
            )
        }
        markAssistantTurnCompleted(
            conversationId = conversationId,
            lastResponseId = lastResponseId,
            updatedAt = createdAt,
        )
    }

    @Transaction
    open suspend fun recoverInterruptedTurn(
        conversationId: Long,
        recoveredAt: Long,
    ): Boolean {
        val lastMessage = getLastMessage(conversationId) ?: return false
        if (lastMessage.role != MESSAGE_ROLE_USER) return false

        deleteMessage(lastMessage.id)
        updateDraft(
            conversationId = conversationId,
            draft = lastMessage.text,
            updatedAt = recoveredAt,
        )
        return true
    }
}
