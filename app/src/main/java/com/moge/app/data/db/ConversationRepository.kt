package com.moge.app.data.db

import com.moge.app.core.IoDispatcher
import com.moge.app.domain.SolveMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 历史会话的读写入口。
 *
 * 生成流程的消息写入仍然只走 [RequestRepository]（带 attempt 围栏）；
 * 这里负责题目建立、历史查询与分类、改名、受保护的批量删除与空壳回收。
 */
@Singleton
class ConversationRepository @Inject constructor(
    private val dao: ConversationDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** 默认查询全部；categoryId 筛选分类，uncategorizedOnly 筛选未分类，与搜索取交集。 */
    fun observeHistory(
        query: String = "", categoryId: String? = null, uncategorizedOnly: Boolean = false,
    ): Flow<List<HistoryEntry>> = dao.observeHistory(searchPattern(query), categoryId, uncategorizedOnly)

    /**
     * 批量移动或以 null 取消分类，返回实际变更的会话数；不存在的会话与原分类相同的会话不计入。
     * 空集合直接返回 0；非空集合指定无效分类时抛出 IllegalArgumentException，所有批次原子提交。
     * 只更新分类，不改变历史时间、消息、请求或独立收藏快照。
     */
    suspend fun moveToCategory(ids: Set<String>, categoryId: String?): Int = withContext(io) {
        dao.moveToCategory(ids.toList(), categoryId)
    }

    suspend fun getConversation(id: String): ConversationEntity? = withContext(io) { dao.getConversation(id) }

    suspend fun setPinned(id: String, pinned: Boolean) = withContext(io) { dao.setPinned(id, pinned) }

    suspend fun rename(id: String, title: String): Boolean = withContext(io) {
        val cleaned = title.trim()
        require(cleaned.isNotEmpty()) { "标题不能为空" }
        require(cleaned.codePointCount(0, cleaned.length) <= 80) { "标题最多 80 个字" }
        dao.rename(id, cleaned, Instant.now()) > 0
    }

    /** 图片可能被其他题目或草稿共享；这里只删数据库，保留原图和图表。 */
    suspend fun deleteIdle(ids: Set<String>): Set<String> = withContext(io) {
        // Room 的 SQLite 参数有数量上限；分批，但每批都有完整的事务保护。
        ids.toList().chunked(500).flatMap { dao.deleteIdleConversations(it) }.toSet()
    }

    suspend fun allIds(): Set<String> = withContext(io) { dao.allConversationIds().toSet() }

    /**
     * 消息、封面和请求附件引用的全部本机图片路径（未规范化），孤儿文件回收时保留它们。
     * 任一行解析失败即抛出，调用方据此放弃回收。
     */
    suspend fun referencedImagePaths(): Set<String> = withContext(io) {
        val lists = dao.allImagePathJson() + dao.allAttachmentPathJson()
        (lists.flatMap(RequestRepository::decodePathListStrict) + dao.allCoverPaths()).filter { it.isNotBlank() }.toSet()
    }

    /** 清空历史：删掉所有没有在途请求的会话；独立收藏不受影响。 */
    suspend fun deleteAllIdle(): Set<String> = deleteIdle(allIds())

    fun observeConversation(id: String): Flow<ConversationEntity?> = dao.observeConversation(id)

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> = dao.observeMessages(conversationId)

    suspend fun messages(conversationId: String): List<MessageEntity> = withContext(io) {
        dao.getMessages(conversationId)
    }

    /**
     * 新建一道题：标题先取提问的第一行，首次有效回答后由
     * [RequestDao.nameConversationFromAnswer] 换成模型概括的主题。
     *
     * 刻意**不**与请求记录放进同一个事务：请求落盘在 [com.moge.app.runtime.GenerationManager.submit]
     * 的提交锁里，且要先读会话历史钉快照；把建会话塞进那把锁会改动已验证的运行时。
     * 代价是提交被拒时会留下空会话，由调用方用 [discardIfEmpty] 回收。
     */
    suspend fun createConversation(firstQuestion: String, solveMode: SolveMode): ConversationEntity =
        withContext(io) {
            val now = Instant.now()
            val conversation = ConversationEntity(
                title = initialTitle(firstQuestion),
                solveMode = solveMode.name,
                createdAt = now,
                updatedAt = now,
            )
            dao.insertConversation(conversation)
            conversation
        }

    /** 题册封面：只在还没有封面时写入首张题目照片，之后的追问照片不会顶掉它。 */
    suspend fun setCoverIfEmpty(id: String, path: String) = withContext(io) { dao.setCoverIfEmpty(id, path) }

    /** 回收还没有任何消息的会话；已有消息的绝不删。返回 true 表示确实删了。 */
    suspend fun discardIfEmpty(id: String): Boolean = withContext(io) { dao.deleteIfEmpty(id) > 0 }

    companion object {
        /** 按字面搜索 SQL LIKE 的特殊字符，包括反斜杠自身。空串表示不筛选。 */
        fun searchPattern(query: String): String {
            val cleaned = query.trim()
            if (cleaned.isEmpty()) return ""
            return "%" + cleaned.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        /** 新题目的占位标题，与 [RequestDao.nameConversationFromAnswer] 的可覆盖名单一致。 */
        const val PLACEHOLDER_TITLE = "新题目"

        /** 只有照片、没打字的题目的占位标题；同样在首次有效回答后被替换。 */
        const val PHOTO_PLACEHOLDER_TITLE = "图片题目"

        private const val TITLE_MAX_CODE_POINTS = 40

        /** 提问的第一行非空文字，最长 40 个字符（按码点截断，不切坏 emoji）；空提问用占位标题。 */
        fun initialTitle(question: String): String {
            val line = question.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?: return PLACEHOLDER_TITLE
            val count = line.codePointCount(0, line.length)
            if (count <= TITLE_MAX_CODE_POINTS) return line
            return line.substring(0, line.offsetByCodePoints(0, TITLE_MAX_CODE_POINTS)).trimEnd() + "…"
        }
    }
}
