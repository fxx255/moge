package com.moge.app.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.llm.GenerationDiagnostics
import com.moge.app.data.llm.ModelClient
import com.moge.app.data.parse.ReplyParser
import com.moge.app.data.llm.StreamEvent
import com.moge.app.data.llm.MonotonicClock
import com.moge.app.data.db.MogeDatabase
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.llm.ChatMessage
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 诊断：确认「完成瞬间闪过 COMPLETED、随即被兜底写入改回 RUNNING」已消失。
 *
 * 这是本轮整改的首要根因（旧 `finally` 里的 `savePartial` 默认状态 RUNNING）。
 * 保留这个探针作为**可复核的证据**：先前的探针在 t=0ms 打印 COMPLETED、
 * t=100ms 起就一直打印 RUNNING。现在全程必须是 COMPLETED。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class ManagerStatusProbe {

    private class FakeGuard : GenerationGuard {
        private val held = mutableSetOf<String>()
        override fun acquire(requestId: String, conversationId: String?) = "$requestId#1".also { held += it }
        override fun release(token: String) { held -= token }
        override fun activeCount() = held.size
    }

    private class NoopRenderer : FigureRenderer {
        override suspend fun render(figures: List<com.moge.app.data.parse.ReplyFigure>) =
            figures.map { "" }
    }

    @Test
    fun probeTerminalStateIsStable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, MogeDatabase::class.java)
            .allowMainThreadQueries().build()
        val repo = RequestRepository(db.requestDao(), Dispatchers.Unconfined)
        db.conversationDao().insertConversation(ConversationEntity(id = "c1", title = "t"))
        val created = repo.createRequest(
            conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
            attemptId = "att1", userText = "q", attachmentPaths = emptyList(), snapshotJson = "",
        )
        val model = mockk<ModelClient>()
        coEvery {
            model.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            onEvent(StreamEvent.AnswerDelta("""{"reply":"hello"}"""))
            ReplyParser.parse("""{"reply":"hello"}""", normalizeMarkdown = false)
        }
        val manager = GenerationManager(
            modelClient = model,
            requestRepository = repo,
            conversationDao = db.conversationDao(),
            credentialStore = mockk(relaxed = true),
            diagnostics = GenerationDiagnostics(),
            guard = FakeGuard(),
            monotonicClock = MonotonicClock.SYSTEM,
            finalizer = GenerationFinalizer(repo, NoopRenderer()),
            preparer = mockk(relaxed = true),
        )
        manager.start(
            GenerationManager.GenerationRequest(
                conversationId = "c1", userMessageId = "u1", answerMessageId = "a1",
                attemptId = "att1", userText = "q",
                history = listOf(ChatMessage("user", "q")),
                imageBase64s = emptyList(), webSearchEnabled = false,
                forceWebSearch = false, maxContinuations = 0, modelKey = "m", protocol = "p",
            ),
            created.requestId,
        )
        val seen = mutableListOf<String>()
        repeat(20) {
            delay(50)
            val status = repo.get(created.requestId)?.status.orEmpty()
            seen += status
            println("PROBE t=${it * 50}ms status=$status")
        }
        val distinct = seen.distinct()
        println("PROBE distinct-statuses=$distinct")
        // 一旦出现终态，就不能再变回在途状态。
        val firstTerminal = seen.indexOfFirst { it == "COMPLETED" }
        check(firstTerminal >= 0) { "从未到达 COMPLETED：$distinct" }
        val afterTerminal = seen.drop(firstTerminal)
        check(afterTerminal.all { it == "COMPLETED" }) {
            "终态被复活了：$afterTerminal"
        }
        println("PROBE RESULT=TERMINAL-STABLE")
        db.close()
    }
}
