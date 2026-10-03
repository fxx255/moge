package com.moge.app.data.db

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.moge.app.ui.notebook.NotebookViewModel
import com.moge.app.ui.solve.AnswerState
import com.moge.app.ui.solve.FavoriteTarget
import com.moge.app.ui.solve.FavoriteViewModel
import com.moge.app.ui.solve.SolveItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/** Real Room interoperability between the dialog writer, history projection and notebook reader. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HistoryFavoritesIntegrationTest {
    private lateinit var db: MogeDatabase
    private lateinit var notebook: NotebookRepository
    private lateinit var history: ConversationRepository
    private val viewModels = ViewModelStore()

    @Before fun setup() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MogeDatabase::class.java)
            .allowMainThreadQueries().build()
        notebook = NotebookRepository(db, db.notebookDao(), Dispatchers.Unconfined)
        history = ConversationRepository(db.conversationDao(), Dispatchers.Unconfined)
    }
    @After fun teardown() {
        viewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun conversation(id: String, category: String? = null): ConversationEntity =
        ConversationEntity(id = id, title = "历史 " + id, categoryId = category).also {
            db.conversationDao().insertConversation(it)
        }
    private suspend fun question(conversation: String, id: String = "q" + conversation, at: Long = 1): MessageEntity =
        MessageEntity(id = id, conversationId = conversation, role = "user", content = "模型输入 " + id,
            displayContent = "显示问题 " + id, transcript = "识题 " + id,
            imagePaths = RequestRepository.encodePathList(listOf("/" + id + ".jpg")), createdAt = Instant.ofEpochMilli(at))
            .also { db.requestDao().insertMessage(it) }
    private suspend fun answer(conversation: String, question: String, id: String = "a" + conversation,
        status: String? = "COMPLETED", at: Long = 2): MessageEntity =
        MessageEntity(id = id, conversationId = conversation, role = "assistant", content = "原始解答 " + id,
            displayContent = "显示解答 " + id, finalAnswer = "最终答案 " + id, replyToMessageId = question,
            imagePaths = RequestRepository.encodePathList(listOf("", "/" + id + ".png")), createdAt = Instant.ofEpochMilli(at))
            .also {
                db.requestDao().insertMessage(it)
                if (status != null) db.requestDao().insertRequest(RequestEntity(requestId = "r" + id,
                    conversationId = conversation, userMessageId = question, answerMessageId = id,
                    attemptId = "attempt", status = status))
            }

    @Test fun `dialog save is visible in notebook and history favorite badge invalidates on add and remove`() = runBlocking {
        val staleCategory = notebook.createCategory("旧筛选分类")
        val source = conversation("historical")
        val question = question(source.id)
        val answer = answer(source.id, question.id)
        val model = FavoriteViewModel(notebook)
        viewModels.put("favorite", model)
        model.open(answer.id)
        model.loading.first { !it }
        val target = FavoriteTarget(source.id, source.title,
            SolveItem.Question(question.id, question.displayContent!!,
                RequestRepository.decodePathListStrict(question.imagePaths), question.transcript, true),
            SolveItem.Answer(answer.id, AnswerState.COMPLETED, answer.displayContent!!,
                RequestRepository.decodePathListStrict(answer.imagePaths), finalAnswer = answer.finalAnswer,
                replyToMessageId = question.id))
        history.observeHistory().distinctUntilChanged().test {
            val initial = awaitItem().single()
            assertEquals(0, initial.favoriteCount)
            assertEquals(question.displayContent, initial.questionPreview)
            assertEquals(answer.displayContent, initial.recentContent)
            assertEquals("COMPLETED", initial.requestStatus)
            val saved = CompletableDeferred<String>()
            model.save(target, null, "") { saved.complete(it) }
            val id = saved.await()
            assertEquals(1, awaitItem().single().favoriteCount)
            val visible = notebook.observeEntries().first().single()
            assertEquals(id, visible.id)
            assertEquals(question.id, visible.sourceQuestionId)
            assertEquals(answer.id, visible.sourceAnswerId)
            assertEquals(question.displayContent, visible.questionText)
            assertEquals(question.imagePaths, visible.questionImagePaths)
            assertEquals(answer.displayContent, visible.answerText)
            assertEquals(answer.imagePaths, visible.figurePaths)
            val handle = SavedStateHandle(mapOf("notebookEntryId" to id,
                "notebookQuery" to "unrelated old query", "notebookCategory" to staleCategory.id))
            val reader = NotebookViewModel(handle, notebook, history)
            viewModels.put("notebook", reader)
            val revealed = reader.state.first { !it.loading && it.detailEntry != null }
            assertEquals(id, revealed.openEntryId)
            assertEquals(visible, revealed.detailEntry)
            assertTrue(revealed.entries.isEmpty())
            assertEquals(staleCategory.id, revealed.categoryId)
            assertEquals("unrelated old query", revealed.query)
            reader.closeEntry()
            assertEquals("", handle.get<String>("notebookOpenEntry"))
            assertEquals(listOf(id), notebook.saveConversations(setOf(source.id)))
            assertEquals(visible, notebook.observeEntries().first().single())
            notebook.deleteFavorites(setOf(id))
            assertEquals(0, awaitItem().single().favoriteCount)
            assertNotNull(history.getConversation(source.id))
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `history collects latest completed explicit followup and retains snapshot category on repeat`() = runBlocking {
        val sourceCategory = notebook.createCategory("源会话分类")
        val otherCategory = notebook.createCategory("后来分类")
        val source = conversation("followup", sourceCategory.id)
        val firstQuestion = question(source.id)
        val firstAnswer = answer(source.id, firstQuestion.id)
        val oldFavorite = notebook.saveFavorite(source, firstQuestion, firstAnswer, otherCategory.id)
        val followup = question(source.id, "followup-question", 3)
        question(source.id, "unrelated-adjacent-question", 4)
        val completed = answer(source.id, followup.id, "followup-answer", at = 5)
        answer(source.id, firstQuestion.id, "later-running", "RUNNING", 6)
        answer(source.id, "missing-question", "later-unlinked", status = null, at = 7)
        val id = notebook.saveConversations(setOf(source.id)).single()
        val saved = db.notebookDao().getEntry(id)!!
        assertEquals(completed.id, saved.sourceAnswerId)
        assertEquals(followup.id, saved.sourceQuestionId)
        assertEquals(followup.displayContent, saved.questionText)
        assertEquals(followup.transcript, saved.questionTranscript)
        assertEquals(followup.imagePaths, saved.questionImagePaths)
        assertEquals(completed.displayContent, saved.answerText)
        assertEquals(completed.finalAnswer, saved.finalAnswer)
        assertEquals(completed.imagePaths, saved.figurePaths)
        assertEquals(sourceCategory.id, saved.categoryId)
        assertEquals(oldFavorite, db.notebookDao().getEntry(oldFavorite.id))
        history.moveToCategory(setOf(source.id), otherCategory.id)
        db.requestDao().updateMessageContent(completed.id, "later provider text")
        db.conversationDao().setTranscript(followup.id, "later transcript")
        assertEquals(listOf(id), notebook.saveConversations(setOf(source.id)))
        assertEquals(saved, db.notebookDao().getEntry(id))
        assertEquals(2, history.observeHistory().first().single().favoriteCount)
        val newestQuestion = question(source.id, "newest-question", 8)
        val newestAnswer = answer(source.id, newestQuestion.id, "newest-answer", at = 9)
        val newestId = notebook.saveConversations(setOf(source.id)).single()
        assertNotEquals(id, newestId)
        assertEquals(newestAnswer.id, db.notebookDao().getEntry(newestId)!!.sourceAnswerId)
        assertEquals(otherCategory.id, db.notebookDao().getEntry(newestId)!!.categoryId)
        assertEquals(saved, db.notebookDao().getEntry(id))
        assertEquals(3, notebook.observeEntries().first().size)
    }

    @Test fun `only completed or explicitly linked finalized legacy answers are accepted`() = runBlocking {
        val rejected = listOf("PREPARING", "RUNNING", "INTERRUPTED", "CANCELLED", "FAILED", "STOPPED")
        for (status in rejected) {
            conversation(status)
            val question = question(status)
            answer(status, question.id, status = status)
        }
        conversation("completed")
        val completedQuestion = question("completed")
        answer("completed", completedQuestion.id)
        conversation("legacy")
        val legacyQuestion = question("legacy")
        answer("legacy", legacyQuestion.id, status = null)
        conversation("unlinked")
        question("unlinked")
        answer("unlinked", "", status = null)
        conversation("mismatched-request")
        val linkedQuestion = question("mismatched-request")
        val otherQuestion = question("mismatched-request", "request-question")
        val mismatched = answer("mismatched-request", linkedQuestion.id)
        db.requestDao().updateRequest(db.requestDao().getRequest("r" + mismatched.id)!!.copy(userMessageId = otherQuestion.id))
        conversation("multiple-requests")
        val multipleQuestion = question("multiple-requests")
        val multipleAnswer = answer("multiple-requests", multipleQuestion.id)
        db.requestDao().insertRequest(RequestEntity(requestId = "another-request", conversationId = "multiple-requests",
            userMessageId = multipleQuestion.id, answerMessageId = multipleAnswer.id, attemptId = "later-attempt", status = "RUNNING"))
        conversation("wrong-question-role")
        val wrongRoleQuestion = question("wrong-question-role")
        val wrongRoleAnswer = answer("wrong-question-role", wrongRoleQuestion.id)
        answer("wrong-question-role", wrongRoleAnswer.id, id = "assistant-reply", status = null, at = 3)
        // Mark the otherwise valid older answer unfinished so only the invalid assistant-to-assistant reply remains.
        db.requestDao().updateStatus("r" + wrongRoleAnswer.id, "INTERRUPTED", Instant.now())
        conversation("cross-conversation")
        question("cross-conversation")
        answer("cross-conversation", legacyQuestion.id, status = null)
        conversation("blank-completed")
        val blankQuestion = question("blank-completed")
        db.requestDao().insertMessage(MessageEntity(id = "blank-answer", conversationId = "blank-completed",
            role = "assistant", content = "", replyToMessageId = blankQuestion.id))
        db.requestDao().insertRequest(RequestEntity(conversationId = "blank-completed", userMessageId = blankQuestion.id,
            answerMessageId = "blank-answer", attemptId = "attempt", status = "COMPLETED"))
        conversation("question-only")
        question("question-only")
        val ids = rejected.toSet() + setOf("completed", "legacy", "unlinked", "mismatched-request",
            "cross-conversation", "multiple-requests", "wrong-question-role", "blank-completed", "question-only", "missing")
        assertEquals(2, notebook.saveConversations(ids).size)
        assertEquals(setOf("completed", "legacy"), notebook.observeEntries().first().map { it.sourceConversationId }.toSet())
    }

    @Test fun `concurrent history collections dedupe by answer without updating existing favorites`() = runBlocking {
        val source = conversation("concurrent")
        val question = question(source.id)
        answer(source.id, question.id)
        val ids = (1..4).map { async(Dispatchers.Default) { notebook.saveConversations(setOf(source.id)).single() } }.awaitAll()
        assertEquals(1, ids.toSet().size)
        assertEquals(1, notebook.observeEntries().first().size)
        val before = db.notebookDao().getEntry(ids.first())!!
        assertEquals(ids.first(), notebook.saveConversations(setOf(source.id)).single())
        assertEquals(before, db.notebookDao().getEntry(ids.first()))
    }

    @Test fun `failure in a later selected conversation rolls back all new favorites and preserves existing ones`() = runBlocking {
        for (id in listOf("a", "z", "existing")) {
            conversation(id)
            val question = question(id)
            answer(id, question.id)
        }
        val existingId = notebook.saveConversations(setOf("existing")).single()
        val before = db.notebookDao().getEntry(existingId)!!
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_history_favorite BEFORE INSERT ON notebook_entry
            WHEN NEW.source_conversation_id = 'z'
            BEGIN SELECT RAISE(ABORT, 'reject later favorite'); END
        """.trimIndent())
        try {
            assertTrue(runCatching { notebook.saveConversations(setOf("a", "z", "existing")) }.isFailure)
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_history_favorite")
        }
        assertEquals(listOf(before), notebook.observeEntries().first())
        assertEquals(2, notebook.saveConversations(setOf("a", "z")).size)
    }

    @Test fun `latest legacy answer uses rowid to break equal message timestamps`() = runBlocking {
        val source = conversation("ties")
        val question = question(source.id)
        answer(source.id, question.id, id = "z-old-answer", status = null)
        val newest = answer(source.id, question.id, id = "a-new-answer", status = null)
        val id = notebook.saveConversations(setOf(source.id)).single()
        assertEquals(newest.id, db.notebookDao().getEntry(id)!!.sourceAnswerId)
        assertTrue(notebook.saveConversations(emptySet()).isEmpty())
    }
}
