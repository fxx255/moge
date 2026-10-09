package com.moge.app.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.prefs.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HistoryRetentionTest {
    private lateinit var db: MogeDatabase
    private lateinit var history: ConversationRepository
    private val now = Instant.parse("2026-10-07T12:00:00Z")
    private val cutoff = now.minusSeconds(UserSettings.DEFAULT_HISTORY_RETENTION_DAYS * 24L * 60 * 60)
    private val old = cutoff.minusSeconds(1)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MogeDatabase::class.java)
            .allowMainThreadQueries().build()
        history = ConversationRepository(db.conversationDao(), Dispatchers.Unconfined)
    }
    @After fun teardown() { db.close() }

    private suspend fun conversation(id: String, at: Instant = old, category: String? = null, pinned: Boolean = false) {
        db.conversationDao().insertConversation(ConversationEntity(id = id, title = id,
            createdAt = at, updatedAt = at, categoryId = category, pinned = pinned))
        db.requestDao().insertMessage(MessageEntity(id = "q$id", conversationId = id, role = "user", content = "问题", createdAt = at))
    }
    private suspend fun request(id: String, status: String, at: Instant = old) {
        db.requestDao().insertMessage(MessageEntity(id = "a$id", conversationId = id, role = "assistant",
            content = "解答", replyToMessageId = "q$id", parentMessageId = "q$id", createdAt = old))
        db.requestDao().insertRequest(RequestEntity(requestId = "r$id", conversationId = id, userMessageId = "q$id",
            answerMessageId = "a$id", attemptId = "attempt", status = status, createdAt = old, updatedAt = at))
    }
    private suspend fun favorite(id: String) {
        db.notebookDao().insertEntry(NotebookEntryEntity(id = "f$id", sourceConversationId = id,
            sourceQuestionId = "q$id", sourceAnswerId = "a$id", title = id, questionText = "问题", answerText = "解答",
            createdAt = old, updatedAt = old))
    }

    @Test fun `expires at seven days exactly and recent activity restarts the interval`() = runBlocking {
        conversation("older")
        conversation("boundary", cutoff)
        conversation("recent", cutoff.plusMillis(1))
        conversation("updated")
        db.requestDao().touchConversation("updated", cutoff.plusMillis(1))
        assertEquals(setOf("older", "boundary"), history.deleteExpired(now).toSet())
        assertEquals(setOf("recent", "updated"), history.allIds())
        assertTrue(history.deleteExpired(now).isEmpty())
    }

    @Test fun `favorites and classifications are retained and pinning alone is not a favorite`() = runBlocking {
        db.notebookDao().insertCategory(NotebookCategoryEntity(id = "category", name = "通信"))
        conversation("categorized", category = "category")
        conversation("favorite")
        favorite("favorite")
        conversation("pinned", pinned = true)
        conversation("ordinary")
        assertEquals(setOf("pinned", "ordinary"), history.deleteExpired(now).toSet())
        assertEquals(setOf("categorized", "favorite"), history.allIds())
        assertNotNull(db.notebookDao().getEntry("ffavorite"))
    }

    @Test fun `all in flight states survive while old terminal states expire`() = runBlocking {
        listOf("PREPARING", "RUNNING", "COMPLETED", "INTERRUPTED", "CANCELLED").forEach {
            conversation(it)
            request(it, it)
        }
        assertEquals(setOf("COMPLETED", "INTERRUPTED", "CANCELLED"), history.deleteExpired(now).toSet())
        assertEquals(setOf("PREPARING", "RUNNING"), history.allIds())
    }

    @Test fun `recent message or retry failure protects older conversation timestamp`() = runBlocking {
        conversation("message")
        db.requestDao().insertMessage(MessageEntity(id = "follow", conversationId = "message", role = "user",
            content = "追问", createdAt = cutoff.plusMillis(1)))
        conversation("retry")
        request("retry", "INTERRUPTED", cutoff.plusMillis(1))
        assertTrue(history.deleteExpired(now).isEmpty())
        assertEquals(setOf("message", "retry"), history.deleteExpired(now.plusSeconds(1)).toSet())
    }

    @Test fun `protection changes after candidate scan are rechecked by deletion transaction`() = runBlocking {
        listOf("category", "favorite", "follow", "active").forEach { conversation(it) }
        assertEquals(4, db.conversationDao().expiredConversationIds(cutoff).size)
        db.notebookDao().insertCategory(NotebookCategoryEntity(id = "c", name = "收藏分类"))
        history.moveToCategory(setOf("category"), "c")
        favorite("favorite")
        db.requestDao().touchConversation("follow", now)
        request("active", "RUNNING")
        assertTrue(history.deleteExpired(now).isEmpty())
        assertEquals(4, history.allIds().size)
    }

    @Test fun `removing category or last favorite removes retention protection`() = runBlocking {
        db.notebookDao().insertCategory(NotebookCategoryEntity(id = "c", name = "分类"))
        conversation("category", category = "c")
        conversation("favorite")
        favorite("favorite")
        assertTrue(history.deleteExpired(now).isEmpty())
        db.notebookDao().deleteCategory("c")
        db.notebookDao().deleteEntries(listOf("ffavorite"))
        assertEquals(setOf("category", "favorite"), history.deleteExpired(now).toSet())
    }

    @Test fun `expiry cascades messages requests and branch selections`() = runBlocking {
        conversation("old")
        request("old", "COMPLETED")
        db.conversationDao().putBranchSelection(BranchSelectionEntity("old", "root", "qold"))
        assertEquals(listOf("old"), history.deleteExpired(now))
        assertTrue(db.conversationDao().getMessages("old").isEmpty())
        assertTrue(db.conversationDao().getBranchSelections("old").isEmpty())
        assertNull(db.requestDao().getRequest("rold"))
    }

    @Test fun `large expired histories are deleted beyond SQLite parameter limit`() = runBlocking {
        repeat(1100) { conversation("old$it") }
        conversation("keep", now)
        assertEquals(1100, history.deleteExpired(now).size)
        assertEquals(setOf("keep"), history.allIds())
    }

    @Test fun `configured period replaces the seven day default`() = runBlocking {
        conversation("eight-days", now.minusSeconds(8L * 24 * 60 * 60))
        conversation("thirty-days", now.minusSeconds(30L * 24 * 60 * 60))
        assertEquals(listOf("thirty-days"), history.deleteExpired(now, retentionDays = 30))
        assertEquals(listOf("eight-days"), history.deleteExpired(now, retentionDays = 3))
    }
}
