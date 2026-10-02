package com.moge.app.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.domain.RequestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HistoryRepositoryTest {
    private lateinit var db: MogeDatabase
    private lateinit var repository: ConversationRepository

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MogeDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = ConversationRepository(db.conversationDao(), Dispatchers.Unconfined)
    }
    @After fun teardown() { db.close() }

    private suspend fun question(id: String, title: String = id, text: String = "题目 $id",
        pinned: Boolean = false, at: Long = 1) {
        db.conversationDao().insertConversation(ConversationEntity(id = id, title = title,
            pinned = pinned, createdAt = Instant.ofEpochMilli(at), updatedAt = Instant.ofEpochMilli(at)))
        db.requestDao().insertMessage(MessageEntity(id = "u$id", conversationId = id, role = "user", content = text))
    }
    private suspend fun ids(query: String = "") =
        repository.observeHistory(query).first().map { it.conversation.id }

    @Test fun `history hides empty shells and orders pinned before recent`() = runBlocking {
        question("old", pinned = true)
        question("recent", at = 30)
        question("middle", at = 20)
        db.conversationDao().insertConversation(ConversationEntity(id = "empty", title = "空题"))
        assertEquals(listOf("old", "recent", "middle"), ids())
    }

    @Test fun `search covers title questions follow ups OCR and answers`() = runBlocking {
        question("a", title = "三角函数", text = "求最大值")
        db.requestDao().insertMessage(MessageEntity(id = "follow", conversationId = "a", role = "user", content = "用代换法"))
        db.requestDao().insertMessage(MessageEntity(id = "answer", conversationId = "a", role = "assistant", content = "答案专用词"))
        db.conversationDao().setTranscript("ua", "识别到了抛物线")
        listOf("三角", "最大值", "代换", "抛物线").forEach { assertEquals(listOf("a"), ids(it)) }
        assertEquals(listOf("a"), ids("答案专用词"))
    }

    @Test fun `wildcards and backslash are searched literally`() = runBlocking {
        question("percent", text = "概率 50%")
        question("underscore", text = "a_b")
        question("slash", text = "\\frac{x}{2}")
        question("other", text = "abc 50 分")
        assertEquals(listOf("percent"), ids("%"))
        assertEquals(listOf("underscore"), ids("_"))
        assertEquals(listOf("slash"), ids("\\"))
        assertEquals(listOf("slash"), ids("\\frac"))
    }

    @Test fun `card preview is newest readable message with transcript fallback`() = runBlocking {
        question("a", text = "")
        db.conversationDao().setTranscript("ua", "OCR 首题")
        assertEquals("OCR 首题", repository.observeHistory().first().single().recentContent)
        db.requestDao().insertMessage(MessageEntity(id = "later", conversationId = "a", role = "user", content = "追問",
            createdAt = Instant.now().plusSeconds(1)))
        assertEquals("追問", repository.observeHistory().first().single().recentContent)
    }

    @Test fun `interrupted preview and final answer remain searchable and pin order updates`() = runBlocking {
        question("a", at = 1)
        question("b", at = 2)
        db.requestDao().insertMessage(MessageEntity(id = "answer", conversationId = "a", role = "assistant",
            content = "", replyToMessageId = "ua", finalAnswer = "最终答案词"))
        db.requestDao().insertRequest(RequestEntity(requestId = "request", conversationId = "a",
            userMessageId = "ua", answerMessageId = "answer", attemptId = "attempt", status = "INTERRUPTED",
            partialText = "中断内容词"))
        val entry = repository.observeHistory("中断内容词").first().single()
        assertEquals("中断内容词", entry.recentContent)
        assertEquals("INTERRUPTED", entry.requestStatus)
        assertEquals(listOf("a"), ids("最终答案词"))
        repository.setPinned("a", true)
        assertEquals(listOf("a", "b"), ids())
        repository.setPinned("a", false)
        assertEquals(listOf("b", "a"), ids())
    }

    @Test fun `rename validates unicode and missing conversation`() = runBlocking {
        question("a")
        assertTrue(repository.rename("a", "  😀新的标题  "))
        assertEquals("😀新的标题", db.conversationDao().getConversation("a")!!.title)
        assertFalse(repository.rename("missing", "标题"))
        assertTrue(runCatching { repository.rename("a", " ") }.isFailure)
        assertTrue(repository.rename("a", "😀".repeat(80)))
        assertTrue(runCatching { repository.rename("a", "😀".repeat(81)) }.isFailure)
    }

    @Test fun `delete cascades finished records and protects preparing running`() = runBlocking {
        val statuses = listOf(RequestStatus.PREPARING, RequestStatus.RUNNING, RequestStatus.INTERRUPTED,
            RequestStatus.COMPLETED, RequestStatus.CANCELLED)
        statuses.forEach { status ->
            val id = status.name
            question(id)
            db.requestDao().insertMessage(MessageEntity(id = "a$id", conversationId = id, role = "assistant", content = ""))
            db.requestDao().insertRequest(RequestEntity(requestId = "r$id", conversationId = id,
                userMessageId = "u$id", answerMessageId = "a$id", attemptId = "attempt", status = status.name))
        }
        val deleted = repository.deleteIdle(statuses.map { it.name }.toSet() + "missing")
        assertEquals(setOf("INTERRUPTED", "COMPLETED", "CANCELLED"), deleted)
        assertEquals(setOf("PREPARING", "RUNNING"), ids().toSet())
        deleted.forEach { id ->
            assertTrue(db.conversationDao().getMessages(id).isEmpty())
            assertNull(db.requestDao().getRequest("r$id"))
            assertEquals(0, db.requestDao().updatePartial("r$id", "attempt", "迟到回调", "RUNNING", Instant.now()))
        }
    }

    @Test fun `delete retains photos shared by remaining conversations`() = runBlocking {
        question("a"); question("b")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val photo = File(context.filesDir, "shared.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        try {
            db.conversationDao().setCoverIfEmpty("a", photo.path)
            db.conversationDao().setCoverIfEmpty("b", photo.path)
            assertEquals(setOf("a"), repository.deleteIdle(setOf("a")))
            assertTrue(photo.exists())
            assertEquals(photo.path, db.conversationDao().getConversation("b")!!.coverImage)
        } finally { photo.delete() }
    }
}
