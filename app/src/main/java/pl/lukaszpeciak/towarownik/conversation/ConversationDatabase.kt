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
        MessageSourceEntity::class,
        MessageSearchActionEntity::class,
        MessageAttachmentEntity::class,
    ],
    version = 13,
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
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                    )
                    .build()
                    .also { database ->
                        instance = database
                    }
            }
    }
}

internal val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE message_products ADD COLUMN stockUnit TEXT")
    }
}

internal val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_attachments_v12` (
                `messageId` INTEGER NOT NULL,
                `position` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL,
                `localId` TEXT NOT NULL,
                `byteSize` INTEGER NOT NULL,
                `width` INTEGER,
                `height` INTEGER,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`messageId`, `position`),
                FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO message_attachments_v12 (
                messageId, position, type, displayName, mimeType, localId,
                byteSize, width, height, createdAt
            )
            SELECT messageId, 0, type, displayName, mimeType, localId,
                byteSize, width, height, createdAt
            FROM message_attachments
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE message_attachments")
        db.execSQL("ALTER TABLE message_attachments_v12 RENAME TO message_attachments")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_message_attachments_messageId` " +
                "ON `message_attachments` (`messageId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_message_attachments_localId` " +
                "ON `message_attachments` (`localId`)",
        )
    }
}

internal val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE messages ADD COLUMN advisorTraceId TEXT",
        )
    }
}

internal val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_attachments` (
                `messageId` INTEGER NOT NULL,
                `type` TEXT NOT NULL,
                `displayName` TEXT NOT NULL,
                `mimeType` TEXT NOT NULL,
                `localId` TEXT NOT NULL,
                `byteSize` INTEGER NOT NULL,
                `width` INTEGER,
                `height` INTEGER,
                `createdAt` INTEGER NOT NULL,
                PRIMARY KEY(`messageId`),
                FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_message_attachments_messageId` ON `message_attachments` (`messageId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_message_attachments_localId` ON `message_attachments` (`localId`)")
    }
}

internal val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE message_products ADD COLUMN centralStock INTEGER",
        )
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


internal val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE conversations " +
                "ADD COLUMN storeNumber TEXT NOT NULL DEFAULT '075'",
        )
        db.execSQL(
            "ALTER TABLE message_products " +
                "ADD COLUMN storeNumber TEXT NOT NULL DEFAULT '075'",
        )
    }
}


internal val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_sources` (
                `messageId` INTEGER NOT NULL,
                `position` INTEGER NOT NULL,
                `title` TEXT NOT NULL,
                `url` TEXT NOT NULL,
                `startIndex` INTEGER,
                `endIndex` INTEGER,
                PRIMARY KEY(`messageId`, `position`),
                FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_message_sources_messageId`
            ON `message_sources` (`messageId`)
            """.trimIndent(),
        )
    }
}


internal val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `message_search_actions` (
                `messageId` INTEGER NOT NULL,
                `position` INTEGER NOT NULL,
                `query` TEXT NOT NULL,
                `storeNumber` TEXT NOT NULL,
                `reportedTotalCount` INTEGER NOT NULL,
                PRIMARY KEY(`messageId`, `position`),
                FOREIGN KEY(`messageId`) REFERENCES `messages`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE INDEX IF NOT EXISTS `index_message_search_actions_messageId`
            ON `message_search_actions` (`messageId`)
            """.trimIndent(),
        )
    }
}


internal val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE message_products ADD COLUMN imageUrl TEXT",
        )
    }
}


internal val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE conversations " +
                "ADD COLUMN providerId TEXT NOT NULL DEFAULT 'obi-pl'",
        )
        db.execSQL(
            "ALTER TABLE conversations " +
                "ADD COLUMN branchId TEXT NOT NULL DEFAULT '075'",
        )
        db.execSQL(
            "UPDATE conversations SET branchId = storeNumber",
        )
    }
}


internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE message_products " +
                "ADD COLUMN providerId TEXT NOT NULL DEFAULT 'obi-pl'",
        )
        db.execSQL(
            "ALTER TABLE message_products " +
                "ADD COLUMN productId TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            "ALTER TABLE message_products " +
                "ADD COLUMN branchId TEXT NOT NULL DEFAULT '075'",
        )
        db.execSQL(
            "ALTER TABLE message_products ADD COLUMN articleNumber TEXT",
        )
        db.execSQL(
            "UPDATE message_products " +
                "SET providerId = 'obi-pl', " +
                "productId = obik, branchId = storeNumber, " +
                "articleNumber = NULL",
        )
    }
}
