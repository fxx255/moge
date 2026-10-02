package com.moge.app.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NotebookRepositoryTest {
    private lateinit var db: MogeDatabase
    private lateinit var repository: NotebookRepository
    private lateinit var history: ConversationRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MogeDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = NotebookRepository(db, db.notebookDao(), Dispatchers.Unconfined)
        history = ConversationRepository(db.conversationDao(), Dispatchers.Unconfined)
    }
    @After fun teardown() { db.close() }
    private fun entry(answer: String = "answer", category: String? = null) = NotebookEntryEntity(
        categoryId = category, sourceConversationId = "conversation", sourceQuestionId = "question", sourceAnswerId = answer,
        title = "题目标题", questionText = "求导", questionTranscript = "识别函数",
        questionImagePaths = "[\"/private/question.jpg\"]", answerText = "完整讲解 [[FIGURE:2]]",
        finalAnswer = "最终答案", figurePaths = "[\"\",\"/private/plot.png\"]",
    )

    @Test fun `snapshot survives history cascade and keeps all image references`() = runBlocking {
        db.conversationDao().insertConversation(ConversationEntity(id = "conversation", title = "源题"))
        db.requestDao().insertMessage(MessageEntity(id = "question", conversationId = "conversation", role = "user", content = "源问题"))
        db.requestDao().insertMessage(MessageEntity(id = "answer", conversationId = "conversation", role = "assistant",
            replyToMessageId = "question", content = "源答案"))
        val id = repository.save(entry())
        assertEquals(setOf("conversation"), history.deleteAllIdle())
        assertTrue(db.conversationDao().getMessages("conversation").isEmpty())
        assertEquals("完整讲解 [[FIGURE:2]]", db.notebookDao().getEntry(id)!!.answerText)
        assertEquals("识别函数", db.notebookDao().getEntry(id)!!.questionTranscript)
        assertEquals("最终答案", db.notebookDao().getEntry(id)!!.finalAnswer)
        assertEquals(setOf("/private/question.jpg", "/private/plot.png"), repository.referencedImagePaths())
        assertEquals(1, repository.observeEntries().first().size)
    }

    @Test fun `explicit save updates by answer id and preserves identity and creation time`() = runBlocking {
        val original = entry()
        val id = repository.save(original)
        val before = db.notebookDao().getEntry(id)!!
        val category = repository.createCategory("错题")
        val updatedId = repository.save(entry(category = category.id).copy(answerText = "重新生成后主动更新", finalAnswer = "新答案"))
        val after = db.notebookDao().getEntry(updatedId)!!
        assertEquals(id, updatedId)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(category.id, after.categoryId)
        assertEquals("新答案", after.finalAnswer)
        assertEquals(1, repository.observeEntries().first().size)
    }

    @Test fun `idempotent favorite save does not silently overwrite regenerated source`() = runBlocking {
        val saved = repository.saveFavorite(entry())
        assertEquals(saved, repository.saveFavorite(entry().copy(answerText = "不同的重新生成答案")))
        assertEquals(saved.id, repository.updateFavorite(entry().copy(answerText = "主动更新"))!!.id)
        assertEquals("主动更新", repository.favorite("answer")!!.answerText)
    }

    @Test fun `two answers in one conversation are independent favorites`() = runBlocking {
        repository.save(entry("a"))
        repository.save(entry("b").copy(sourceQuestionId = "followup"))
        assertEquals(setOf("a", "b"), repository.observeEntries().first().map { it.sourceAnswerId }.toSet())
    }

    @Test fun `concurrent saves deduplicate the source answer`() = runBlocking {
        val ids = (1..8).map { async(Dispatchers.Default) { repository.save(entry()) } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertEquals(1, repository.observeEntries().first().size)
    }

    @Test fun `category rename reorder move and SET NULL deletion retain snapshots`() = runBlocking {
        assertTrue(repository.observeCategories().first().isEmpty())
        val a = repository.createCategory("  自定义 A  ")
        val b = repository.createCategory("自定义 B")
        val id = repository.save(entry(category = a.id))
        assertEquals("自定义 A", a.name)
        assertTrue(repository.renameCategory(a.id, "任意名称"))
        repository.reorderCategories(listOf(b.id, a.id))
        assertEquals(listOf(b.id, a.id), repository.observeCategories().first().map { it.id })
        assertEquals(1, repository.moveEntries(setOf(id), b.id))
        assertTrue(repository.observeEntries(categoryId = a.id).first().isEmpty())
        assertEquals(id, repository.observeEntries(categoryId = b.id).first().single().id)
        repository.deleteCategory(b.id)
        assertNull(db.notebookDao().getEntry(id)!!.categoryId)
        assertEquals(id, repository.observeEntries(uncategorizedOnly = true).first().single().id)
        assertEquals("完整讲解 [[FIGURE:2]]", repository.favorite("answer")!!.answerText)
    }

    @Test fun `search intersects category and handles wildcards literally in all snapshot fields`() = runBlocking {
        val category = repository.createCategory("我的分类")
        val id = repository.save(entry(category = category.id).copy(questionText = "50% a_b \\frac{x}{2}"))
        repository.save(entry("other").copy(questionText = "无关", questionTranscript = "无关", answerText = "无关", finalAnswer = "无关"))
        listOf("%", "_", "\\", "识别", "讲解", "最终答案").forEach { text ->
            assertEquals(id, repository.observeEntries(text, category.id).first().single().id)
        }
        assertTrue(repository.observeEntries("不存在", category.id).first().isEmpty())
        assertTrue(repository.observeEntries("讲解", uncategorizedOnly = true).first().isEmpty())
    }

    @Test fun `delete favorite and clear notebook preserve categories and source history`() = runBlocking {
        db.conversationDao().insertConversation(ConversationEntity(id = "conversation", title = "历史"))
        db.requestDao().insertMessage(MessageEntity(conversationId = "conversation", role = "user", content = "问题"))
        val category = repository.createCategory("待复习")
        val id = repository.save(entry(category = category.id))
        assertEquals(1, repository.deleteFavorites(setOf(id)))
        assertNotNull(history.getConversation("conversation"))
        repository.save(entry())
        repository.clear()
        assertTrue(repository.observeEntries().first().isEmpty())
        assertEquals(listOf(category.id), repository.observeCategories().first().map { it.id })
        assertTrue(repository.referencedImagePaths().isEmpty())
        assertEquals(1, history.messages("conversation").size)
    }

    @Test fun `malformed snapshot references stop orphan collection`() = runBlocking {
        db.notebookDao().insertEntry(entry().copy(figurePaths = "not json"))
        assertTrue(runCatching { repository.referencedImagePaths() }.isFailure)
        assertTrue(runCatching { repository.save(entry().copy(questionImagePaths = "not json")) }.isFailure)
    }

    @Test fun `stale category and invalid ordering fail without losing data`() = runBlocking {
        val a = repository.createCategory("A")
        val b = repository.createCategory("B")
        val id = repository.save(entry(category = a.id))
        assertTrue(runCatching { repository.moveEntries(setOf(id), "missing") }.isFailure)
        assertEquals(a.id, db.notebookDao().getEntry(id)!!.categoryId)
        assertTrue(runCatching { repository.reorderCategories(listOf(a.id, a.id)) }.isFailure)
        assertEquals(listOf(a.id, b.id), repository.observeCategories().first().map { it.id })
        assertTrue(runCatching { repository.createCategory(" ") }.isFailure)
        assertTrue(runCatching { repository.renameCategory(a.id, "😀".repeat(81)) }.isFailure)
    }

    @Test fun `message snapshot validates association and preserves question display text and figure slots`() {
        val conversation = ConversationEntity(id = "c", title = "源题")
        val question = MessageEntity(id = "q", conversationId = "c", role = "user", content = "模型输入",
            displayContent = "用户提问", transcript = "识别内容")
        val answer = MessageEntity(id = "a", conversationId = "c", role = "assistant", content = "完整解答",
            replyToMessageId = "q", finalAnswer = "答案", imagePaths = "[\"\",\"plot.png\"]")
        val snapshot = NotebookRepository.snapshot(conversation, question, answer)
        assertEquals("用户提问", snapshot.questionText)
        assertEquals("识别内容", snapshot.questionTranscript)
        assertEquals("答案", snapshot.finalAnswer)
        assertEquals("[\"\",\"plot.png\"]", snapshot.figurePaths)
        assertTrue(runCatching { NotebookRepository.snapshot(conversation, question, answer.copy(replyToMessageId = "other")) }.isFailure)
    }
}
