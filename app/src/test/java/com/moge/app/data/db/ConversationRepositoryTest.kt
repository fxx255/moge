package com.moge.app.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.domain.SolveMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 新题目建会话 + 提交被拒时回收空壳：回收绝不能误删已有消息的题。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ConversationRepositoryTest {

    private lateinit var db: MogeDatabase
    private lateinit var repository: ConversationRepository
    private lateinit var requests: RequestRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MogeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ConversationRepository(db.conversationDao(), Dispatchers.Unconfined)
        requests = RequestRepository(db.requestDao(), Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `create stores title from first line and solve mode`() = runBlocking {
        val created = repository.createConversation("\n  求导：x^2\n第二行", SolveMode.DETAILED)
        val stored = db.conversationDao().getConversation(created.id)
        assertNotNull(stored)
        assertEquals("求导：x^2", stored!!.title)
        assertEquals(SolveMode.DETAILED.name, stored.solveMode)
    }

    @Test
    fun `created conversation accepts a request insert`() = runBlocking {
        val created = repository.createConversation("题", SolveMode.DETAILED)
        // 外键：请求与消息必须能挂在新会话上（这正是「先建会话再提交」的前提）。
        requests.createRequest(created.id, "u1", "a1", "att", "题", emptyList(), "")
        assertEquals(2, repository.messages(created.id).size)
    }

    @Test
    fun `discard removes only empty conversations`() = runBlocking {
        val empty = repository.createConversation("空", SolveMode.DETAILED)
        val used = repository.createConversation("有消息", SolveMode.DETAILED)
        requests.createRequest(used.id, "u1", "a1", "att", "有消息", emptyList(), "")

        assertTrue(repository.discardIfEmpty(empty.id))
        assertNull(db.conversationDao().getConversation(empty.id))
        assertFalse("已有消息的题绝不能被回收", repository.discardIfEmpty(used.id))
        assertNotNull(db.conversationDao().getConversation(used.id))
    }

    @Test
    fun `initial title falls back and truncates by code point`() {
        assertEquals(ConversationRepository.PLACEHOLDER_TITLE, ConversationRepository.initialTitle("  \n "))
        val long = "😀".repeat(50)
        val title = ConversationRepository.initialTitle(long)
        assertTrue(title.endsWith("…"))
        assertEquals(41, title.codePointCount(0, title.length))
    }

    @Test fun `manual name survives a late first answer including placeholder names`() = runBlocking {
        for ((index, name) in listOf("英语阅读易错点", "图片题目").withIndex()) {
            val created = repository.createConversation("第一问", SolveMode.DETAILED)
            val answerId = "answer-$index"
            requests.createRequest(created.id, "user-$index", answerId, "att-$index", "第一问", emptyList(), "")
            assertTrue(repository.rename(created.id, name))
            db.requestDao().nameConversationFromAnswer(created.id, answerId, "模型自动命名")
            assertEquals(name, repository.getConversation(created.id)!!.title)
            assertTrue(repository.getConversation(created.id)!!.titleUserEdited)
        }
        val automatic = repository.createConversation("第一问", SolveMode.DETAILED)
        db.requestDao().nameConversationFromAnswer(automatic.id, "answer", "模型自动命名")
        assertEquals("模型自动命名", repository.getConversation(automatic.id)!!.title)
    }

    @Test fun `manual names normalize whitespace and count full unicode characters`() = runBlocking {
        val created = repository.createConversation("题目", SolveMode.DETAILED)
        assertTrue(repository.rename(created.id, "  英语\n\t 阅读  "))
        assertEquals("英语 阅读", repository.getConversation(created.id)!!.title)
        assertTrue(repository.rename(created.id, "😀".repeat(80)))
        for (invalid in listOf(" \n\t", "😀".repeat(81))) {
            assertTrue(runCatching { repository.rename(created.id, invalid) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals("😀".repeat(80), repository.getConversation(created.id)!!.title)
    }
}
