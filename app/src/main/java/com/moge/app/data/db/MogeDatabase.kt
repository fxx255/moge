package com.moge.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
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
    version = 2,
    exportSchema = true,
)
@TypeConverters(InstantConverters::class)
abstract class MogeDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun requestDao(): RequestDao
    abstract fun notebookDao(): NotebookDao
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
        Room.databaseBuilder(context, MogeDatabase::class.java, "moge.db").build()

    @Provides
    fun provideConversationDao(db: MogeDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideRequestDao(db: MogeDatabase): RequestDao = db.requestDao()

    @Provides
    fun provideNotebookDao(db: MogeDatabase): NotebookDao = db.notebookDao()
}
