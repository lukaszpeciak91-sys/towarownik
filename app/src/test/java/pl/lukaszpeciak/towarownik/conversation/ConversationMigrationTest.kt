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
                    "(messageId,position,title,url) " +
                    "VALUES (1,0,'Manual','https://example.com/manual')",
            )
            assertEquals(
                "1",
                queryText(
                    db,
                    "SELECT COUNT(*) FROM message_sources " +
                        "WHERE messageId=1",
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
    }
}
