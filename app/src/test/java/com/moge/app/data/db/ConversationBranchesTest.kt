package com.moge.app.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationBranchesTest {
    @Test fun nestedBranchesPreserveDescendantsAndCanBeRevealedBySearch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MogeDatabase::class.java).allowMainThreadQueries().build()
        try {
            val dao = db.conversationDao()
            val repo = RequestRepository(db.requestDao(), Dispatchers.Unconfined)
            val conversations = ConversationRepository(dao, Dispatchers.Unconfined)
            dao.insertConversation(ConversationEntity(id = "c", title = "tree"))
            suspend fun turn(q: String, parent: String? = ConversationBranches.AUTO_PARENT): RequestEntity {
                val request = repo.createRequest("c", q, "a-$q", "attempt-$q", q, emptyList(), "", parentMessageId = parent)
                assertTrue(repo.complete(request.requestId, request.attemptId, request.answerMessageId, "回答 " + q))
                return request
            }
            turn("q1", null)
            turn("q2")
            turn("q3")
            turn("q2b", "a-q1")
            turn("q3b")
            turn("q3c", "a-q2b")
            fun ids(messages: List<MessageEntity>) = messages.map { it.id }
            assertEquals(listOf("q1", "a-q1", "q2b", "a-q2b", "q3c", "a-q3c"), ids(conversations.visibleMessages("c")))
            val allBefore = conversations.messages("c")
            conversations.selectBranch("c", "q2")
            assertEquals(listOf("q1", "a-q1", "q2", "a-q2", "q3", "a-q3"), ids(conversations.visibleMessages("c")))
            assertEquals("a-q3", dao.latestCompletedAnswer("c")!!.id)
            conversations.selectBranch("c", "q2b")
            assertEquals("q3c", conversations.visibleMessages("c").last { it.role == "user" }.id)
            assertEquals("a-q3c", dao.latestCompletedAnswer("c")!!.id)
            conversations.selectBranch("c", "q3b")
            assertEquals("q3b", conversations.visibleMessages("c").last { it.role == "user" }.id)
            assertEquals("a-q3c", conversations.revealSearchMatch("c", "q3"))
            assertEquals("q3c", conversations.visibleMessages("c").last { it.role == "user" }.id)
            conversations.revealBranch("c", "q3")
            assertEquals("q3", conversations.visibleMessages("c").last { it.role == "user" }.id)
            assertEquals(allBefore, conversations.messages("c"))
            assertEquals(listOf("q1", "a-q1"), ids(ConversationBranches.before(allBefore, "q2b")))
            assertEquals(listOf("q2", "q2b"), ConversationBranches.versions(allBefore, allBefore.first { it.id == "q2" }).map { it.id })
        } finally { db.close() }
    }

    @Test fun crossConversationParentAndAnswerAreRejectedAtomicallyAndSelectionsRepair() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MogeDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val dao = db.conversationDao()
            val repo = RequestRepository(db.requestDao(), Dispatchers.Unconfined)
            dao.insertConversation(ConversationEntity(id = "c", title = "tree"))
            dao.insertConversation(ConversationEntity(id = "other", title = "other"))
            repo.createRequest("c", "q1", "a1", "at1", "first", emptyList(), "")
            repo.createRequest("other", "oq", "oa", "oat", "other", emptyList(), "")
            try {
                repo.createRequest("c", "bad", "bad-a", "at2", "bad", emptyList(), "", parentMessageId = "oa")
                fail("foreign parent accepted")
            } catch (_: IllegalArgumentException) {}
            assertEquals(2, dao.getMessages("c").size)
            assertEquals(1, repo.forConversation("c").size)
            try { dao.selectBranch("c", "oq"); fail("foreign branch accepted") } catch (_: IllegalArgumentException) {}
            try { dao.selectBranch("c", "a1"); fail("answer selected as question") } catch (_: IllegalArgumentException) {}
            dao.putBranchSelection(BranchSelectionEntity("c", ConversationBranches.ROOT, "oq"))
            assertEquals(listOf("q1", "a1"), dao.visibleMessages("c").map { it.id })
            assertEquals("q1", dao.getBranchSelections("c").first { it.parentKey == ConversationBranches.ROOT }.selectedChildId)
        } finally { db.close() }
    }

    @Test fun ancestorTraversalRejectsCyclesAndMissingAnchors() {
        val one = MessageEntity(id = "one", conversationId = "c", role = "user", content = "", parentMessageId = "two")
        val two = MessageEntity(id = "two", conversationId = "c", role = "assistant", content = "", parentMessageId = "one")
        assertThrows(IllegalArgumentException::class.java) { ConversationBranches.ancestors(listOf(one, two), "one") }
        assertThrows(IllegalArgumentException::class.java) { ConversationBranches.ancestors(listOf(one), "missing") }
    }

    @Test fun longConversationCanBeDeletedWithoutRecursiveTriggerOverflow() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MogeDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.beginTransaction()
            try {
                sqlite.execSQL("INSERT INTO conversation(id,title,created_at,updated_at) VALUES('long','长对话',0,0)")
                repeat(1200) { index ->
                    sqlite.execSQL("INSERT INTO message(id,conversation_id,role,content,created_at,parent_message_id) VALUES(?,?,?,?,?,?)",
                        arrayOf<Any?>("m$index", "long", if (index % 2 == 0) "user" else "assistant", "保留的消息", index,
                            if (index == 0) null else "m" + (index - 1)))
                }
                sqlite.setTransactionSuccessful()
            } finally { sqlite.endTransaction() }
            db.conversationDao().deleteConversations(listOf("long"))
            assertTrue(db.conversationDao().getMessages("long").isEmpty())
            sqlite.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close() }
    }
}
