package com.moge.app.runtime

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.MessageEntity
import com.moge.app.data.db.MogeDatabase
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.domain.RequestStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class StoredFigureRepairTest {
    private lateinit var db: MogeDatabase
    private var calls = 0
    private var beforeRender: suspend () -> Unit = {}
    private val fixture = requireNotNull(javaClass.getResource("/fixtures/cardioid-fenced-members.md")).readText()
    private val renderer = object : FigureRenderer {
        override suspend fun render(figures: List<ReplyFigure>): List<String> {
            calls++
            assertEquals(25, (figures.single() as ReplyFigure.Plot).spec.series.single().points!!.size)
            beforeRender()
            return listOf("/figures/cardioid.png")
        }
    }

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MogeDatabase::class.java)
            .allowMainThreadQueries().build()
        db.conversationDao().insertConversation(ConversationEntity(id = "c", title = "心形线"))
    }
    @After fun close() { db.close() }
    private fun repair() = StoredFigureRepair(db.conversationDao(), renderer, Dispatchers.Unconfined)
    private suspend fun message(paths: String = ""): MessageEntity {
        val message = MessageEntity(id = "a", conversationId = "c", role = "assistant", content = fixture, imagePaths = paths)
        db.requestDao().insertMessage(message)
        return message
    }

    @Test fun `old completed text is preserved while display and curve paths are repaired once`() = runBlocking {
        val original = message()
        val repair = repair()
        repair.repair(listOf(original), emptyList())
        val recovered = db.requestDao().getMessage("a")!!
        assertEquals(fixture, recovered.content)
        assertFalse(recovered.displayContent!!.contains("\"plots\""))
        assertTrue(recovered.displayContent!!.contains("右端顶点"))
        assertEquals(listOf("/figures/cardioid.png"), RequestRepository.decodePathList(recovered.imagePaths))
        repair.repair(listOf(original), emptyList())
        repair.repair(listOf(recovered), emptyList())
        assertEquals(1, calls)
    }

    @Test fun `existing successful image paths are not replaced by an embedded subset`() = runBlocking {
        val original = message("[\"/old/diagram.png\"]")
        val persisted = db.requestDao().getMessage("a")!!
        repair().repair(listOf(original), emptyList())
        assertEquals(0, calls)
        assertEquals(persisted, db.requestDao().getMessage("a"))
    }

    @Test fun `in flight snapshot is never repaired`() = runBlocking {
        val original = message()
        val request = RequestEntity(conversationId = "c", userMessageId = "u", answerMessageId = "a", attemptId = "att", status = RequestStatus.RUNNING.name)
        repair().repair(listOf(original), listOf(request))
        assertEquals(0, calls)
        assertNull(db.requestDao().getMessage("a")!!.displayContent)
    }

    @Test fun `compare and update refuses a changed answer while local rendering runs`() = runBlocking {
        val original = message()
        beforeRender = { db.requestDao().updateMessageContent("a", "重新生成后的答案") }
        repair().repair(listOf(original), emptyList())
        val current = db.requestDao().getMessage("a")!!
        assertEquals("重新生成后的答案", current.content)
        assertNull(current.displayContent)
        assertEquals("", current.imagePaths)
    }

    @Test fun `database guard rejects a stale completed snapshot once regeneration starts`() = runBlocking {
        val original = message()
        val repository = RequestRepository(db.requestDao(), Dispatchers.Unconfined)
        // A second message receives a real pending request; the stale snapshot
        // deliberately omits it, exercising the database ownership guard.
        val record = repository.createRequest("c", "u2", "a2", "att", "问", emptyList(), "")
        val second = db.requestDao().getMessage(record.answerMessageId)!!
        db.requestDao().updateMessageContent(second.id, fixture)
        repair().repair(listOf(second.copy(content = fixture)), emptyList())
        assertNull(db.requestDao().getMessage(second.id)!!.displayContent)
        assertEquals("", db.requestDao().getMessage(second.id)!!.imagePaths)
        assertNull(db.requestDao().getMessage(original.id)!!.displayContent)
    }

    @Test fun `new completion clears the old display repair`() = runBlocking {
        val original = message()
        repair().repair(listOf(original), emptyList())
        db.requestDao().updateMessageContent("a", "新回答")
        db.requestDao().applyAnswerMetaRow("a", "[]", "模型", 10, "", "新答案")
        assertNull(db.requestDao().getMessage("a")!!.displayContent)
        assertEquals("新回答", db.requestDao().getMessage("a")!!.content)
    }
}
