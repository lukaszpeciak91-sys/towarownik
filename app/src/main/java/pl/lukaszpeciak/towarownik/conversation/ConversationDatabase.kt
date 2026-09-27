package pl.lukaszpeciak.towarownik.conversation

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
    ],
    version = 1,
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
                    .build()
                    .also { database ->
                        instance = database
                    }
            }
    }
}
