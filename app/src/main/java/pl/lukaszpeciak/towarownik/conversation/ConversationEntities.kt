package pl.lukaszpeciak.towarownik.conversation

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

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
    val grossPrice: String?,
    val productUrl: String,
    val verifiedAt: Long,
)

internal data class MessageWithProducts(
    @Embedded
    val message: MessageEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "messageId",
    )
    val products: List<MessageProductEntity>,
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
