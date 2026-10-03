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
        NotebookCategoryEntity::class, NotebookEntryEntity::class],
    version = 3,
    exportSchema = true,
)
@TypeConverters(InstantConverters::class)
abstract class MogeDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun requestDao(): RequestDao
    abstract fun notebookDao(): NotebookDao

    companion object {
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
            .addMigrations(MogeDatabase.MIGRATION_2_3)
            .build()

    @Provides
    fun provideConversationDao(db: MogeDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideRequestDao(db: MogeDatabase): RequestDao = db.requestDao()

    @Provides
    fun provideNotebookDao(db: MogeDatabase): NotebookDao = db.notebookDao()
}
