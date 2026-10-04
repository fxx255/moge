package com.moge.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface NotebookDao {
    @Query("SELECT document_paths FROM notebook_entry")
    suspend fun allDocumentPathJson(): List<String>

    @Query("SELECT * FROM notebook_category ORDER BY sort_order, created_at, id")
    fun observeCategories(): Flow<List<NotebookCategoryEntity>>

    @Query("SELECT * FROM notebook_category ORDER BY sort_order, created_at, id")
    suspend fun getCategories(): List<NotebookCategoryEntity>

    @Query("SELECT * FROM notebook_category WHERE id = :id")
    suspend fun getCategory(id: String): NotebookCategoryEntity?

    @Insert suspend fun insertCategory(category: NotebookCategoryEntity)

    @Query("UPDATE notebook_category SET name = :name, updated_at = :at WHERE id = :id")
    suspend fun renameCategory(id: String, name: String, at: Instant): Int

    @Query("UPDATE notebook_category SET sort_order = :position, updated_at = :at WHERE id = :id")
    suspend fun setCategoryOrder(id: String, position: Int, at: Instant)

    /** SET_NULL 外键只取消分类，不移除收藏。 */
    @Query("DELETE FROM notebook_category WHERE id = :id")
    suspend fun deleteCategory(id: String): Int

    /** categoryId=null + uncategorizedOnly=false 为全部；true 为未分类。 */
    @Query("""
        SELECT * FROM notebook_entry
        WHERE (:categoryId IS NULL OR category_id = :categoryId)
          AND (:uncategorizedOnly = 0 OR category_id IS NULL)
          AND (:pattern = '' OR title LIKE :pattern ESCAPE '\'
               OR question_text LIKE :pattern ESCAPE '\' OR question_transcript LIKE :pattern ESCAPE '\'
               OR answer_text LIKE :pattern ESCAPE '\' OR final_answer LIKE :pattern ESCAPE '\')
        ORDER BY updated_at DESC, created_at DESC, id
    """)
    fun observeEntries(pattern: String, categoryId: String?, uncategorizedOnly: Boolean): Flow<List<NotebookEntryEntity>>

    @Query("SELECT * FROM notebook_entry WHERE id = :id")
    fun observeEntry(id: String): Flow<NotebookEntryEntity?>

    @Query("SELECT * FROM notebook_entry WHERE id = :id")
    suspend fun getEntry(id: String): NotebookEntryEntity?

    @Query("SELECT * FROM notebook_entry WHERE source_answer_id = :sourceAnswerId")
    suspend fun getBySourceAnswerId(sourceAnswerId: String): NotebookEntryEntity?

    @Query("SELECT * FROM notebook_entry WHERE source_conversation_id IN (:conversationIds)")
    suspend fun entriesForConversations(conversationIds: List<String>): List<NotebookEntryEntity>

    @Query("SELECT * FROM notebook_entry WHERE source_answer_id = :sourceAnswerId")
    fun observeBySourceAnswerId(sourceAnswerId: String): Flow<NotebookEntryEntity?>

    @Insert suspend fun insertEntry(entry: NotebookEntryEntity)
    @Update suspend fun updateEntry(entry: NotebookEntryEntity): Int

    @Query("UPDATE notebook_entry SET category_id = :categoryId, updated_at = :at WHERE id IN (:ids)")
    suspend fun moveEntries(ids: List<String>, categoryId: String?, at: Instant): Int

    @Query("DELETE FROM notebook_entry WHERE id IN (:ids)")
    suspend fun deleteEntries(ids: List<String>): Int

    @Query("SELECT question_image_paths FROM notebook_entry WHERE question_image_paths != ''")
    suspend fun allQuestionImagePathJson(): List<String>

    @Query("SELECT figure_paths FROM notebook_entry WHERE figure_paths != ''")
    suspend fun allFigurePathJson(): List<String>

    @Query("DELETE FROM notebook_entry") suspend fun clearEntries()
}
