package com.moge.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Embedded
import androidx.room.Transaction
import androidx.room.Relation
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** 独立历史与消息的读写（生成流程的写入走 [RequestDao]，带 attempt 围栏）。 */
@Dao
interface ConversationDao {
    @Transaction
    @Query("SELECT * FROM conversation WHERE id=:conversationId")
    fun observeTree(conversationId: String): Flow<ConversationTree?>

    @Transaction
    suspend fun visibleMessages(conversationId: String): List<MessageEntity> {
        val messages = getMessages(conversationId)
        val selections = getBranchSelections(conversationId)
        val path = ConversationBranches.visible(messages, selections)
        if (messages.any { it.parentMessageId != null }) {
            path.forEach { child ->
                val parentKey = ConversationBranches.key(child.parentMessageId)
                if (selections.none { it.parentKey == parentKey && it.selectedChildId == child.id })
                    putBranchSelection(BranchSelectionEntity(conversationId, parentKey, child.id))
            }
        }
        return path
    }
    @Query("SELECT * FROM branch_selection WHERE conversation_id=:conversationId")
    suspend fun getBranchSelections(conversationId: String): List<BranchSelectionEntity>

    @Query("SELECT * FROM branch_selection WHERE conversation_id=:conversationId")
    fun observeBranchSelections(conversationId: String): Flow<List<BranchSelectionEntity>>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun putBranchSelection(selection: BranchSelectionEntity)

    @Transaction
    suspend fun selectBranch(conversationId: String, questionId: String) {
        val question = requireNotNull(getMessage(questionId)) { "这个版本已不存在" }
        require(question.conversationId == conversationId && question.role == "user") { "不能切换到其他对话" }
        putBranchSelection(BranchSelectionEntity(conversationId, ConversationBranches.key(question.parentMessageId), question.id))
    }

    @Transaction
    suspend fun revealBranch(conversationId: String, messageId: String) {
        val messages = getMessages(conversationId)
        ConversationBranches.ancestors(messages, messageId).forEach { message ->
            putBranchSelection(BranchSelectionEntity(conversationId, ConversationBranches.key(message.parentMessageId), message.id))
        }
    }

    @Query("""
        SELECT m.id FROM message m WHERE m.conversation_id=:conversationId AND
        (m.content LIKE :pattern ESCAPE '\' OR m.display_content LIKE :pattern ESCAPE '\'
        OR m.transcript LIKE :pattern ESCAPE '\' OR m.final_answer LIKE :pattern ESCAPE '\'
        OR EXISTS (SELECT 1 FROM request r WHERE r.answer_message_id=m.id AND r.partial_text LIKE :pattern ESCAPE '\'))
        ORDER BY m.created_at DESC, m.rowid DESC LIMIT 1
    """)
    suspend fun searchMessage(conversationId: String, pattern: String): String?
    @Query("SELECT document_paths FROM message UNION ALL SELECT document_paths FROM request")
    suspend fun allDocumentPathJson(): List<String>


