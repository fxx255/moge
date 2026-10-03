package com.moge.app.ui.solve

import com.moge.app.data.db.NotebookEntryEntity
import com.moge.app.data.db.NotebookRepository
import com.moge.app.data.db.RequestRepository
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<NotebookRepository>()
    private fun target(answer: String = "answer") = FavoriteTarget("historical", "历史追问",
        SolveItem.Question("question", "显示问题", listOf("/question.jpg"), "识题正文", false),
        SolveItem.Answer(answer, AnswerState.COMPLETED, "完整显示解答", listOf("", "/plot.png"),
            finalAnswer = "最终答案", replyToMessageId = "question"))
    private fun entry(id: String = "existing") = NotebookEntryEntity(id = id,
        sourceConversationId = "historical", sourceQuestionId = "question", sourceAnswerId = "answer",
        title = "历史追问", questionText = "显示问题", answerText = "完整显示解答")
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeCategories() } returns MutableStateFlow(emptyList())
        coEvery { repository.favorite(any()) } returns null
    }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun `save emits repository id and preserves exact historical question answer and images`() {
        val snapshot = slot<NotebookEntryEntity>()
        coEvery { repository.favorite("answer") } returns entry("already-saved")
        coEvery { repository.save(capture(snapshot)) } returns "already-saved"
        val model = FavoriteViewModel(repository)
        model.open("answer")
        assertEquals("already-saved", model.existing.value!!.id)
        var savedId: String? = null
        model.save(target(), "category", "") { savedId = it }
        assertEquals("already-saved", savedId)
        assertEquals("historical", snapshot.captured.sourceConversationId)
        assertEquals("question", snapshot.captured.sourceQuestionId)
        assertEquals("answer", snapshot.captured.sourceAnswerId)
        assertEquals("显示问题", snapshot.captured.questionText)
        assertEquals("识题正文", snapshot.captured.questionTranscript)
        assertEquals(listOf("/question.jpg"), RequestRepository.decodePathListStrict(snapshot.captured.questionImagePaths))
        assertEquals("完整显示解答", snapshot.captured.answerText)
        assertEquals("最终答案", snapshot.captured.finalAnswer)
        assertEquals(listOf("", "/plot.png"), RequestRepository.decodePathListStrict(snapshot.captured.figurePaths))
        assertEquals("category", snapshot.captured.categoryId)
        assertFalse(model.busy.value)
    }

    @Test fun `callback waits for a successful save and overlapping save is ignored`() {
        val pending = CompletableDeferred<String>()
        coEvery { repository.save(any()) } coAnswers { pending.await() }
        val model = FavoriteViewModel(repository)
        model.open("answer")
        var savedId: String? = null
        model.save(target(), null, "") { savedId = it }
        model.save(target(), null, "") { savedId = it }
        coVerify(exactly = 1) { repository.save(any()) }
        assertNull(savedId)
        assertTrue(model.busy.value)
        pending.complete("actual-id")
        assertEquals("actual-id", savedId)
        assertFalse(model.busy.value)
    }

    @Test fun `failed save reports error without emitting a favorite id`() {
        coEvery { repository.save(any()) } throws IllegalStateException("disk")
        val model = FavoriteViewModel(repository)
        model.open("answer")
        var savedId: String? = null
        model.save(target(), null, "") { savedId = it }
        assertNull(savedId)
        assertEquals("disk", model.error.value)
        assertFalse(model.busy.value)
    }

    @Test fun `obsolete read error for the same reopened answer cannot overwrite current state`() {
        val pending = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { repository.favorite("answer") } coAnswers {
            if (++calls == 1) withContext(NonCancellable) { pending.await(); error("obsolete read") }
            else entry("current")
        }
        val model = FavoriteViewModel(repository)
        model.open("answer")
        model.open("answer")
        assertEquals("current", model.existing.value!!.id)
        pending.complete(Unit)
        assertEquals("current", model.existing.value!!.id)
        assertNull(model.error.value)
        assertFalse(model.loading.value)
    }

    @Test fun `obsolete save failure cannot overwrite error or busy state of a new save`() {
        val first = CompletableDeferred<String>()
        val second = CompletableDeferred<String>()
        coEvery { repository.save(match { it.sourceAnswerId == "answer" }) } coAnswers {
            withContext(NonCancellable) { first.await() }
        }
        coEvery { repository.save(match { it.sourceAnswerId == "second" }) } coAnswers { second.await() }
        val model = FavoriteViewModel(repository)
        model.open("answer")
        var obsoleteCallback = false
        model.save(target(), null, "") { obsoleteCallback = true }
        model.open("second")
        var savedId: String? = null
        model.save(target("second"), null, "") { savedId = it }
        first.completeExceptionally(IllegalStateException("obsolete save"))
        assertFalse(obsoleteCallback)
        assertNull(model.error.value)
        assertTrue(model.busy.value)
        second.complete("second-favorite")
        assertEquals("second-favorite", savedId)
        assertFalse(model.busy.value)
    }

    @Test fun `changing target cancels pending save without showing an error or calling old callback`() {
        var cancelled = false
        coEvery { repository.save(any()) } coAnswers {
            try { awaitCancellation() } finally { cancelled = true }
        }
        val model = FavoriteViewModel(repository)
        model.open("answer")
        var called = false
        model.save(target(), null, "") { called = true }
        model.open("second")
        assertTrue(cancelled)
        assertFalse(called)
        assertFalse(model.busy.value)
        assertNull(model.error.value)
        model.save(target(), null, "") { called = true }
        coVerify(exactly = 1) { repository.save(any()) }
    }
}
