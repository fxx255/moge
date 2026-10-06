package com.moge.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import javax.inject.Singleton

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, RequestEntity::class,
        NotebookCategoryEntity::class, NotebookEntryEntity::class, BranchSelectionEntity::class],
    version = 5,
    exportSchema = true,
)
@TypeConverters(InstantConverters::class)
abstract class MogeDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun requestDao(): RequestDao
    abstract fun notebookDao(): NotebookDao

    companion object {
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE message ADD COLUMN parent_message_id TEXT REFERENCES message(id) ON DELETE SET NULL")
                db.execSQL("CREATE INDEX index_message_parent_message_id ON message(parent_message_id)")
                db.query("SELECT id,conversation_id FROM message ORDER BY conversation_id,created_at,rowid").use { cursor ->
                    var lastConversation: String? = null
                    var previous: String? = null
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0)
                        val conversation = cursor.getString(1)
                        if (conversation != lastConversation) previous = null
                        if (previous != null) db.execSQL("UPDATE message SET parent_message_id=? WHERE id=?", arrayOf(previous, id))
                        previous = id
                        lastConversation = conversation
                    }
                }
                db.execSQL("""CREATE TABLE branch_selection (
                    conversation_id TEXT NOT NULL, parent_key TEXT NOT NULL, selected_child_id TEXT NOT NULL,
                    PRIMARY KEY(conversation_id,parent_key),
                    FOREIGN KEY(conversation_id) REFERENCES conversation(id) ON DELETE CASCADE,
                    FOREIGN KEY(selected_child_id) REFERENCES message(id) ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX index_branch_selection_selected_child_id ON branch_selection(selected_child_id)")
            }
        }
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("message", "request", "notebook_entry").forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `document_paths` TEXT NOT NULL DEFAULT ''")
                }
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `conversation` ADD COLUMN `category_id` TEXT " +
                        "REFERENCES `notebook_category`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_conversation_category_id` " +
                        "ON `conversation` (`category_id`)",
                )
            }
        }
    }
}

class InstantConverters {
    @TypeConverter
    fun fromInstant(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun toInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MogeDatabase =
        Room.databaseBuilder(context, MogeDatabase::class.java, "moge.db")
            .addMigrations(MogeDatabase.MIGRATION_2_3, MogeDatabase.MIGRATION_3_4, MogeDatabase.MIGRATION_4_5)
            .build()

    @Provides
    fun provideConversationDao(db: MogeDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideRequestDao(db: MogeDatabase): RequestDao = db.requestDao()

    @Provides
    fun provideNotebookDao(db: MogeDatabase): NotebookDao = db.notebookDao()
}
