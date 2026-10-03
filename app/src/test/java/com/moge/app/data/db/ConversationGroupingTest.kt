package com.moge.app.data.db

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationGroupingTest {
    private lateinit var db: MogeDatabase
    private lateinit var repository: ConversationRepository

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), MogeDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ConversationRepository(db.conversationDao(), Dispatchers.Unconfined)
    }

    @After
    fun teardown() = db.close()

    private suspend fun category(id: String): NotebookCategoryEntity =
        NotebookCategoryEntity(id = id, name = id).also { db.notebookDao().insertCategory(it) }

    private suspend fun question(
        id: String, categoryId: String? = null, pinned: Boolean = false, at: Long = 1,
        title: String = id, text: String = "question $id",
    ): ConversationEntity {
        val conversation = ConversationEntity(
            id = id, title = title, coverImage = "/photos/$id.jpg", solveMode = "DETAILED",
            pinned = pinned, createdAt = Instant.ofEpochMilli(at), updatedAt = Instant.ofEpochMilli(at + 10),
            categoryId = categoryId,
        )
        db.conversationDao().insertConversation(conversation)
        db.requestDao().insertMessage(MessageEntity(
            id = "u-$id", conversationId = id, role = "user", content = text,
            createdAt = Instant.ofEpochMilli(at + 1),
        ))
        return conversation
    }

    private suspend fun ids(
        query: String = "", categoryId: String? = null, uncategorizedOnly: Boolean = false,
    ): List<String> = repository.observeHistory(query, categoryId, uncategorizedOnly)
        .first().map { it.conversation.id }

    private fun favorite(conversation: ConversationEntity, categoryId: String?) = NotebookEntryEntity(
        id = "favorite", categoryId = categoryId, sourceConversationId = conversation.id,
        sourceQuestionId = "u-" + conversation.id, sourceAnswerId = "a-" + conversation.id,
        title = "snapshot", questionText = "stored question", answerText = "stored answer",
        createdAt = Instant.ofEpochMilli(20), updatedAt = Instant.ofEpochMilli(30),
    )

    private suspend fun batchConversations(count: Int, categoryId: String? = null): List<ConversationEntity> =
        db.withTransaction {
            (0 until count).map { index ->
                ConversationEntity(
                    id = "batch-$index", title = "题目 $index", categoryId = categoryId,
                    createdAt = Instant.ofEpochMilli(index.toLong()),
                    updatedAt = Instant.ofEpochMilli(index.toLong() + 1),
                ).also { db.conversationDao().insertConversation(it) }
            }
        }

    private suspend fun conversations(): Map<String, ConversationEntity> =
        db.conversationDao().observeConversations().first().associateBy { it.id }

    @Test
    fun `filters preserve user message criteria and pinned chronological order`() = runBlocking {
        category("a")
        category("b")
        question("old-pinned", categoryId = "a", pinned = true)
        question("old", categoryId = "a", at = 2)
        question("other", categoryId = "b", at = 20)
        val ungrouped = question("ungrouped", at = 30)
        assertNull(ungrouped.categoryId)
        db.conversationDao().insertConversation(ConversationEntity(id = "empty", title = "empty", categoryId = "a"))
        db.conversationDao().insertConversation(ConversationEntity(
            id = "assistant-only", title = "assistant-only", categoryId = "a",
        ))
        db.requestDao().insertMessage(MessageEntity(
            conversationId = "assistant-only", role = "assistant", content = "answer",
        ))
        db.conversationDao().insertConversation(ConversationEntity(id = "ungrouped-empty", title = "empty"))

        val all = listOf("old-pinned", "ungrouped", "other", "old")
        assertEquals(all, ids())
        assertEquals(all, db.conversationDao().observeHistory("").first().map { it.conversation.id })
        assertEquals(listOf("old-pinned", "old"), ids(categoryId = "a"))
        assertEquals(listOf("other"), ids(categoryId = "b"))
        assertEquals(listOf("ungrouped"), ids(uncategorizedOnly = true))
        assertTrue(ids(categoryId = "missing").isEmpty())
        assertTrue(ids(categoryId = "a", uncategorizedOnly = true).isEmpty())
    }

    @Test
    fun `category filters intersect all existing search fields and literal wildcards`() = runBlocking {
        category("a")
        category("b")
        question("match", categoryId = "a", title = "topic", text = "question 50% a_b \\frac{x}{2}")
        question("other-group", categoryId = "b", text = "topic question display ocr answer final partial 50% a_b \\frac")
        question("ungrouped", text = "topic question display ocr answer final partial 50% a_b \\frac")
        db.requestDao().insertMessage(MessageEntity(
            id = "answer", conversationId = "match", role = "assistant", content = "answer",
            displayContent = "display", transcript = "ocr", finalAnswer = "final",
            replyToMessageId = "u-match", createdAt = Instant.ofEpochMilli(3),
        ))
        db.requestDao().insertRequest(RequestEntity(
            requestId = "request", conversationId = "match", userMessageId = "u-match",
            answerMessageId = "answer", attemptId = "attempt", status = "INTERRUPTED", partialText = "partial",
        ))

        listOf("topic", "question", "display", "ocr", "answer", "final", "partial", "%", "_", "\\").forEach { query ->
            assertEquals(query, listOf("match"), ids(query, categoryId = "a"))
        }
        assertEquals(listOf("ungrouped"), ids("%", uncategorizedOnly = true))
        assertTrue(ids("absent", categoryId = "a").isEmpty())
        val preview = repository.observeHistory("partial", categoryId = "a").first().single()
        assertEquals("partial", preview.recentContent)
        assertEquals("INTERRUPTED", preview.requestStatus)
    }

    @Test
    fun `moving and clearing change only grouping while favorites remain independent`() = runBlocking {
        category("a")
        category("b")
        val original = question("history", categoryId = "a", pinned = true, at = 10)
        val saved = favorite(original, "a")
        db.notebookDao().insertEntry(saved)
        val messages = repository.messages(original.id)

        assertEquals(1, repository.moveToCategory(setOf(original.id, "missing"), "b"))
        assertEquals(original.copy(categoryId = "b"), repository.getConversation(original.id))
        assertEquals(saved, db.notebookDao().getEntry(saved.id))
        assertEquals(messages, repository.messages(original.id))
        assertTrue(ids(categoryId = "a").isEmpty())
        assertEquals(listOf(original.id), ids(categoryId = "b"))
        assertEquals(0, repository.moveToCategory(setOf(original.id), "b"))

        assertEquals(1, repository.moveToCategory(setOf(original.id), null))
        assertEquals(original.copy(categoryId = null), repository.getConversation(original.id))
        assertEquals(listOf(original.id), ids(uncategorizedOnly = true))
        assertEquals(0, repository.moveToCategory(setOf(original.id), null))
        val notebook = NotebookRepository(db, db.notebookDao(), Dispatchers.Unconfined)
        assertEquals(1, notebook.moveEntries(setOf(saved.id), "b"))
        assertEquals(original.copy(categoryId = null), repository.getConversation(original.id))
        assertEquals(messages, repository.messages(original.id))
    }

    @Test
    fun `empty selections and missing conversation ids are no ops`() = runBlocking {
        category("a")
        assertEquals(0, repository.moveToCategory(emptySet(), "missing"))
        assertEquals(0, repository.moveToCategory(emptySet(), null))
        assertEquals(0, repository.moveToCategory(setOf("missing"), "a"))
        assertEquals(0, repository.moveToCategory(setOf("missing"), null))
        val failure = runCatching { repository.moveToCategory(setOf("missing"), "missing") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `deleting a category clears grouping and keeps histories messages requests and favorites`() = runBlocking {
        category("a")
        val original = question("history", categoryId = "a")
        question("ungrouped", at = 20)
        db.requestDao().insertMessage(MessageEntity(
            id = "a-history", conversationId = original.id, role = "assistant", content = "answer",
        ))
        val request = RequestEntity(
            requestId = "request", conversationId = original.id, userMessageId = "u-history",
            answerMessageId = "a-history", attemptId = "attempt", status = "RUNNING", partialText = "partial",
            createdAt = Instant.ofEpochMilli(10), updatedAt = Instant.ofEpochMilli(11),
        )
        db.requestDao().insertRequest(request)
        val saved = favorite(original, "a")
        db.notebookDao().insertEntry(saved)
        val messages = repository.messages(original.id)

        assertEquals(1, db.notebookDao().deleteCategory("a"))
        assertEquals(original.copy(categoryId = null), repository.getConversation(original.id))
        assertEquals(messages, repository.messages(original.id))
        assertEquals(request, db.requestDao().getRequest(request.requestId))
        assertEquals(saved.copy(categoryId = null), db.notebookDao().getEntry(saved.id))
        assertEquals(listOf("ungrouped", "history"), ids(uncategorizedOnly = true))
        assertTrue(ids(categoryId = "a").isEmpty())
    }

    @Test
    fun `more than a thousand ids move and clear with exact counts and unchanged timestamps`() = runBlocking {
        category("a")
        val before = batchConversations(1_201)
        val selection = before.map { it.id }.toSet()
        assertEquals(before.size, repository.moveToCategory(selection + "missing", "a"))
        assertEquals(before.associateBy({ it.id }, { it.copy(categoryId = "a") }), conversations())
        assertEquals(0, repository.moveToCategory(selection, "a"))
        assertEquals(before.size, repository.moveToCategory(selection, null))
        assertEquals(before.associateBy { it.id }, conversations())
    }

    @Test
    fun `invalid or deleted target leaves every selected batch unchanged`() = runBlocking {
        category("source")
        category("deleted")
        val before = batchConversations(600, "source")
        val selection = before.map { it.id }.toSet()
        db.notebookDao().deleteCategory("deleted")
        listOf("missing", "deleted", "").forEach { target ->
            val failure = runCatching { repository.moveToCategory(selection, target) }.exceptionOrNull()
            assertTrue(target, failure is IllegalArgumentException)
            assertEquals(before.associateBy { it.id }, conversations())
        }
    }

    @Test
    fun `failure in a later batch rolls back all earlier batches`() = runBlocking {
        category("a")
        val before = batchConversations(600)
        val selection = before.map { it.id }.toSet()
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_last_move BEFORE UPDATE OF category_id ON conversation
            WHEN NEW.id = 'batch-599'
            BEGIN SELECT RAISE(ABORT, 'reject last move'); END
        """.trimIndent())
        try {
            assertTrue(runCatching { repository.moveToCategory(selection, "a") }.isFailure)
            assertEquals(before.associateBy { it.id }, conversations())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_last_move")
        }
        assertEquals(before.size, repository.moveToCategory(selection, "a"))
    }

    @Test
    fun `history observers update after moving and deleting a category`() = runBlocking {
        category("a")
        question("history")
        repository.observeHistory(categoryId = "a").test {
            assertTrue(awaitItem().isEmpty())
            repository.moveToCategory(setOf("history"), "a")
            assertEquals(listOf("history"), awaitItem().map { it.conversation.id })
            db.notebookDao().deleteCategory("a")
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
