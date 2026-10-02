package com.moge.app.data.db

import androidx.room.withTransaction
import com.moge.app.core.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** 收藏与分类只写自己的表；从不更新或删除源会话、消息和附件文件。 */
@Singleton
class NotebookRepository @Inject constructor(
    private val db: MogeDatabase,
    private val dao: NotebookDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    fun observeCategories(): Flow<List<NotebookCategoryEntity>> = dao.observeCategories()

    fun observeEntries(
        query: String = "", categoryId: String? = null, uncategorizedOnly: Boolean = false,
    ): Flow<List<NotebookEntryEntity>> = dao.observeEntries(
        ConversationRepository.searchPattern(query), categoryId, uncategorizedOnly,
    )

    fun observeEntry(id: String): Flow<NotebookEntryEntity?> = dao.observeEntry(id)
    fun observeFavorite(sourceAnswerId: String): Flow<NotebookEntryEntity?> = dao.observeBySourceAnswerId(sourceAnswerId)
    suspend fun favorite(sourceAnswerId: String): NotebookEntryEntity? = withContext(io) {
        dao.getBySourceAnswerId(sourceAnswerId)
    }

    suspend fun createCategory(name: String): NotebookCategoryEntity = withContext(io) {
        val cleaned = validName(name)
        db.withTransaction {
            val categories = dao.getCategories()
            // Keep positions compact even after deletions and reorder operations.
            categories.forEachIndexed { index, category -> dao.setCategoryOrder(category.id, index, category.updatedAt) }
            NotebookCategoryEntity(name = cleaned, sortOrder = categories.size).also { dao.insertCategory(it) }
        }
    }

    suspend fun renameCategory(id: String, name: String): Boolean = withContext(io) {
        dao.renameCategory(id, validName(name), Instant.now()) > 0
    }

    /** 完整顺序必须与当前分类集合一致，避免并发创建/删除时静默丢失分类。 */
    suspend fun reorderCategories(ids: List<String>) = withContext(io) {
        db.withTransaction {
            val current = dao.getCategories().map { it.id }.toSet()
            require(ids.size == current.size && ids.toSet() == current) { "分类已变化，请刷新后重试" }
            val now = Instant.now()
            ids.forEachIndexed { index, id -> dao.setCategoryOrder(id, index, now) }
        }
    }

    suspend fun deleteCategory(id: String): Boolean = withContext(io) { dao.deleteCategory(id) > 0 }

    /** 主动保存/更新入口：按源回答去重，保留收藏 id 和创建时间，返回收藏 id。 */
    suspend fun save(entry: NotebookEntryEntity): String = withContext(io) {
        validateSnapshot(entry)
        db.withTransaction {
            requireCategory(entry.categoryId)
            val saved = dao.getBySourceAnswerId(entry.sourceAnswerId)
            val now = Instant.now()
            val snapshot = entry.copy(id = saved?.id ?: entry.id, title = validName(entry.title),
                createdAt = saved?.createdAt ?: now, updatedAt = now)
            if (saved == null) dao.insertEntry(snapshot) else check(dao.updateEntry(snapshot) == 1)
            snapshot.id
        }
    }

    /** 相同源回答再次收藏只返回原快照；源回答重新生成不会静默改写收藏。 */
    suspend fun saveFavorite(snapshot: NotebookEntryEntity): NotebookEntryEntity = withContext(io) {
        validateSnapshot(snapshot)
        db.withTransaction {
            dao.getBySourceAnswerId(snapshot.sourceAnswerId) ?: run {
                requireCategory(snapshot.categoryId)
                val now = Instant.ofEpochMilli(System.currentTimeMillis())
                snapshot.copy(title = validName(snapshot.title), createdAt = now, updatedAt = now)
                    .also { dao.insertEntry(it) }
            }
        }
    }

    suspend fun saveFavorite(
        conversation: ConversationEntity, question: MessageEntity, answer: MessageEntity, categoryId: String? = null,
    ): NotebookEntryEntity = saveFavorite(snapshot(conversation, question, answer, categoryId))

    /** 用户主动更新；保留收藏 id、分类、创建时间，只替换内容快照。 */
    suspend fun updateFavorite(snapshot: NotebookEntryEntity): NotebookEntryEntity? = withContext(io) {
        validateSnapshot(snapshot)
        db.withTransaction {
            val saved = dao.getBySourceAnswerId(snapshot.sourceAnswerId) ?: return@withTransaction null
            snapshot.copy(id = saved.id, title = validName(snapshot.title), categoryId = saved.categoryId,
                createdAt = saved.createdAt, updatedAt = Instant.now()).also { check(dao.updateEntry(it) == 1) }
        }
    }

    suspend fun updateFavorite(
        conversation: ConversationEntity, question: MessageEntity, answer: MessageEntity,
    ): NotebookEntryEntity? = updateFavorite(snapshot(conversation, question, answer))

    suspend fun moveEntries(ids: Set<String>, categoryId: String?): Int = withContext(io) {
        db.withTransaction {
            requireCategory(categoryId)
            val now = Instant.now()
            ids.toList().chunked(500).sumOf { dao.moveEntries(it, categoryId, now) }
        }
    }

    suspend fun deleteFavorites(ids: Set<String>): Int = withContext(io) {
        db.withTransaction { ids.toList().chunked(500).sumOf { dao.deleteEntries(it) } }
    }

    /** 严格解析：损坏的引用使 janitor 放弃本次回收，不能把仍有效的照片当孤儿。 */
    suspend fun referencedImagePaths(): Set<String> = withContext(io) {
        db.withTransaction {
            (dao.allQuestionImagePathJson() + dao.allFigurePathJson())
                .flatMap(RequestRepository::decodePathListStrict).filter { it.isNotBlank() }.toSet()
        }
    }

    /** 设置中的“清空题册”：只删除收藏，保留自定义分类和历史。 */
    suspend fun clear() = withContext(io) { dao.clearEntries() }

    private suspend fun requireCategory(id: String?) {
        require(id == null || dao.getCategory(id) != null) { "分类已被删除，请重新选择" }
    }

    companion object {
        private fun validName(value: String): String = value.trim().also {
            require(it.isNotEmpty()) { "名称不能为空" }
            require(it.codePointCount(0, it.length) <= 80) { "名称最多 80 个字" }
        }

        private fun validateSnapshot(entry: NotebookEntryEntity) {
            require(entry.sourceConversationId.isNotBlank() && entry.sourceQuestionId.isNotBlank() &&
                entry.sourceAnswerId.isNotBlank()) { "收藏缺少源问题或回答标识" }
            validName(entry.title)
            require(entry.answerText.isNotBlank() || entry.finalAnswer.isNotBlank()) { "解答尚未完成，暂不能收藏" }
            RequestRepository.decodePathListStrict(entry.questionImagePaths)
            RequestRepository.decodePathListStrict(entry.figurePaths)
        }

        /** 调用方确认回答已完成；按明确的问题关联保存，不猜前一条消息。 */
        fun snapshot(
            conversation: ConversationEntity, question: MessageEntity, answer: MessageEntity, categoryId: String? = null,
        ): NotebookEntryEntity {
            require(question.role == "user" && answer.role == "assistant") { "收藏必须包含问题与回答" }
            require(question.conversationId == conversation.id && answer.conversationId == conversation.id) {
                "问题与回答必须属于同一会话"
            }
            require(answer.replyToMessageId == question.id) { "请选择该回答对应的问题" }
            return NotebookEntryEntity(
                categoryId = categoryId, sourceConversationId = conversation.id,
                sourceQuestionId = question.id, sourceAnswerId = answer.id, title = conversation.title,
                questionText = question.displayContent ?: question.content,
                questionTranscript = question.transcript, questionImagePaths = question.imagePaths,
                answerText = answer.displayContent ?: answer.content,
                finalAnswer = answer.finalAnswer, figurePaths = answer.imagePaths,
            )
        }
    }
}
