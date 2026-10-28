package com.moge.app.runtime

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.MogeDatabase
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.llm.*
import com.moge.app.data.parse.ReplyParser
import com.moge.app.domain.RequestStatus
import com.moge.app.domain.SolveMode
import io.mockk.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GenerationElapsedLifecycleTest {
    @Test fun `elapsed origin precedes preparation and final duration includes rendering and database commit`() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MogeDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.conversationDao().insertConversation(ConversationEntity(id = "c", title = "测试"))
            val nanos = AtomicLong(1_000_000_000L)
            fun advance(ms: Long) { nanos.addAndGet(ms * 1_000_000) }
            val repository = spyk(RequestRepository(db.requestDao(), Dispatchers.Unconfined))
            coEvery { repository.complete(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                advance(1000) // The write itself takes time, after the old timer's stop point.
                callOriginal()
            }
            val snapshot = RequestSnapshot(model = "test", endpointIdentity = "https://test.invalid")
            val preparer = mockk<GenerationPreparer>(relaxed = true)
            coEvery { preparer.captureInitialSnapshot(any(), any(), any(), any()) } coAnswers { advance(1000); snapshot }
            coEvery { preparer.prepare(any(), any(), any()) } coAnswers {
                advance(3000)
                GenerationPreparer.Prepared("题目", listOf(ChatMessage("user", "题目")), emptyList(),
                    SolveMode.DETAILED, false, false, snapshot.toPolicy(), snapshot.copy(prepared = true))
            }
            val proseStarted = CompletableDeferred<Unit>()
            val finishModel = CompletableDeferred<Unit>()
            val client = mockk<ModelClient>()
            coEvery { client.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                advance(2000)
                val event = arg<suspend (StreamEvent) -> Unit>(7)
                event(StreamEvent.ReasoningDelta("思考"))
                advance(3000)
                val body = """{"reply":"正文已经开始，任务还在继续。"}"""
                event(StreamEvent.AnswerDelta(body))
                proseStarted.complete(Unit)
                finishModel.await()
                ReplyParser.parse(body)
            }
            val renderer = object : FigureRenderer {
                override suspend fun render(figures: List<com.moge.app.data.parse.ReplyFigure>): List<String> {
                    advance(1000); return figures.map { "" }
                }
            }
            val guard = mockk<GenerationGuard>(relaxed = true)
            val manager = GenerationManager(client, repository, db.conversationDao(), mockk<AiCredentialStore>(relaxed = true),
                GenerationDiagnostics(), guard, MonotonicClock { nanos.get() }, GenerationFinalizer(repository, renderer),
                preparer, GenerationFigureCompleter(renderer, client))
            val completed = CompletableDeferred<GenerationManager.GenerationDiagnostic>()
            manager.diagnosticObserver = { completed.complete(it) }
            val record = manager.submit(GenerationManager.Submission("c", "题目", emptyList()))
            withTimeout(5000) { proseStarted.await() }
            assertEquals(1000L, manager.active.value.startedAtMs)
            assertTrue(manager.active.value.partialText.isNotEmpty())
            advance(2000) // Generation continues after visible prose; the clock cannot stop here.
            finishModel.complete(Unit)
            withTimeout(5000) { completed.await() }
            val answer = db.requestDao().getMessage(record.answerMessageId)!!
            assertEquals(13000L, answer.durationMs)
            assertEquals(RequestStatus.COMPLETED.name, repository.get(record.requestId)!!.status)
            assertFalse(repository.saveCompletedDuration(record.requestId, "old-attempt", record.answerMessageId, 999999))
            assertEquals(13000L, db.requestDao().getMessage(record.answerMessageId)!!.durationMs)
            withTimeout(5000) { while (manager.active.value.isRunning) delay(10) }
        } finally { db.close() }
    }
}
