package com.moge.app.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.domain.ConversationExportSource
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
@Config(sdk = [33], application = Application::class)
class ConversationExportRepositoryTest {
    private lateinit var db: MogeDatabase
    private lateinit var repository: ConversationExportRepository
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MogeDatabase::class.java).allowMainThreadQueries().build()
        repository = ConversationExportRepository(db, Dispatchers.Unconfined)
    }
    @After fun close() { db.close() }

    @Test fun `history snapshot includes active branch follow ups without mutating selections`() = runBlocking {
        val dao = db.conversationDao()
        dao.insertConversation(ConversationEntity(id = "c", title = "卷积码"))
        val messages = listOf(
            MessageEntity(id = "q", conversationId = "c", role = "user", content = "问题"),
            MessageEntity(id = "a", conversationId = "c", role = "assistant", content = "回答", parentMessageId = "q"),
            MessageEntity(id = "q2", conversationId = "c", role = "user", content = "追问旧版", parentMessageId = "a"),
            MessageEntity(id = "q3", conversationId = "c", role = "user", content = "追问新版", parentMessageId = "a"),
            MessageEntity(id = "a3", conversationId = "c", role = "assistant", content = "", parentMessageId = "q3"),
        )
        messages.forEach { db.requestDao().insertMessage(it) }
        dao.putBranchSelection(BranchSelectionEntity("c", "a", "q3"))
        val before = dao.getBranchSelections("c")
        val result = repository.snapshot(ConversationExportSource.HISTORY, "c")
        assertEquals(listOf("q", "a", "q3", "a3"), result.messages.map { it.id })
        assertEquals(before, dao.getBranchSelections("c"))
        dao.insertConversation(ConversationEntity(id = "empty", title = "空对话"))
        val catalogue = repository.catalogue(ConversationExportSource.HISTORY)
        assertEquals(listOf("c"), catalogue.map { it.id })
        assertEquals("问题", catalogue.single().preview)
    }

    @Test fun `notebook export reads immutable saved answer after original conversation deleted`() = runBlocking {
        val entry = NotebookEntryEntity(id = "saved", sourceConversationId = "gone", sourceQuestionId = "q", sourceAnswerId = "a",
            title = "保存的题目", questionText = "求积分", answerText = "保存的答案", figurePaths = "[\"\",\"plot.png\"]",
            createdAt = java.time.Instant.parse("2026-11-16T12:00:00Z"))
        db.notebookDao().insertEntry(entry)
        val result = repository.snapshot(ConversationExportSource.NOTEBOOK, "saved")
        assertEquals(entry, result.notebook)
        assertTrue(result.messages.isEmpty())
        assertEquals("求积分", repository.catalogue(ConversationExportSource.NOTEBOOK).single().preview)
        db.notebookDao().deleteEntries(listOf("saved"))
        try { repository.snapshot(ConversationExportSource.NOTEBOOK, "saved"); fail("Deleted selections must report failure") }
        catch (_: IllegalStateException) { }
    }
}
