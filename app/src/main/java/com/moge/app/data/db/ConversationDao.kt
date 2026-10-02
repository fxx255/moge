package com.moge.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Embedded
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** 独立历史与消息的读写（生成流程的写入走 [RequestDao]，带 attempt 围栏）。 */
@Dao
interface ConversationDao {

    /** 一次查询取最近内容与请求状态；空会话不出现在历史中。 */
    @Query("""
        SELECT c.*, COALESCE(
            (SELECT NULLIF(trim(r.partial_text), '') FROM request r
             WHERE r.conversation_id = c.id AND r.status IN ('PREPARING','RUNNING','INTERRUPTED','CANCELLED')
             AND r.answer_message_id = (SELECT id FROM message WHERE conversation_id = c.id
                                        ORDER BY created_at DESC, rowid DESC LIMIT 1)
             ORDER BY r.updated_at DESC, r.rowid DESC LIMIT 1),
            (SELECT COALESCE(NULLIF(trim(m.display_content), ''), NULLIF(trim(m.content), ''),
                             NULLIF(trim(m.transcript), '')) FROM message m
             WHERE m.conversation_id = c.id AND
               (trim(COALESCE(m.display_content, '')) != '' OR trim(m.content) != '' OR trim(m.transcript) != '')
             ORDER BY m.created_at DESC, m.rowid DESC LIMIT 1), '') AS recent_content,
            COALESCE((SELECT status FROM request WHERE conversation_id = c.id
                      ORDER BY updated_at DESC, rowid DESC LIMIT 1), '') AS request_status
        FROM conversation c WHERE EXISTS
            (SELECT 1 FROM message WHERE conversation_id = c.id AND role = 'user')
        AND (:pattern = '' OR c.title LIKE :pattern ESCAPE '\' OR EXISTS
            (SELECT 1 FROM message WHERE conversation_id = c.id AND
             (content LIKE :pattern ESCAPE '\' OR display_content LIKE :pattern ESCAPE '\'
              OR transcript LIKE :pattern ESCAPE '\' OR final_answer LIKE :pattern ESCAPE '\'))
             OR EXISTS (SELECT 1 FROM request WHERE conversation_id = c.id
                        AND partial_text LIKE :pattern ESCAPE '\'))
        ORDER BY c.pinned DESC, c.updated_at DESC, c.id
    """)
    fun observeHistory(pattern: String): Flow<List<HistoryEntry>>

    @Query(
        "SELECT id FROM conversation WHERE id IN (:ids) AND NOT EXISTS " +
            "(SELECT 1 FROM request WHERE conversation_id = conversation.id AND status IN ('PREPARING','RUNNING'))",
    )
    suspend fun idleConversationIds(ids: List<String>): List<String>

    @Query("DELETE FROM conversation WHERE id IN (:ids)")
    suspend fun deleteConversations(ids: List<String>)

    /** 查询与级联删除在同一事务中：与提交、重试串行，绝不删掉在途请求。 */
    @Transaction
    suspend fun deleteIdleConversations(ids: List<String>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val deletable = idleConversationIds(ids)
        if (deletable.isNotEmpty()) deleteConversations(deletable)
        return deletable
    }

    /** 保留生成流程使用的会话 API。历史列表使用 [observeHistory]。 */
    @Query("SELECT * FROM conversation ORDER BY pinned DESC, updated_at DESC")
    fun observeConversations(): Flow<List<ConversationEntity>>

    /** 标题或题目正文模糊匹配；[pattern] 由调用方转义并包好 `%`。 */
    @Query(
        "SELECT * FROM conversation WHERE title LIKE :pattern ESCAPE '\\' " +
            "OR id IN (SELECT conversation_id FROM message WHERE role = 'user' " +
            "AND (content LIKE :pattern ESCAPE '\\' OR transcript LIKE :pattern ESCAPE '\\')) " +
            "ORDER BY pinned DESC, updated_at DESC",
    )
    fun searchConversations(pattern: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id")
    fun observeConversation(id: String): Flow<ConversationEntity?>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun getConversation(id: String): ConversationEntity?

    @Query("SELECT * FROM message WHERE conversation_id = :conversationId ORDER BY created_at ASC, rowid ASC")
    fun observeMessages(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM message WHERE conversation_id = :conversationId ORDER BY created_at ASC, rowid ASC")
    suspend fun getMessages(conversationId: String): List<MessageEntity>

    /** Recover a completed answer locally without replacing its original provider text. */
    @Query("""
        UPDATE message SET display_content = :displayText, image_paths = :recoveredPaths
        WHERE id = :messageId AND role = 'assistant'
          AND content = :sourceText AND image_paths = :sourcePaths
          AND display_content IS :sourceDisplay
          AND NOT EXISTS (SELECT 1 FROM request
                          WHERE answer_message_id = :messageId AND status != 'COMPLETED')
    """)
    suspend fun repairAnswerFigures(
        messageId: String, sourceText: String, sourceDisplay: String?, sourcePaths: String,
        displayText: String, recoveredPaths: String,
    ): Int

    @Insert
    suspend fun insertConversation(conversation: ConversationEntity)

    /**
     * 只删**还没有任何消息**的会话：新题目先建会话再提交，提交被拒（另一道题在生成）时
     * 用它回收空壳，避免历史里出现一条空对话。已有消息的会话绝不受影响。
     */
    @Query(
        "DELETE FROM conversation WHERE id = :id " +
            "AND NOT EXISTS (SELECT 1 FROM message WHERE conversation_id = :id)",
    )
    suspend fun deleteIfEmpty(id: String): Int

    @Query("UPDATE conversation SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Instant): Int

    @Query("UPDATE conversation SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversation SET solve_mode = :mode WHERE id = :id")
    suspend fun setSolveMode(id: String, mode: String)

    @Query("UPDATE conversation SET cover_image = :path WHERE id = :id AND cover_image = ''")
    suspend fun setCoverIfEmpty(id: String, path: String)

    @Query("UPDATE message SET transcript = :transcript WHERE id = :messageId")
    suspend fun setTranscript(messageId: String, transcript: String)

    /** 所有消息引用的图片（拍照原图 + 图表），清理孤儿文件时使用。 */
    @Query("SELECT image_paths FROM message WHERE image_paths != ''")
    suspend fun allImagePathJson(): List<String>

    @Query("SELECT cover_image FROM conversation WHERE cover_image != ''")
    suspend fun allCoverPaths(): List<String>

    /** 在途 / 待重试请求钉下的附件（与用户消息的 image_paths 通常重合，保险起见一并保留）。 */
    @Query("SELECT attachment_paths FROM request WHERE attachment_paths != ''")
    suspend fun allAttachmentPathJson(): List<String>

    @Query("SELECT id FROM conversation")
    suspend fun allConversationIds(): List<String>
}

/** 独立历史卡片投影，无学科或收藏归属。 */
data class HistoryEntry(
    @Embedded val conversation: ConversationEntity,
    @androidx.room.ColumnInfo(name = "recent_content") val recentContent: String,
    @androidx.room.ColumnInfo(name = "request_status") val requestStatus: String = "",
)
