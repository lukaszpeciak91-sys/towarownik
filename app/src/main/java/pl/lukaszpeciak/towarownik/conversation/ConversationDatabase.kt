package pl.lukaszpeciak.towarownik.conversation

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MessageProductEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
internal abstract class ConversationDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao

    companion object {
        @Volatile
        private var instance: ConversationDatabase? = null

        fun get(context: Context): ConversationDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ConversationDatabase::class.java,
                    "towarownik-conversations.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { database ->
                        instance = database
                    }
            }
    }
}


internal val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_products` (
                `messageId` INTEGER NOT NULL,
                `position` INTEGER NOT NULL,
                `obik` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `stock` INTEGER,
                `grossPrice` TEXT,
                `productUrl` TEXT NOT NULL,
                `verifiedAt` INTEGER NOT NULL,
                PRIMARY KEY(`messageId`, `position`),
                FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_message_products_messageId`
            ON `message_products` (`messageId`)
            """.trimIndent(),
        )
    }
}
