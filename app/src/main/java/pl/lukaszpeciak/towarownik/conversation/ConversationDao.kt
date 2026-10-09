package pl.lukaszpeciak.towarownik.conversation

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import pl.lukaszpeciak.towarownik.product.VerifiedProductSnapshot
import pl.lukaszpeciak.towarownik.attachment.AdvisorAttachment
import pl.lukaszpeciak.towarownik.attachment.validatedAttachmentOrNull

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

    @Query("SELECT ma.localId FROM message_attachments ma JOIN messages m ON m.id = ma.messageId WHERE m.conversationId = :conversationId")
    abstract suspend fun getAttachmentIds(conversationId: Long): List<String>

    @Query(
        "SELECT EXISTS(" +
            "SELECT 1 FROM message_attachments WHERE localId = :localId" +
            ")",
    )
    abstract suspend fun hasAttachmentLocalId(localId: String): Boolean

    @Query(
        """
        SELECT ma.localId
        FROM messages m
        LEFT JOIN message_attachments ma ON ma.messageId = m.id
        WHERE m.conversationId = :conversationId
        ORDER BY m.createdAt DESC, m.id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun getLastMessageAttachmentId(
        conversationId: Long,
    ): String?

    @Query(
        """
        SELECT ma.*
        FROM messages m
        JOIN message_attachments ma ON ma.messageId = m.id
        WHERE m.conversationId = :conversationId
        ORDER BY m.createdAt DESC, m.id DESC
        LIMIT 1
        """,
    )
    abstract suspend fun getLastMessageAttachment(
        conversationId: Long,
    ): MessageAttachmentEntity?

    @Query(
        """
        SELECT ma.* FROM message_attachments ma
        WHERE ma.messageId = (
            SELECT m.id FROM messages m
            WHERE m.conversationId = :conversationId
            ORDER BY m.createdAt DESC, m.id DESC LIMIT 1
        )
        ORDER BY ma.position ASC
        """,
    )
    abstract suspend fun getLastMessageAttachments(
        conversationId: Long,
    ): List<MessageAttachmentEntity>

    @Query(
        """
        SELECT ma.localId FROM message_attachments ma
        WHERE ma.messageId = (
            SELECT m.id FROM messages m
            WHERE m.conversationId = :conversationId
            ORDER BY m.createdAt DESC, m.id DESC LIMIT 1
        )
        ORDER BY ma.position ASC
        """,
    )
    abstract suspend fun getLastMessageAttachmentIds(
        conversationId: Long,
    ): List<String>


    @Query("SELECT ma.localId FROM message_attachments ma JOIN messages m ON m.id = ma.messageId JOIN conversations c ON c.id = m.conversationId WHERE c.updatedAt < :cutoffExclusive")
    abstract suspend fun getAttachmentIdsUpdatedBefore(cutoffExclusive: Long): List<String>

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

    @Insert
    protected abstract suspend fun insertMessageSources(
        sources: List<MessageSourceEntity>,
    )

    @Insert
    protected abstract suspend fun insertMessageSearchActions(
        actions: List<MessageSearchActionEntity>,
    )

    @Insert
    protected abstract suspend fun insertMessageAttachment(attachment: MessageAttachmentEntity)

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
        providerId: String,
        branchId: String,
        attachment: AdvisorAttachment? = null,
        attachments: List<AdvisorAttachment> = listOfNotNull(attachment),
    ): Pair<Long, String?> {
        val conversationId = insertConversation(
            ConversationEntity(
                title = title,
                createdAt = createdAt,
                updatedAt = createdAt,
                lastResponseId = null,
                draft = "",
                storeNumber = branchId,
                providerId = providerId,
                branchId = branchId,
            ),
        )
        val messageId = insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = MESSAGE_ROLE_USER,
                text = text,
                createdAt = createdAt,
            ),
        )
        require(attachments.size <= 3)
        require(attachments.map { it.localId }.distinct().size == attachments.size)
        require(attachments.sumOf { it.byteSize } <= 24L * 1024L * 1024L)
        attachments.forEachIndexed { position, item ->
            insertMessageAttachment(item.toEntity(messageId, position))
        }
        return conversationId to null
    }

    @Transaction
    open suspend fun appendUserMessage(
        conversationId: Long,
        text: String,
        createdAt: Long,
        expectedProviderId: String,
        expectedBranchId: String,
        attachment: AdvisorAttachment? = null,
        attachments: List<AdvisorAttachment> = listOfNotNull(attachment),
    ): String? {
        val conversation = checkNotNull(getConversation(conversationId))
        require(conversation.providerId == expectedProviderId)
        require(conversation.branchId == expectedBranchId)
        val messageId = insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = MESSAGE_ROLE_USER,
                text = text,
                createdAt = createdAt,
            ),
        )
        require(attachments.size <= 3)
        require(attachments.map { it.localId }.distinct().size == attachments.size)
        require(attachments.sumOf { it.byteSize } <= 24L * 1024L * 1024L)
        attachments.forEachIndexed { position, item ->
            insertMessageAttachment(item.toEntity(messageId, position))
        }
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
        sources: List<PersistedWebSource> = emptyList(),
        searchActions: List<PersistedSearchAction> = emptyList(),
        advisorTraceId: String? = null,
    ) {
        checkNotNull(getConversation(conversationId))
        require(products.size <= 5)
        require(products.map { it.key }.distinct().size == products.size)
        require(sources.size <= 6)
        require(sources.map { it.url }.distinct().size == sources.size)
        require(
            searchActions
                .map { it.storeNumber to it.query }
                .distinct()
                .size == searchActions.size,
        )
        require(searchActions.all { persistedSearchActionOrNull(
            query = it.query,
            storeNumber = it.storeNumber,
            reportedTotalCount = it.reportedTotalCount,
        ) == it })
        require(
            sources.all {
                persistedWebSourceOrNull(
                    title = it.title,
                    url = it.url,
                    startIndex = it.startIndex,
                    endIndex = it.endIndex,
                ) == it &&
                    (
                        it.endIndex == null ||
                            it.endIndex <= text.length
                    )
            },
        )
        val messageId = insertMessage(
            MessageEntity(
                conversationId = conversationId,
                role = MESSAGE_ROLE_ASSISTANT,
                text = text,
                createdAt = createdAt,
                advisorTraceId = advisorTraceId,
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
                        centralStock = product.centralStock,
                        grossPrice = product.grossPrice?.toPlainString(),
                        productUrl = product.productUrl,
                        imageUrl = product.primaryImageUrl,
                        verifiedAt = product.verifiedAt,
                        storeNumber = product.storeNumber,
                        providerId = product.providerId,
                        productId = product.effectiveProductId,
                        branchId = product.effectiveBranchId,
                        articleNumber = product.articleNumber,
                    )
                },
            )
        }
        if (sources.isNotEmpty()) {
            insertMessageSources(
                sources.mapIndexed { position, source ->
                    MessageSourceEntity(
                        messageId = messageId,
                        position = position,
                        title = source.title,
                        url = source.url,
                        startIndex = source.startIndex,
                        endIndex = source.endIndex,
                    )
                },
            )
        }
        if (searchActions.isNotEmpty()) {
            insertMessageSearchActions(
                searchActions.mapIndexed { position, action ->
                    MessageSearchActionEntity(
                        messageId = messageId,
                        position = position,
                        query = action.query,
                        storeNumber = action.storeNumber,
                        reportedTotalCount = action.reportedTotalCount,
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

private fun AdvisorAttachment.toEntity(messageId: Long, position: Int): MessageAttachmentEntity {
    require(
        validatedAttachmentOrNull(
            type.name, displayName, mimeType, localId, byteSize,
            width, height, createdAt,
        ) == this,
    )
    return MessageAttachmentEntity(
        messageId = messageId,
        position = position,
        type = type.name,
        displayName = displayName,
        mimeType = mimeType,
        localId = localId,
        byteSize = byteSize,
        width = width,
        height = height,
        createdAt = createdAt,
    )
}