    /** 一次查询取最近内容与请求状态；仅含用户消息的会话显示，分类筛选与搜索取交集。 */
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
                      ORDER BY updated_at DESC, rowid DESC LIMIT 1), '') AS request_status,
            COALESCE((SELECT COALESCE(NULLIF(trim(m.display_content), ''), NULLIF(trim(m.content), ''),
                                      NULLIF(trim(m.transcript), '')) FROM message m
                      WHERE m.conversation_id = c.id AND m.role = 'user'
                      ORDER BY m.created_at ASC, m.rowid ASC LIMIT 1), '') AS question_preview,
            (SELECT COUNT(*) FROM notebook_entry n
             WHERE n.source_conversation_id = c.id) AS favorite_count
        FROM conversation c WHERE EXISTS
            (SELECT 1 FROM message WHERE conversation_id = c.id AND role = 'user')
        AND (:categoryId IS NULL OR c.category_id = :categoryId)
        AND (:uncategorizedOnly = 0 OR c.category_id IS NULL)
        AND (:pattern = '' OR c.title LIKE :pattern ESCAPE '\' OR EXISTS
            (SELECT 1 FROM message WHERE conversation_id = c.id AND
             (content LIKE :pattern ESCAPE '\' OR display_content LIKE :pattern ESCAPE '\'
              OR transcript LIKE :pattern ESCAPE '\' OR final_answer LIKE :pattern ESCAPE '\'))
             OR EXISTS (SELECT 1 FROM request WHERE conversation_id = c.id
                        AND partial_text LIKE :pattern ESCAPE '\'))
        ORDER BY c.pinned DESC, c.updated_at DESC, c.id
    """)
    fun observeHistory(
        pattern: String, categoryId: String? = null, uncategorizedOnly: Boolean = false,
    ): Flow<List<HistoryEntry>>

    @Query("SELECT EXISTS(SELECT 1 FROM notebook_category WHERE id = :categoryId)")
    suspend fun categoryExists(categoryId: String): Boolean

    /** 只变更分类；同一分类的会话不计入返回值，历史时间与收藏归属保持原样。 */
    @Query("UPDATE conversation SET category_id = :categoryId WHERE id IN (:ids) AND category_id IS NOT :categoryId")
    suspend fun moveConversations(ids: List<String>, categoryId: String?): Int

    /** 分类校验与所有批次共享事务：无效分类抛出异常，任何批次失败均整体回滚。 */
    @Transaction
    suspend fun moveToCategory(ids: List<String>, categoryId: String?): Int {
        if (ids.isEmpty()) return 0
        require(categoryId == null || categoryExists(categoryId)) { "分类不存在" }
        var moved = 0
        // 每批两个 categoryId 参数加最多 498 个会话 id，总计不超过 500 个 SQLite 参数。
        for (batch in ids.chunked(498)) {
            moved += moveConversations(batch, categoryId)
        }
        return moved
    }

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

    /** Last activity includes retry/failure timestamps; pinning alone is not collection. */
    @Query("""
        SELECT c.id FROM conversation c
        WHERE c.updated_at <= :cutoff AND c.category_id IS NULL
          AND NOT EXISTS (SELECT 1 FROM notebook_entry n WHERE n.source_conversation_id = c.id)
          AND NOT EXISTS (SELECT 1 FROM message m
                          WHERE m.conversation_id = c.id AND m.created_at > :cutoff)
          AND NOT EXISTS (SELECT 1 FROM request r WHERE r.conversation_id = c.id
                          AND (r.status IN ('PREPARING','RUNNING') OR r.updated_at > :cutoff))
    """)
    suspend fun expiredConversationIds(cutoff: Instant): List<String>

    /** Eligibility and all cascades share a transaction with classification, collection and submission. */
    @Transaction
    suspend fun deleteExpiredConversations(cutoff: Instant): List<String> {
        val expired = expiredConversationIds(cutoff)
        expired.chunked(500).forEach { deleteConversations(it) }
        return expired
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

    @Query("SELECT * FROM message WHERE id = :id")
    suspend fun getMessage(id: String): MessageEntity?

    /** 只接受明确关联的问题与已完成回答；旧消息没有请求记录时仍须有有效回复关联。 */
    @Query("""
        SELECT a.* FROM message a JOIN message q
            ON q.id = a.reply_to_message_id AND q.conversation_id = a.conversation_id AND q.role = 'user'
        WHERE a.conversation_id = :conversationId AND a.role = 'assistant'
          AND (trim(COALESCE(a.display_content, a.content)) != '' OR trim(a.final_answer) != '')
          AND NOT EXISTS (SELECT 1 FROM request r WHERE r.answer_message_id = a.id
              AND (r.status != 'COMPLETED' OR r.conversation_id != a.conversation_id OR r.user_message_id != q.id))
        ORDER BY a.created_at DESC, a.rowid DESC
    """)
    suspend fun completedAnswers(conversationId: String): List<MessageEntity>

    @Transaction
    suspend fun latestCompletedAnswer(conversationId: String): MessageEntity? {
        val visible = visibleMessages(conversationId).mapTo(HashSet()) { it.id }
        return completedAnswers(conversationId).firstOrNull { it.id in visible }
    }

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

/** A transaction snapshot prevents combining new messages with old branch selections. */
data class ConversationTree(
    @Embedded val conversation: ConversationEntity,
    @Relation(parentColumn = "id", entityColumn = "conversation_id") val messages: List<MessageEntity>,
    @Relation(parentColumn = "id", entityColumn = "conversation_id") val selections: List<BranchSelectionEntity>,
)

/** 历史卡片投影；自定义分类在会话上持久化，收藏快照仍然独立。 */
data class HistoryEntry(
    @Embedded val conversation: ConversationEntity,
    @androidx.room.ColumnInfo(name = "recent_content") val recentContent: String,
    @androidx.room.ColumnInfo(name = "request_status") val requestStatus: String = "",
    @androidx.room.ColumnInfo(name = "question_preview") val questionPreview: String = "",
    @androidx.room.ColumnInfo(name = "favorite_count") val favoriteCount: Int = 0,
)
