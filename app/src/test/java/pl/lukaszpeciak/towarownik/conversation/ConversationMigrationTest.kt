package pl.lukaszpeciak.towarownik.conversation

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationMigrationTest {
    @Test
    fun migration11To12PreservesOldFileAndSupportsThreeOrderedRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-conversation-v11-attachments.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(11) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createV2Schema(db)
                        MIGRATION_2_3.migrate(db)
                        MIGRATION_3_4.migrate(db)
                        MIGRATION_4_5.migrate(db)
                        MIGRATION_5_6.migrate(db)
                        MIGRATION_6_7.migrate(db)
                        MIGRATION_7_8.migrate(db)
                        MIGRATION_8_9.migrate(db)
                        MIGRATION_9_10.migrate(db)
                        MIGRATION_10_11.migrate(db)
                    }
                    override fun onUpgrade(
                        db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int,
                    ) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id,title,createdAt,updatedAt,lastResponseId,draft,storeNumber,providerId,branchId) " +
                    "VALUES (1,'legacy',1,1,NULL,'','075','obi-pl','075')",
            )
            db.execSQL("INSERT INTO messages (id,conversationId,role,text,createdAt) VALUES (1,1,'USER','',1)")
            val legacyId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            db.execSQL(
                "INSERT INTO message_attachments " +
                    "(messageId,type,displayName,mimeType,localId,byteSize,width,height,createdAt) " +
                    "VALUES (1,'PDF','legacy.pdf','application/pdf','$legacyId',8,NULL,NULL,1)",
            )
            MIGRATION_11_12.migrate(db)
            assertEquals("0", queryText(db, "SELECT position FROM message_attachments WHERE messageId=1"))
            assertEquals(legacyId, queryText(db, "SELECT localId FROM message_attachments WHERE messageId=1"))
            for (position in 1..2) {
                val id = position.toString().repeat(32)
                db.execSQL(
                    "INSERT INTO message_attachments " +
                        "(messageId,position,type,displayName,mimeType,localId,byteSize,width,height,createdAt) " +
                        "VALUES (1,$position,'PDF','file$position.pdf','application/pdf','$id',8,NULL,NULL,1)",
                )
            }
            val ordered = db.query(
                "SELECT position, displayName FROM message_attachments WHERE messageId=1 ORDER BY position",
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getInt(0) to cursor.getString(1))
                }
            }
            assertEquals(listOf(0 to "legacy.pdf", 1 to "file1.pdf", 2 to "file2.pdf"), ordered)
            db.execSQL("DELETE FROM messages WHERE id=1")
            assertEquals("0", queryText(db, "SELECT COUNT(*) FROM message_attachments"))
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `migration 10 to 11 preserves history and defaults trace to null`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_V10_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_V10_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createV2Schema(db)
                        MIGRATION_2_3.migrate(db)
                        MIGRATION_3_4.migrate(db)
                        MIGRATION_4_5.migrate(db)
                        MIGRATION_5_6.migrate(db)
                        MIGRATION_6_7.migrate(db)
                        MIGRATION_7_8.migrate(db)
                        MIGRATION_8_9.migrate(db)
                        MIGRATION_9_10.migrate(db)
                    }
                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int,
                    ) = Unit
                }).build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id,title,createdAt,updatedAt,lastResponseId,draft," +
                    "storeNumber,providerId,branchId) " +
                    "VALUES (1,'old',1,1,'resp_old','','075','obi-pl','075')",
            )
            db.execSQL(
                "INSERT INTO messages " +
                    "(id,conversationId,role,text,createdAt) " +
                    "VALUES (1,1,'ASSISTANT','historical answer',1)",
            )

            MIGRATION_10_11.migrate(db)

            assertEquals(
                "historical answer",
                queryText(
                    db,
                    "SELECT text FROM messages WHERE id=1",
                ),
            )
            assertEquals(
                "NULL",
                queryText(
                    db,
                    "SELECT COALESCE(advisorTraceId,'NULL') " +
                        "FROM messages WHERE id=1",
                ),
            )

            val trace = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
            db.execSQL(
                "UPDATE messages SET advisorTraceId='$trace' WHERE id=1",
            )
            assertEquals(
                trace,
                queryText(
                    db,
                    "SELECT advisorTraceId FROM messages WHERE id=1",
                ),
            )
        } finally {
            helper.close()
            context.deleteDatabase(DB_V10_NAME)
        }
    }

    @Test
    fun `migration 9 to 10 gives history zero attachments and cascades metadata`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_V9_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_V9_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createV2Schema(db)
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("INSERT INTO conversations (id,title,createdAt,updatedAt,lastResponseId,draft) VALUES (1,'old',1,1,NULL,'')")
            db.execSQL("INSERT INTO messages (id,conversationId,role,text,createdAt) VALUES (1,1,'USER','old',1)")
            MIGRATION_9_10.migrate(db)
            assertEquals("0", queryText(db, "SELECT COUNT(*) FROM message_attachments"))
            db.execSQL("INSERT INTO message_attachments (messageId,type,displayName,mimeType,localId,byteSize,width,height,createdAt) VALUES (1,'PDF','doc.pdf','application/pdf','00000000000000000000000000000000',1,NULL,NULL,1)")
            db.execSQL("DELETE FROM messages WHERE id=1")
            assertEquals("0", queryText(db, "SELECT COUNT(*) FROM message_attachments"))
        } finally {
            helper.close()
            context.deleteDatabase(DB_V9_NAME)
        }
    }

    @Test
    fun `migration 2 to 3 defaults existing conversation and products to 075`() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_NAME)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(
                            db: SupportSQLiteDatabase,
                        ) {
                            createV2Schema(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id,title,createdAt,updatedAt,lastResponseId,draft) " +
                    "VALUES (1,'old',1,1,NULL,'')",
            )
            db.execSQL(
                "INSERT INTO messages " +
                    "(id,conversationId,role,text,createdAt) " +
                    "VALUES (1,1,'ASSISTANT','answer',1)",
            )
            db.execSQL(
                "INSERT INTO message_products " +
                    "(messageId,position,obik,name,stock,grossPrice," +
                    "productUrl,verifiedAt) " +
                    "VALUES (1,0,'3496072','Product',25,'12.99'," +
                    "'https://example.invalid/p/3496072',1)",
            )

            MIGRATION_2_3.migrate(db)

            assertEquals(
                "075",
                queryText(
                    db,
                    "SELECT storeNumber FROM conversations WHERE id=1",
                ),
            )
            assertEquals(
                "075",
                queryText(
                    db,
                    "SELECT storeNumber FROM message_products " +
                        "WHERE messageId=1 AND position=0",
                ),
            )
        } finally {
            helper.close()
            context.deleteDatabase(DB_NAME)
        }
    }

    @Test
    fun `migration 3 to 4 gives historical messages zero sources and cascades new sources`() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_V3_NAME)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_V3_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(3) {
                        override fun onCreate(
                            db: SupportSQLiteDatabase,
                        ) {
                            createV2Schema(db)
                            db.execSQL(
                                "ALTER TABLE conversations " +
                                    "ADD COLUMN storeNumber TEXT NOT NULL DEFAULT '075'",
                            )
                            db.execSQL(
                                "ALTER TABLE message_products " +
                                    "ADD COLUMN storeNumber TEXT NOT NULL DEFAULT '075'",
                            )
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id,title,createdAt,updatedAt,lastResponseId,draft,storeNumber) " +
                    "VALUES (1,'old',1,1,NULL,'','075')",
            )
            db.execSQL(
                "INSERT INTO messages " +
                    "(id,conversationId,role,text,createdAt) " +
                    "VALUES (1,1,'ASSISTANT','answer',1)",
            )

            MIGRATION_3_4.migrate(db)

            assertEquals(
                "0",
                queryText(
                    db,
                    "SELECT COUNT(*) FROM message_sources " +
                        "WHERE messageId=1",
                ),
            )

            db.execSQL(
                "INSERT INTO message_sources " +
                    "(messageId,position,title,url,startIndex,endIndex) " +
                    "VALUES (1,0,'Manual','https://example.com/manual',0,6)",
            )
            assertEquals(
                "1",
                queryText(
                    db,
                    "SELECT COUNT(*) FROM message_sources " +
                        "WHERE messageId=1",
                ),
            )
            assertEquals(
                "6",
                queryText(
                    db,
                    "SELECT endIndex FROM message_sources " +
                        "WHERE messageId=1 AND position=0",
                ),
            )

            db.execSQL("DELETE FROM messages WHERE id=1")
            assertEquals(
                "0",
                queryText(
                    db,
                    "SELECT COUNT(*) FROM message_sources",
                ),
            )
        } finally {
            helper.close()
            context.deleteDatabase(DB_V3_NAME)
        }
    }

    @Test
    fun `migration 4 to 5 gives historical messages zero search actions and cascades new actions`() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_V4_NAME)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_V4_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(4) {
                        override fun onCreate(
                            db: SupportSQLiteDatabase,
                        ) {
                            createV2Schema(db)
                            db.execSQL(
                                "ALTER TABLE conversations " +
                                    "ADD COLUMN storeNumber TEXT NOT NULL DEFAULT '075'",
                            )
                            db.execSQL(
                                "ALTER TABLE message_products " +
                                    "ADD COLUMN storeNumber TEXT NOT NULL DEFAULT '075'",
                            )
                            MIGRATION_3_4.migrate(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id,title,createdAt,updatedAt,lastResponseId,draft,storeNumber) " +
                    "VALUES (1,'old',1,1,NULL,'','075')",
            )
            db.execSQL(
                "INSERT INTO messages " +
                    "(id,conversationId,role,text,createdAt) " +
                    "VALUES (1,1,'ASSISTANT','answer',1)",
            )

            MIGRATION_4_5.migrate(db)

            assertEquals(
                "0",
                queryText(
                    db,
                    "SELECT COUNT(*) FROM message_search_actions " +
                        "WHERE messageId=1",
                ),
            )

            db.execSQL(
                "INSERT INTO message_search_actions " +
                    "(messageId,position,query,storeNumber,reportedTotalCount) " +
                    "VALUES (1,0,'czarne trytytki','074',27)",
            )
            assertEquals(
                "074",
                queryText(
                    db,
                    "SELECT storeNumber FROM message_search_actions " +
                        "WHERE messageId=1 AND position=0",
                ),
            )
            assertEquals(
                "27",
                queryText(
                    db,
                    "SELECT reportedTotalCount FROM message_search_actions " +
                        "WHERE messageId=1 AND position=0",
                ),
            )

            db.execSQL("DELETE FROM messages WHERE id=1")
            assertEquals(
                "0",
                queryText(
                    db,
                    "SELECT COUNT(*) FROM message_search_actions",
                ),
            )
        } finally {
            helper.close()
            context.deleteDatabase(DB_V4_NAME)
        }
    }

    @Test
    fun `migration 5 to 6 preserves historical product rows with null image url`() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_V5_NAME)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_V5_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(
                            db: SupportSQLiteDatabase,
                        ) {
                            createV2Schema(db)
                            MIGRATION_2_3.migrate(db)
                            MIGRATION_3_4.migrate(db)
                            MIGRATION_4_5.migrate(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id,title,createdAt,updatedAt,lastResponseId,draft,storeNumber) " +
                    "VALUES (1,'old',1,1,NULL,'','075')",
            )
            db.execSQL(
                "INSERT INTO messages " +
                    "(id,conversationId,role,text,createdAt) " +
                    "VALUES (1,1,'ASSISTANT','answer',1)",
            )
            db.execSQL(
                "INSERT INTO message_products " +
                    "(messageId,position,obik,name,stock,grossPrice," +
                    "productUrl,verifiedAt,storeNumber) " +
                    "VALUES (1,0,'3496072','Product',25,'12.99'," +
                    "'https://www.obi.pl/p/3496072',1,'075')",
            )

            MIGRATION_5_6.migrate(db)

            assertEquals(
                "1",
                queryText(
                    db,
                    "SELECT COUNT(*) FROM message_products " +
                        "WHERE messageId=1 AND position=0",
                ),
            )
            val imageCursor = db.query(
                "SELECT imageUrl FROM message_products " +
                    "WHERE messageId=1 AND position=0",
            )
            imageCursor.use {
                check(it.moveToFirst())
                assertEquals(null, it.getString(0))
            }

            db.execSQL(
                "UPDATE message_products SET imageUrl=" +
                    "'https://bilder.obi.pl/example/pr08A/image.jpeg' " +
                    "WHERE messageId=1 AND position=0",
            )
            assertEquals(
                "https://bilder.obi.pl/example/pr08A/image.jpeg",
                queryText(
                    db,
                    "SELECT imageUrl FROM message_products " +
                        "WHERE messageId=1 AND position=0",
                ),
            )
        } finally {
            helper.close()
            context.deleteDatabase(DB_V5_NAME)
        }
    }

    @Test
    fun `migration 6 to 7 assigns OBI provider and preserves existing store as branch`() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_V6_NAME)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_V6_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createV2Schema(db)
                            MIGRATION_2_3.migrate(db)
                            MIGRATION_3_4.migrate(db)
                            MIGRATION_4_5.migrate(db)
                            MIGRATION_5_6.migrate(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id,title,createdAt,updatedAt,lastResponseId,draft,storeNumber) " +
                    "VALUES (1,'legacy',1,1,NULL,'','074')",
            )
            db.execSQL(
                "INSERT INTO messages " +
                    "(id,conversationId,role,text,createdAt) " +
                    "VALUES (1,1,'USER','history survives',1)",
            )
            db.execSQL(
                "INSERT INTO message_products " +
                    "(messageId,position,obik,name,stock,grossPrice," +
                    "productUrl,verifiedAt,storeNumber,imageUrl) " +
                    "VALUES (1,0,'3496072','Legacy product',4,'12.99'," +
                    "'https://www.obi.pl/p/3496072/test',1,'074',NULL)",
            )

            MIGRATION_6_7.migrate(db)

            assertEquals(
                "obi-pl",
                queryText(
                    db,
                    "SELECT providerId FROM conversations WHERE id=1",
                ),
            )
            assertEquals(
                "074",
                queryText(
                    db,
                    "SELECT branchId FROM conversations WHERE id=1",
                ),
            )
            assertEquals(
                "history survives",
                queryText(
                    db,
                    "SELECT text FROM messages WHERE id=1",
                ),
            )
            assertEquals(
                "3496072",
                queryText(
                    db,
                    "SELECT obik FROM message_products " +
                        "WHERE messageId=1 AND position=0",
                ),
            )
            assertEquals(
                "074",
                queryText(
                    db,
                    "SELECT storeNumber FROM message_products " +
                        "WHERE messageId=1 AND position=0",
                ),
            )
        } finally {
            helper.close()
            context.deleteDatabase(DB_V6_NAME)
        }
    }

    @Test
    fun `migration 7 to 8 preserves PR66 history and maps OBI product identity`() {
        val context =
            ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(DB_V7_NAME)

        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_V7_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            createV2Schema(db)
                            MIGRATION_2_3.migrate(db)
                            MIGRATION_3_4.migrate(db)
                            MIGRATION_4_5.migrate(db)
                            MIGRATION_5_6.migrate(db)
                            MIGRATION_6_7.migrate(db)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        try {
            val db = helper.writableDatabase
            db.execSQL(
                "INSERT INTO conversations " +
                    "(id,title,createdAt,updatedAt,lastResponseId,draft," +
                    "storeNumber,providerId,branchId) " +
                    "VALUES (1,'PR66',1,2,'resp_1','','074','obi-pl','074')",
            )
            db.execSQL(
                "INSERT INTO messages " +
                    "(id,conversationId,role,text,createdAt) " +
                    "VALUES (1,1,'ASSISTANT','saved answer',2)",
            )
            db.execSQL(
                "INSERT INTO message_products " +
                    "(messageId,position,obik,name,stock,grossPrice," +
                    "productUrl,verifiedAt,storeNumber,imageUrl) " +
                    "VALUES (1,0,'3496072','Legacy product',4,'12.99'," +
                    "'https://www.obi.pl/p/3496072/test',2,'074',NULL)",
            )

            MIGRATION_7_8.migrate(db)

            assertEquals(
                "PR66",
                queryText(
                    db,
                    "SELECT title FROM conversations WHERE id=1",
                ),
            )
            assertEquals(
                "saved answer",
                queryText(
                    db,
                    "SELECT text FROM messages WHERE id=1",
                ),
            )
            assertEquals(
                "Legacy product",
                queryText(
                    db,
                    "SELECT name FROM message_products " +
                        "WHERE messageId=1 AND position=0",
                ),
            )
            assertEquals(
                "obi-pl",
                queryText(
                    db,
                    "SELECT providerId FROM message_products " +
                        "WHERE messageId=1 AND position=0",
                ),
            )
            assertEquals(
                "3496072",
                queryText(
                    db,
                    "SELECT productId FROM message_products " +
                        "WHERE messageId=1 AND position=0",
                ),
            )
            assertEquals(
                "074",
                queryText(
                    db,
                    "SELECT branchId FROM message_products " +
                        "WHERE messageId=1 AND position=0",
                ),
            )
            db.query(
                "SELECT articleNumber FROM message_products " +
                    "WHERE messageId=1 AND position=0",
            ).use {
                check(it.moveToFirst())
                assertEquals(null, it.getString(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(DB_V7_NAME)
        }
    }

    private fun createV2Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE conversations (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "title TEXT NOT NULL,createdAt INTEGER NOT NULL," +
                "updatedAt INTEGER NOT NULL,lastResponseId TEXT," +
                "draft TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL," +
                "conversationId INTEGER NOT NULL,role TEXT NOT NULL," +
                "text TEXT NOT NULL,createdAt INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE message_products (" +
                "messageId INTEGER NOT NULL,position INTEGER NOT NULL," +
                "obik TEXT NOT NULL,name TEXT NOT NULL,stock INTEGER," +
                "grossPrice TEXT,productUrl TEXT NOT NULL," +
                "verifiedAt INTEGER NOT NULL," +
                "PRIMARY KEY(messageId,position))",
        )
    }

    private fun queryText(
        db: SupportSQLiteDatabase,
        sql: String,
    ): String =
        db.query(sql).use {
            check(it.moveToFirst())
            it.getString(0)
        }

    private companion object {
        const val DB_NAME = "conversation-migration-v2-v3-test.db"
        const val DB_V3_NAME = "conversation-migration-v3-v4-test.db"
        const val DB_V4_NAME = "conversation-migration-v4-v5-test.db"
        const val DB_V5_NAME = "conversation-migration-v5-v6-test.db"
        const val DB_V6_NAME = "conversation-migration-v6-v7-test.db"
        const val DB_V7_NAME = "conversation-migration-v7-v8-test.db"
        const val DB_V9_NAME = "conversation-migration-v9-v10-test.db"
        const val DB_V10_NAME = "conversation-migration-v10-v11-test.db"
    }
}
