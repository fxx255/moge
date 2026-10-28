package com.moge.app.runtime

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.db.*
import com.moge.app.data.llm.*
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.parse.ReplyParser
import com.moge.app.domain.RequestStatus
import com.moge.app.domain.SolveMode
import io.mockk.*
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GenerationNetworkLifecycleTest {
    @Test fun waitingKeepsTaskOwnershipAndOfflineTimeWhileCancellationReleasesProtection() = runBlocking {
        for (cancel in listOf(false, true)) {
            val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MogeDatabase::class.java)
                .allowMainThreadQueries().build()
            try {
                db.conversationDao().insertConversation(ConversationEntity("c", "网络测试"))
                val nanos = AtomicLong(1_000_000_000)
                val clock = MonotonicClock { nanos.get() }
                val waiting = CompletableDeferred<Unit>()
                val ready = CompletableDeferred<Unit>()
                val availability = object : NetworkAvailability {
                    override val available = false
                    override suspend fun awaitAvailable(timeoutMs: Long): Boolean {
                        waiting.complete(Unit); ready.await(); return true
                    }
                }
                val network = mockk<GenerationNetworkRecovery>()
                every { network.session(any()) } answers { GenerationNetworkSession(availability, firstArg(), clock) }
                val repository = RequestRepository(db.requestDao(), Dispatchers.Unconfined)
                val snapshot = RequestSnapshot(model = "fixture", endpointIdentity = "https://fixture.invalid")
                val preparer = mockk<GenerationPreparer>(relaxed = true)
                coEvery { preparer.captureInitialSnapshot(any(), any(), any(), any()) } returns snapshot
                coEvery { preparer.prepare(any(), any(), any()) } returns GenerationPreparer.Prepared("题目",
                    listOf(ChatMessage("user", "题目")), emptyList(), SolveMode.DETAILED, false, false,
                    snapshot.toPolicy(), snapshot.copy(prepared = true))
                var httpCalls = 0
                val raw = """{"reply":"网络恢复后完成的正文。"}"""
                val transport = OkHttpClient.Builder().addInterceptor { chain ->
                    httpCalls++
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                        .body(raw.toResponseBody("application/json".toMediaType())).build()
                }.build()
                val client = mockk<ModelClient>()
                coEvery { client.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                    val text = OkHttpCancellation.execute(transport, Request.Builder().url("https://fixture.invalid").build()).use { it.body!!.string() }
                    arg<(UsageSample?) -> Unit>(5)(UsageSample(100, 20, 50))
                    arg<suspend (StreamEvent) -> Unit>(7)(StreamEvent.AnswerDelta(text))
                    ReplyParser.parse(text)
                }
                val renderer = object : FigureRenderer { override suspend fun render(figures: List<ReplyFigure>) = figures.map { "" } }
                val guard = mockk<GenerationGuard>(relaxed = true)
                every { guard.acquire(any(), any()) } returns "lease"
                val manager = GenerationManager(client, repository, db.conversationDao(), mockk<AiCredentialStore>(relaxed = true),
                    GenerationDiagnostics(), guard, clock, GenerationFinalizer(repository, renderer), preparer,
                    GenerationFigureCompleter(renderer, client), network)
                val record = manager.submit(GenerationManager.Submission("c", "题目", emptyList()))
                withTimeout(5000) { waiting.await() }
                assertTrue(manager.active.value.isRunning)
                assertEquals(NetworkRecoveryStatus.WAITING, manager.active.value.networkStatus)
                assertEquals(0, httpCalls)
                nanos.addAndGet(35_000_000_000)
                if (cancel) manager.cancel(record.requestId, record.attemptId) else ready.complete(Unit)
                withTimeout(5000) { while (manager.active.value.isRunning) delay(10) }
                verify(exactly = 1) { guard.release("lease") }
                val stored = repository.get(record.requestId)!!
                assertEquals(if (cancel) RequestStatus.CANCELLED.name else RequestStatus.COMPLETED.name, stored.status)
                assertEquals(if (cancel) 0 else 1, httpCalls)
                if (!cancel) {
                    val answer = db.requestDao().getMessage(record.answerMessageId)!!
                    assertEquals(35_000L, answer.durationMs)
                    assertEquals(1, AnswerUsageCodec.decode(answer.usageJson)!!.requestCount)
                }
            } finally { db.close() }
        }
    }
}
