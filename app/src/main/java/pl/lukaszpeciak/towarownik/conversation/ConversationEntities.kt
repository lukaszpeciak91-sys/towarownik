package pl.lukaszpeciak.towarownik.conversation

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import pl.lukaszpeciak.towarownik.product.DEFAULT_OBI_STORE_NUMBER

internal const val MESSAGE_ROLE_USER = "USER"
internal const val MESSAGE_ROLE_ASSISTANT = "ASSISTANT"

@Entity(tableName = "conversations")
internal data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastResponseId: String?,
    val draft: String,
    val storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    val providerId: String = "obi-pl",
    val branchId: String = storeNumber,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("conversationId"),
    ],
)
internal data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val text: String,
    val createdAt: Long,
    val advisorTraceId: String? = null,
)

@Entity(
    tableName = "message_products",
    primaryKeys = ["messageId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("messageId"),
    ],
)
internal data class MessageProductEntity(
    val messageId: Long,
    val position: Int,
    val obik: String,
    val name: String,
    val stock: Int?,
    val centralStock: Int? = null,
    val grossPrice: String?,
    val productUrl: String,
    val verifiedAt: Long,
    val storeNumber: String = DEFAULT_OBI_STORE_NUMBER,
    val imageUrl: String? = null,
    val providerId: String = "obi-pl",
    val productId: String = obik,
    val branchId: String = storeNumber,
    val articleNumber: String? = null,
)

@Entity(
    tableName = "message_sources",
    primaryKeys = ["messageId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("messageId"),
    ],
)
internal data class MessageSourceEntity(
    val messageId: Long,
    val position: Int,
    val title: String,
    val url: String,
    val startIndex: Int?,
    val endIndex: Int?,
)

@Entity(
    tableName = "message_search_actions",
    primaryKeys = ["messageId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("messageId"),
    ],
)
internal data class MessageSearchActionEntity(
    val messageId: Long,
    val position: Int,
    val query: String,
    val storeNumber: String,
    val reportedTotalCount: Int,
)

@Entity(
    tableName = "message_attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId"), Index(value = ["localId"], unique = true)],
)
internal data class MessageAttachmentEntity(
    @PrimaryKey
    val messageId: Long,
    val type: String,
    val displayName: String,
    val mimeType: String,
    val localId: String,
    val byteSize: Long,
    val width: Int?,
    val height: Int?,
    val createdAt: Long,
)

internal data class MessageWithProducts(
    @Embedded
    val message: MessageEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "messageId",
    )
    val products: List<MessageProductEntity>,
    @Relation(
        parentColumn = "id",
        entityColumn = "messageId",
    )
    val sources: List<MessageSourceEntity> = emptyList(),
    @Relation(
        parentColumn = "id",
        entityColumn = "messageId",
    )
    val searchActions: List<MessageSearchActionEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val attachments: List<MessageAttachmentEntity> = emptyList(),
)

internal data class ConversationWithMessages(
    @Embedded
    val conversation: ConversationEntity,
    @Relation(
        entity = MessageEntity::class,
        parentColumn = "id",
        entityColumn = "conversationId",
    )
    val messages: List<MessageWithProducts>,
)
