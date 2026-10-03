package com.moge.app.runtime

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.llm.GenerationDiagnostics
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.llm.ModelClient
import com.moge.app.data.llm.ModelException
import com.moge.app.data.parse.ReplyParser
import com.moge.app.data.llm.StreamEvent
import com.moge.app.data.llm.MonotonicClock
import com.moge.app.data.parse.ParsedReply
import com.moge.app.data.db.MogeDatabase
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.domain.FailureKind
import com.moge.app.data.llm.ChatMessage
import com.moge.app.domain.RequestStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * 应用级生成管理器验收（实施文档 §2、§3、§6、§7.3）。
 *
 * 用**假模型**驱动可控场景：正常完成、网络中断、纯推理无正文、取消、
 * 切会话、删除会话、重启恢复、连续重试、旧 attempt 迟到、附件缺失、缺配置。
 *
 * ## 测试真实性要求（本轮整改重点）
 *
 * 1. **桩必须走真实解析路径**：`stubStreaming` 以前把整段 JSON 当成 reply 返回
 *    （`ParsedReply(reply = payload)`），于是"正文"里带着 `{"reply":...}`
 *    外层 JSON —— 测试看着过了，真实解析器却根本没被覆盖。现在它调用
 *    [ReplyParser.parse] 得到**真正的** reply/plots/diagrams。
 * 2. **等真正收尾再断言**：等待器必须在状态进入终态**并且**管理器已释放活动标记
 *    之后才返回，否则会读到"短暂 COMPLETED 又变回 RUNNING"这种假通过。
 * 3. 不用"放宽超时/删断言"掩盖失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class GenerationManagerTest {

    private lateinit var db: MogeDatabase
    private lateinit var repository: RequestRepository
    private lateinit var modelClient: ModelClient
    private lateinit var guard: FakeGuard
    private lateinit var renderer: FakeFigureRenderer
    private lateinit var preparer: GenerationPreparer
    private lateinit var manager: GenerationManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    /** 假保活：记录持有情况，验证「不误停、不永久驻留」。 */
    private class FakeGuard : GenerationGuard {
        val held = mutableSetOf<String>()
        /** 释放令牌的瞬间，记录那一刻管理器仍认领着哪个请求（用于"owner 持有到 guard 释放"断言）。 */
        var onRelease: (() -> String?)? = null
        val ownerAtRelease = mutableListOf<String?>()

        override fun acquire(requestId: String, conversationId: String?): String {
            val token = "$requestId#1"
            held += token
            return token
        }

        override fun release(token: String) {
            ownerAtRelease += onRelease?.invoke()
            held -= token
        }

        override fun activeCount(): Int = held.size
    }

    /**
     * 假渲染器：按槽位产出稳定路径，**保留失败槽位的空串**。
     *
     * 真实 Canvas 渲染与"收尾是否保留了每个图槽位"这件事无关；
     * 用假渲染器才能稳定断言槽位顺序与数量（Robolectric 下画图本身就是噪声源）。
     */
    private class FakeFigureRenderer : FigureRenderer {
        var renderCount = 0
        override suspend fun render(figures: List<ReplyFigure>): List<String> {
            renderCount++
            return figures.mapIndexed { index, figure ->
                if (figure is ReplyFigure.Missing) "" else "fake://figure/$index.png"
            }
        }
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MogeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RequestRepository(db.requestDao(), Dispatchers.Unconfined)
        modelClient = mockk()
        guard = FakeGuard()
        renderer = FakeFigureRenderer()
        preparer = mockk(relaxed = true)
        manager = GenerationManager(
            modelClient = modelClient,
            requestRepository = repository,
            conversationDao = db.conversationDao(),
            credentialStore = mockk(relaxed = true),
            diagnostics = GenerationDiagnostics(),
            guard = guard,
            monotonicClock = MonotonicClock.SYSTEM,
            finalizer = GenerationFinalizer(repository, renderer),
            preparer = preparer,
        )
        guard.onRelease = { manager.activeRequestId() }
        runBlocking {
            db.conversationDao().insertConversation(
                ConversationEntity(id = "c1", title = "会话"),
            )
            db.conversationDao().insertConversation(
                ConversationEntity(id = "c2", title = "另一个会话"),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun request(
        requestId: String = "r1",
        attemptId: String = "att1",
        conversationId: String = "c1",
        answerMessageId: String = "a1",
        userText: String = "问题",
        maxContinuations: Int = 0,
    ) = GenerationManager.GenerationRequest(
        conversationId = conversationId,
        userMessageId = "u1",
        answerMessageId = answerMessageId,
        attemptId = attemptId,
        userText = userText,
        history = listOf(ChatMessage("user", userText)),
        imageBase64s = emptyList(),
        webSearchEnabled = false,
        forceWebSearch = false,
        maxContinuations = maxContinuations,
        modelKey = "fake-model",
        protocol = "chat_completions",
    )

    @Test fun `resume keeps question images on the question across further continuation rounds`() = runBlocking {
        val id = seed()
        val sent = mutableListOf<List<ChatMessage>>()
        val currentImages = mutableListOf<List<String>>()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            sent.add(arg(0))
            currentImages.add(arg(1))
            val payload = if (sent.size == 1) """{"reply":"继续推导。"}""" else """{"reply":"最终结论。"}"""
            arg<suspend (StreamEvent) -> Unit>(7)(StreamEvent.AnswerDelta(payload))
            ReplyParser.parse(payload, normalizeMarkdown = false).copy(truncated = sent.size == 1)
        }
        manager.start(request(maxContinuations = 1).copy(continuationText = "已经生成的推导。", imageBase64s = listOf("QUESTION_IMAGE")), id)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(id))
        assertEquals(2, sent.size)
        sent.forEach { history ->
            assertEquals(listOf("QUESTION_IMAGE"), history.first().imageBase64s)
            assertTrue(history.last().imageBase64s.isEmpty())
        }
        assertEquals("已经生成的推导。", sent[0][sent[0].lastIndex - 1].content)
        assertEquals("已经生成的推导。继续推导。", sent[1][sent[1].lastIndex - 1].content)
        assertTrue(currentImages.all { it.isEmpty() })
        assertEquals("已经生成的推导。继续推导。最终结论。", db.requestDao().getMessage("a1")!!.content)
    }

    private suspend fun seed(
        requestId: String = "r1",
        attemptId: String = "att1",
        conversationId: String = "c1",
        answerMessageId: String = "a1",
        userMessageId: String = "u1",
    ): String {
        val created = repository.createRequest(
            conversationId = conversationId,
            userMessageId = userMessageId,
            answerMessageId = answerMessageId,
            attemptId = attemptId,
            userText = "问题",
            attachmentPaths = emptyList(),
            snapshotJson = "",
        )
        return created.requestId
    }

    /**
     * 把一段 **JSON 正文**按固定片长喂进 onEvent，并返回**真正解析后**的回复。
     *
     * 关键：返回值来自 [ReplyParser.parse]，而不是把 JSON 原文当 reply——
     * 后者是之前"测试通过但功能是坏的"的直接原因。
     */
    private fun stubStreaming(
        payload: String,
        chunkSize: Int = 8,
        reasoning: String = "",
        normalizeMarkdown: Boolean = false,
        onRequest: (() -> Unit)? = null,
    ) {
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            onRequest?.invoke()
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            if (reasoning.isNotEmpty()) onEvent(StreamEvent.ReasoningDelta(reasoning))
            payload.chunked(chunkSize).forEach { piece ->
                onEvent(StreamEvent.AnswerDelta(piece))
            }
            ReplyParser.parse(payload, normalizeMarkdown = normalizeMarkdown)
        }
    }

    /** 按调用次序依次返回不同 payload 的桩（用于续写/自纠正多轮）。 */
    private fun stubSequence(
        payloads: List<String>,
        chunkSize: Int = 8,
        truncatedFlags: List<Boolean> = emptyList(),
    ) {
        val calls = AtomicInteger(0)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            val index = calls.getAndIncrement().coerceAtMost(payloads.lastIndex)
            val payload = payloads[index]
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            payload.chunked(chunkSize).forEach { piece ->
                onEvent(StreamEvent.AnswerDelta(piece))
            }
            val parsed = ReplyParser.parse(payload, normalizeMarkdown = false)
            val truncated = truncatedFlags.getOrElse(index) { false }
            if (truncated) parsed.copy(truncated = true) else parsed
        }
    }

    private fun callCount(): AtomicInteger = AtomicInteger(0)

    /**
     * 等待真正收尾。
     *
     * 返回前必须同时满足：
     * - 状态已是终态；
     * - **管理器已不再把它当成活动任务**（活动标记已释放）。
     *
     * 只等状态是不够的：收尾瞬间状态先变 COMPLETED，`finally` 里的兜底写入
     * 随后可能把它改回 RUNNING —— 只等状态就会"读到 COMPLETED 就通过"，
     * 从而掩盖那个真实缺陷。这里额外等 `activeRequestId()` 归零，
     * 并在一段稳定期后复核状态没被改写。
     */
    private suspend fun awaitTerminal(requestId: String, timeoutMs: Long = 10_000): String {
        var status = ""
        withTimeout(timeoutMs) {
            while (true) {
                val current = repository.get(requestId)?.status.orEmpty()
                val settled = manager.activeRequestId() == null || manager.activeRequestId() != requestId
                if (settled && current in TERMINAL_STATUSES) {
                    status = current
                    break
                }
                delay(10)
            }
        }
        // 稳定期复核：终态**不得**被后续写入复活。
        repeat(STABILITY_CHECKS) {
            delay(15)
            val after = repository.get(requestId)?.status.orEmpty()
            assertEquals("终态不得被复活（$requestId）", status, after)
        }
        return status
    }

    /** 等到管理器报告不再有活动任务（用于"后台独立完成"类断言）。 */
    private suspend fun awaitIdle(timeoutMs: Long = 10_000) {
        withTimeout(timeoutMs) {
            while (manager.isRunning()) delay(10)
        }
    }

    @Test
    fun `successful generation stores body in the fixed answer slot`() = runBlocking {
        val requestId = seed()
        stubStreaming("""{"reply":"你好世界"}""")
        manager.start(request(requestId = requestId), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertEquals("你好世界", db.requestDao().getMessage("a1")!!.content)
        assertEquals("完成正文必须写进固定回答位置", "你好世界", repository.get(requestId)!!.partialText)
        // 外层 JSON 绝不能进正文。
        assertFalse(db.requestDao().getMessage("a1")!!.content.contains("plan_actions"))
    }

    @Test
    fun `answer reset replaces placeholder stream before final persistence`() = runBlocking {
        val requestId = seed()
        val placeholder = """{"reply":"见上"}"""
        val complete = """{"reply":"完整的图片讲解"}"""
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            onEvent(StreamEvent.AnswerDelta(placeholder))
            onEvent(StreamEvent.AnswerReset)
            onEvent(StreamEvent.AnswerDelta(complete))
            ReplyParser.parse(complete, normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertEquals("完整的图片讲解", db.requestDao().getMessage("a1")!!.content)
    }

    @Test
    fun `answer deltas are incremental and visible before the stream ends`() = runBlocking {
        val requestId = seed()
        val visible = StringBuilder()
        val sawBeforeEnd = java.util.concurrent.atomic.AtomicBoolean(false)
        val collector = scope.launch {
            manager.events.collect { event ->
                if (event is GenerationManager.GenerationEvent.AnswerDelta) {
                    visible.append(event.text)
                    sawBeforeEnd.set(true)
                }
            }
        }
        stubStreaming("""{"reply":"这是正文内容"}""", chunkSize = 4)
        manager.start(request(requestId = requestId), requestId)
        awaitTerminal(requestId)
        delay(50)
        collector.cancel()
        assertTrue("必须在流结束前就推送可见正文", sawBeforeEnd.get())
        assertEquals("这是正文内容", visible.toString())
        assertFalse(visible.contains("plan_actions"))
        assertFalse(visible.contains("{"))
    }

    @Test
    fun `network failure marks interrupted with partial text preserved locally`() = runBlocking {
        val requestId = seed()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            onEvent(StreamEvent.AnswerDelta("""{"reply":"生成到一半"""))
            throw ModelException(ModelException.Kind.NETWORK, "网络错误：连接中断")
        }
        manager.start(request(requestId = requestId), requestId)
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(requestId))
        val stored = repository.get(requestId)!!
        assertEquals(FailureKind.NETWORK.name, stored.failureKind)
        assertEquals("部分正文必须留在请求记录里", "生成到一半", stored.partialText)
        // 部分正文**不写进会话消息**：那条消息会进跨端同步/备份，半成品不该被同步出去。
        assertEquals("", db.requestDao().getMessage("a1")!!.content)
    }

    @Test
    fun `reasoning only response never becomes the answer`() = runBlocking {
        val requestId = seed()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            onEvent(StreamEvent.ReasoningDelta("这是内部推理，包含草稿 {\"reply\":\"草稿答案\"}"))
            throw ModelException(
                ModelException.Kind.INVALID_RESPONSE,
                "模型在补充生成后仍未返回最终答案",
            )
        }
        manager.start(request(requestId = requestId), requestId)
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(requestId))
        val content = db.requestDao().getMessage("a1")!!.content
        assertFalse("推理链绝不能升格为正文", content.contains("草稿答案"))
        assertFalse(content.contains("内部推理"))
        assertEquals("", content)
    }

    @Test
    fun `reasoning deltas are emitted on a separate channel from answer`() = runBlocking {
        val requestId = seed()
        val reasoning = StringBuilder()
        val answer = StringBuilder()
        val collector = scope.launch {
            manager.events.collect { event ->
                when (event) {
                    is GenerationManager.GenerationEvent.Reasoning -> reasoning.append(event.text)
                    is GenerationManager.GenerationEvent.AnswerDelta -> answer.append(event.text)
                    else -> Unit
                }
            }
        }
        stubStreaming("""{"reply":"正文"}""", reasoning = "思考中")
        manager.start(request(requestId = requestId), requestId)
        awaitTerminal(requestId)
        delay(50)
        collector.cancel()
        assertEquals("思考中", reasoning.toString())
        assertEquals("正文", answer.toString())
        assertFalse("推理不得混进正文", answer.contains("思考中"))
    }

    @Test
    fun `cancel really cancels and does not auto retry`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        manager.start(request(requestId = requestId), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.cancel(requestId)
        assertEquals(RequestStatus.CANCELLED.name, awaitTerminal(requestId))
        assertEquals(0, repository.countActive())
        assertEquals(0, repository.recoverOrphans().size)
        assertEquals("取消后必须归还全部保护令牌", 0, guard.activeCount())
    }

    /**
     * **服务超时 ≠ 用户取消**（协调者第 1 条阻塞）。
     *
     * 两者共用「取消协程」这条技术路径（都要让阻塞的网络立刻停），但产品语义不同：
     * - 用户取消 ⇒ CANCELLED 且**不可重试**；
     * - 服务超时 ⇒ INTERRUPTED 且**可重试**，否则界面连重试入口都不给，
     *   用户只能重新打字。
     */
    @Test
    fun `service timeout marks interrupted and retryable not cancelled`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            // 超时前已经生成了一部分正文：必须保留。
            onEvent(StreamEvent.AnswerDelta("""{"reply":"超时前的正文"""))
            started.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        manager.start(request(requestId = requestId), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))

        manager.onForegroundServiceTimeout()

        assertEquals(
            "服务超时必须落 INTERRUPTED 而不是 CANCELLED",
            RequestStatus.INTERRUPTED.name,
            awaitTerminal(requestId),
        )
        val stored = repository.get(requestId)!!
        assertEquals(FailureKind.SERVER.name, stored.failureKind)
        assertEquals("超时前的部分正文必须保留", "超时前的正文", stored.partialText)
        assertTrue(
            "服务超时必须可重试",
            FailureKind.fromName(stored.failureKind)!!.isRetryable,
        )
        // 可以真的重试（不是只给个按钮）。
        assertNotNull(
            "服务超时后必须能发起重试",
            repository.beginRetry(requestId, "att-timeout", null, null),
        )
        assertEquals("超时后不得驻留保护令牌", 0, guard.activeCount())
    }

    /** 服务超时要发出 Failed 事件（界面据此清 busy 并显示重试入口）。 */
    @Test
    fun `service timeout emits a failed event with retryable kind`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        val failed = java.util.concurrent.CompletableFuture<GenerationManager.GenerationEvent.Failed>()
        val collector = scope.launch {
            manager.events.collect { event ->
                if (event is GenerationManager.GenerationEvent.Failed) failed.complete(event)
            }
        }
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        manager.start(request(requestId = requestId), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.onForegroundServiceTimeout()
        awaitTerminal(requestId)
        val event = failed.get(3, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(FailureKind.SERVER, event.kind)
        assertTrue("超时事件必须标记可重试", event.retryable)
        collector.cancel()
    }

    /** 取消**错 id** 不得影响另一个正在跑的请求。 */
    @Test
    fun `cancelling a different request id does not disturb the running one`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(300)
            ReplyParser.parse("""{"reply":"完成了"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        // 取消一个**不属于**当前活动请求的 id：必须完全没有副作用。
        manager.cancel("some-other-request")
        assertTrue("取消错 id 不能把正在跑的任务弄停", manager.isRunning())
        assertEquals("取消错 id 不能改别人状态", requestId, manager.activeRequestId())
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertEquals("完成了", db.requestDao().getMessage("a1")!!.content)
    }

    /** 终态不可复活：收尾之后任何"兜底"写入都不能把它改回在途状态。 */
    @Test
    fun `terminal state is never resurrected by a late partial write`() = runBlocking {
        val requestId = seed()
        stubStreaming("""{"reply":"最终正文"}""")
        manager.start(request(requestId = requestId), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        // 模拟迟到的兜底写入（旧实现里 finally 就会这么干）。
        val lateWrite = repository.savePartial(requestId, "att1", "更晚的片段")
        assertFalse("终态必须拒绝迟到的在途写入", lateWrite)
        assertEquals(
            "终态不得被复活",
            RequestStatus.COMPLETED.name,
            repository.get(requestId)!!.status,
        )
        assertEquals("最终正文", db.requestDao().getMessage("a1")!!.content)
    }

    /** 旧 attempt 迟到：不能覆盖新一轮结果，也不能把状态改回在途。 */
    @Test
    fun `late write from an old attempt is rejected`() = runBlocking {
        val requestId = seed()
        stubStreaming("""{"reply":"第二轮完整答案"}""")
        // 先制造一次中断，再换 attempt 重试成功。
        repository.interrupt(requestId, "att1", "半截", FailureKind.NETWORK, "网络错误")
        val retried = repository.beginRetry(requestId, "att2", null, null)
        assertNotNull(retried)
        manager.start(request(requestId = requestId, attemptId = "att2"), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        // 旧 attempt 的迟到回调：
        assertFalse(repository.savePartial(requestId, "att1", "旧 attempt 的正文"))
        assertEquals("第二轮完整答案", db.requestDao().getMessage("a1")!!.content)
        assertEquals(RequestStatus.COMPLETED.name, repository.get(requestId)!!.status)
    }

    @Test
    fun `second concurrent generation is rejected so answers never cross conversations`() = runBlocking {
        val requestId = seed()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            delay(2_000)
            ReplyParser.parse("""{"reply":"x"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId), requestId)
        assertTrue(manager.isRunning())
        val second = runCatching { manager.start(request(requestId = "r2", attemptId = "att2"), "r2") }
        assertTrue(
            "全应用最多一个活动生成",
            second.exceptionOrNull() is GenerationManager.AlreadyRunningException,
        )
        manager.cancel(requestId)
        awaitTerminal(requestId)
        Unit
    }

    @Test
    fun `retry with new attempt replaces content and old attempt cannot overwrite`() = runBlocking {
        val requestId = seed()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            onEvent(StreamEvent.AnswerDelta("""{"reply":"第一轮半截"""))
            throw ModelException(ModelException.Kind.NETWORK, "网络错误")
        }
        manager.start(request(requestId = requestId), requestId)
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(requestId))

        val retried = repository.beginRetry(requestId, "att2", null, null)!!
        assertEquals("u1", retried.userMessageId)
        assertEquals("a1", retried.answerMessageId)
        stubStreaming("""{"reply":"第二轮完整答案"}""")
        manager.start(request(requestId = requestId, attemptId = "att2"), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertEquals("第二轮完整答案", db.requestDao().getMessage("a1")!!.content)
        val messages = db.conversationDao().getMessages("c1")
        assertEquals(1, messages.count { it.role == "user" })
        assertEquals(1, messages.count { it.role == "assistant" })
    }

    /** 并发/双击重试：只有一个 CAS 能成功，另一个必须被拒。 */
    @Test
    fun `concurrent retry only one attempt wins`() = runBlocking {
        val requestId = seed()
        repository.interrupt(requestId, "att1", "", FailureKind.NETWORK, "网络错误")
        val first = repository.beginRetry(requestId, "att-a", null, null)
        val second = repository.beginRetry(requestId, "att-b", null, null)
        assertNotNull("第一次重试应当抢到", first)
        assertNull("第二次重试必须被拒（状态已不是 INTERRUPTED/attempt 已变）", second)
        assertEquals("att-a", repository.get(requestId)!!.attemptId)
    }

    /** 已完成的请求不能被"重试"复活。 */
    @Test
    fun `completed request cannot be retried again`() = runBlocking {
        val requestId = seed()
        stubStreaming("""{"reply":"完成啦"}""")
        manager.start(request(requestId = requestId), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertNull("已完成的请求不得被重试复活", repository.beginRetry(requestId, "att2", null, null))
        assertEquals(RequestStatus.COMPLETED.name, repository.get(requestId)!!.status)
        assertEquals("完成啦", db.requestDao().getMessage("a1")!!.content)
    }

    @Test
    fun `guard token is released on every exit path`() = runBlocking {
        val requestId = seed()
        stubStreaming("""{"reply":"正文"}""")
        manager.start(request(requestId = requestId), requestId)
        awaitTerminal(requestId)
        assertEquals("完成后不得驻留保护", 0, guard.activeCount())
        assertFalse(manager.isRunning())
    }

    @Test
    fun `guard token is released when generation fails`() = runBlocking {
        val requestId = seed()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            throw ModelException(ModelException.Kind.SERVER, "HTTP 500")
        }
        manager.start(request(requestId = requestId), requestId)
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(requestId))
        assertEquals("失败后不得驻留保护", 0, guard.activeCount())
    }

    @Test
    fun `config invalid failure can be resent after changing provider`() = runBlocking {
        val requestId = seed()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            throw ModelException(ModelException.Kind.UNAUTHORIZED, "API 密钥被拒绝（HTTP 401）")
        }
        manager.start(request(requestId = requestId), requestId)
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(requestId))
        val stored = repository.get(requestId)!!
        assertEquals(FailureKind.CONFIG_INVALID.name, stored.failureKind)
        assertTrue(
            "用户可换服务商后在原问题旁重新发送",
            FailureKind.CONFIG_INVALID.isRetryable,
        )
    }

    /**
     * 续写：**必须保留全部合并正文与按轮序累积的图槽位**（含失败槽位）。
     *
     * 这是本次整改的核心回归：旧实现把 `Completed.result=lastResult` 交给界面，
     * 界面再用 `lastResult.reply` 覆盖完整正文、并只渲染最后一轮的图 ——
     * 于是长回答被整段抹掉、图指向错位。
     */
    @Test
    fun `continuation keeps merged body and accumulates figures across rounds`() = runBlocking {
        val requestId = seed()
        // 第 1 轮：正文 + 1 张 plot + 1 个失败 plot 槽位 + 1 张 diagram，且被截断。
        // 第 2 轮：只续写文字（无图），锚点从 1 重新编号 → 必须平移。
        stubSequence(
            payloads = listOf(
                """{"reply":"第一段：双纽线 $$\\rho^2=\\cos 2\\theta$$ 的面积为\n\n[[FIGURE:1]]\n\n未完，","plots":[{"title":"P1","series":[{"expr":"x"}]},{}],"diagrams":[{"title":"D1","nodes":[{"id":"a","label":"A"}],"edges":[]}]}""",
                """{"reply":"接着说：\n\n[[FIGURE:1]]\n\n全部写完。","plots":[{"title":"P2","series":[{"expr":"x^2"}]}]}""",
            ),
            truncatedFlags = listOf(true, false),
        )
        manager.start(request(requestId = requestId, maxContinuations = 2), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        val content = db.requestDao().getMessage("a1")!!.content
        // ① 完整正文：两轮内容都要在，不能被最后一轮覆盖。
        assertTrue("必须保留第一轮正文", content.contains("第一段：双纽线"))
        assertTrue("必须保留第二轮正文", content.contains("接着说"))
        assertTrue("必须保留合并后的公式", content.contains("\\rho^2=\\cos 2\\theta"))
        // ② 图槽位跨轮累积，且**失败槽位保留**：第 1 轮 3 个（plot/失败/diagram）+ 第 2 轮 1 个 = 4。
        val imagePaths = RequestRepository.decodePathList(db.requestDao().getMessage("a1")!!.imagePaths)
        assertEquals("图槽位必须跨轮累积且保留失败槽位", 4, imagePaths.size)
        assertEquals("失败槽位必须留空而不是被挤掉", "", imagePaths[1])
        // ③ 锚点平移：第 2 轮的 [[FIGURE:1]] 应指向合并列表里的第 4 张。
        assertTrue("续写轮的锚点必须平移", content.contains("[[FIGURE:4]]"))
    }

    @Test
    fun `summary title survives continuation and stays out of the answer bubble`() = runBlocking {
        val requestId = seed()
        stubSequence(
            payloads = listOf(
                """{"conversation_title":"二重积分的对称性判断","reply":"先通过交换变量分析积分区域的对称性，再说明被积函数变化时能够得到的结论。"}""",
                """{"reply":"然后使用单位圆盘构造反例，原命题不成立。","conversation_title":"反例计算"}""",
            ),
            truncatedFlags = listOf(true, false),
        )
        manager.start(request(requestId = requestId, maxContinuations = 2), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertEquals("二重积分的对称性判断", db.conversationDao().getConversation("c1")!!.title)
        assertEquals("另一个会话", db.conversationDao().getConversation("c2")!!.title)
        val answer = db.requestDao().getMessage("a1")!!.content
        assertTrue(answer.contains("单位圆盘"))
        assertFalse(answer.contains("conversation_title"))
        assertFalse(answer.contains("二重积分的对称性判断"))
    }

    /** 续写中途失败：**早先的正文与图不能丢**。 */
    @Test
    fun `failed continuation keeps earlier body and figures`() = runBlocking {
        val requestId = seed()
        val dollar = '$'
        val calls = callCount()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            if (calls.getAndIncrement() == 0) {
                val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
                val payload =
                    """{"reply":"第一段已完成的结论：${dollar}a^2+b^2=c^2$dollar。","plots":[{"title":"P1","series":[{"expr":"x"}]}]}"""
                onEvent(StreamEvent.AnswerDelta(payload))
                ReplyParser.parse(payload, normalizeMarkdown = false).copy(truncated = true)
            } else {
                throw ModelException(ModelException.Kind.NETWORK, "续写时断网")
            }
        }
        manager.start(request(requestId = requestId, maxContinuations = 2), requestId)
        // 续写失败 → 终态是中断，但**第一轮正文与图必须留住**。
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(requestId))
        val partial = repository.get(requestId)!!.partialText
        assertTrue("续写失败必须保留第一轮正文", partial.contains("第一段已完成的结论"))
    }

    @Test
    fun `startup recovery flips leftover request to interrupted without calling the model`() = runBlocking {
        val requestId = seed()
        repository.markRunning(requestId, "att1")
        val calls = callCount()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            calls.incrementAndGet()
            ReplyParser.parse("""{"reply":"x"}""", normalizeMarkdown = false)
        }
        manager.recoverOnStartup()
        assertEquals(RequestStatus.INTERRUPTED.name, repository.get(requestId)!!.status)
        assertEquals("重启后绝不自动调用模型", 0, calls.get())
        assertFalse(manager.isRunning())
    }

    /**
     * 重复初始化**不得打断正在跑的任务**。
     *
     * 旧实现里 ViewModel 每次 init 都调用 `recoverOnStartup`，于是反复进出
     * 助手页会把当前正在生成的任务标成中断。
     *
     * 注意区分：**应用启动时**表里的 PREPARING/RUNNING 确实是遗留孤儿（该被中断），
     * 所以这里先让任务真正跑起来，再模拟"用户又进了一次助手页"重复调用。
     */
    @Test
    fun `repeated startup recovery does not interrupt a running task`() = runBlocking {
        // 应用启动扫描先跑（此刻表里没有在途记录）——真实顺序就是先启动再提问。
        manager.recoverOnStartup()
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(300)
            ReplyParser.parse("""{"reply":"跑完了"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(
            "任务确实在跑",
            RequestStatus.RUNNING.name,
            repository.get(requestId)!!.status,
        )
        // 模拟"用户又进了一次助手页"：重复调用恢复扫描（含绕过实例闩的内核，
        // 否则"闩恰好挡住第二次调用"会让这个测试假通过）。
        repeat(3) { manager.recoverOnStartup() }
        repeat(3) { manager.scanOrphansExcludingOwned() }
        assertEquals(
            "重复初始化不能把正在跑的任务标成中断",
            RequestStatus.RUNNING.name,
            repository.get(requestId)!!.status,
        )
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertEquals("跑完了", db.requestDao().getMessage("a1")!!.content)
    }

    /**
     * 启动扫描确实要把**上个进程遗留**的在途请求转成中断，
     * 但不能碰本进程当前持有的那个请求。
     */
    @Test
    fun `startup recovery leaves the request owned by this process alone`() = runBlocking {
        // 遗留孤儿（上个进程留下的）。
        val orphanId = seed(requestId = "orphan", attemptId = "att-orphan", answerMessageId = "a-orphan", userMessageId = "u-orphan")
        repository.markRunning(orphanId, "att-orphan")

        // 本进程正在跑的请求。
        val liveId = seed(requestId = "live", attemptId = "att-live", answerMessageId = "a-live", userMessageId = "u-live")
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(300)
            ReplyParser.parse("""{"reply":"活着"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = liveId, attemptId = "att-live", answerMessageId = "a-live"), liveId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))

        // 再扫一次：只应中断孤儿，不能动本进程的活动请求。
        manager.scanOrphansExcludingOwned()
        assertEquals(
            "遗留孤儿必须被转成中断",
            RequestStatus.INTERRUPTED.name,
            repository.get(orphanId)!!.status,
        )
        assertEquals(
            "本进程正在跑的请求不能被扫描打断",
            RequestStatus.RUNNING.name,
            repository.get(liveId)!!.status,
        )
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(liveId))
    }

    /**
     * **页面销毁后仍要完成收尾**（图 + 信封 + 持久化）。
     *
     * 这里模拟"没有订阅者"：不收集任何事件，只等管理器自己跑完。
     * 旧实现把绘图/信封/保存放在 ViewModel.finishTurn 里，
     * 没有订阅者就什么都没保存。
     */
    @Test
    fun `generation finishes figures and envelope with no subscriber attached`() = runBlocking {
        val requestId = seed()
        stubSequence(
            payloads = listOf(
                """{"reply":"见下图。\n\n[[FIGURE:1]]","plots":[{"title":"P1","series":[{"expr":"x"}]}]}""",
            ),
        )
        // 刻意不订阅 manager.events / manager.state
        manager.start(request(requestId = requestId), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        val message = db.requestDao().getMessage("a1")!!
        // 图必须已经渲染并落库（无订阅者也一样）。
        val paths = RequestRepository.decodePathList(message.imagePaths)
        assertEquals("无订阅者也要完成绘图", 1, paths.size)
        assertEquals("fake://figure/0.png", paths.single())
        assertEquals("正文必须落库", "见下图。\n\n[[FIGURE:1]]", message.content)
        assertTrue("渲染器确实被调用过", renderer.renderCount > 0)
    }

    /** 重新订阅（新页面）能拿到完整状态：正文 + 活动标记。 */
    @Test
    fun `resubscribing receives the replayed in-flight state`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            onEvent(StreamEvent.AnswerDelta("""{"reply":"正在生成中的正文"}"""))
            started.countDown()
            release.await()
            ReplyParser.parse(
                """{"reply":"正在生成中的正文"}""",
                normalizeMarkdown = false,
            )
        }
        manager.start(request(requestId = requestId), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        delay(50)
        // 模拟"新页面订阅"：StateFlow 是可重放的，订阅即刻拿到当前正文。
        val observed = manager.state.value
        assertEquals(requestId, observed.requestId)
        assertTrue("重新订阅必须能拿到正在进行中的正文", observed.partialText.contains("正在生成中的正文"))
        assertTrue("重新订阅必须知道这一轮在跑", observed.isRunning)
        release.countDown()
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
    }

    /** 事务性：创建请求时**用户消息 + 回答占位 + 请求记录**一起落盘。 */
    @Test
    fun `creating a request persists user message answer slot and request together`() = runBlocking {
        val requestId = seed()
        val messages = db.conversationDao().getMessages("c1")
        assertEquals("用户消息必须在同一事务里落盘", 1, messages.count { it.id == "u1" })
        assertEquals("回答占位必须落盘", 1, messages.count { it.id == "a1" })
        assertEquals("回答占位内容为空，不进同步/备份的半成品", "", messages.first { it.id == "a1" }.content)
        assertNotNull(repository.get(requestId))
    }

    /** 回答位置归属校验：不能把正文写进**别人的**回答位置。 */
    @Test
    fun `completion refuses a foreign answer message id`() = runBlocking {
        val requestId = seed()
        // 另一个请求的回答位置。
        db.requestDao().insertMessage(
            com.moge.app.data.db.MessageEntity(
                id = "someone-else",
                conversationId = "c1",
                role = "assistant",
                content = "",
            ),
        )
        val accepted = repository.complete(requestId, "att1", "someone-else", "不该写进去")
        assertFalse("不得把正文写进别的回答位置", accepted)
        assertEquals("", db.requestDao().getMessage("someone-else")!!.content)
        assertEquals("归属不符时状态也不该变", RequestStatus.PREPARING.name, repository.get(requestId)!!.status)
    }

    /** 收尾必须校验 attempt：过期 attempt 不得写终态。 */
    @Test
    fun `completion refuses an expired attempt`() = runBlocking {
        val requestId = seed()
        val accepted = repository.complete(requestId, "stale-attempt", "a1", "过期写入")
        assertFalse("过期 attempt 不得收尾", accepted)
        assertEquals(RequestStatus.PREPARING.name, repository.get(requestId)!!.status)
        assertEquals("", db.requestDao().getMessage("a1")!!.content)
    }

    @Test
    fun `deleting a conversation cancels its generation and cleans records`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(30_000)
            ReplyParser.parse("""{"reply":"x"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.cancel(requestId)
        awaitTerminal(requestId)
        repository.deleteForConversation("c1")
        assertEquals(0, repository.forConversation("c1").size)
        assertEquals(0, guard.activeCount())
    }

    @Test
    fun `timings are recorded for a completed generation`() = runBlocking {
        val requestId = seed()
        stubStreaming("""{"reply":"正文内容"}""", reasoning = "想一想")
        val completed = java.util.concurrent.CompletableFuture<com.moge.app.data.llm.GenerationTimings>()
        val collector = scope.launch {
            manager.events.collect { event ->
                if (event is GenerationManager.GenerationEvent.Completed) {
                    completed.complete(event.timings)
                }
            }
        }
        manager.start(request(requestId = requestId), requestId)
        awaitTerminal(requestId)
        val timings = completed.get(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("必须记录首个可见正文时间", timings.firstVisibleTextMs != null)
        assertTrue("必须记录完成时间", timings.completedMs != null)
        collector.cancel()
    }

    /** 完成事件必须带上**完整合并正文**与按轮累积的图片，供界面直接展示。 */
    @Test
    fun `completed event carries merged text and accumulated images`() = runBlocking {
        val requestId = seed()
        val captured = java.util.concurrent.CompletableFuture<GenerationManager.GenerationEvent.Completed>()
        val collector = scope.launch {
            manager.events.collect { event ->
                if (event is GenerationManager.GenerationEvent.Completed) captured.complete(event)
            }
        }
        stubSequence(
            payloads = listOf(
                """{"reply":"上半段，","plots":[{"title":"P1","series":[{"expr":"x"}]}]}""",
                """{"reply":"下半段。","plots":[]}""",
            ),
            truncatedFlags = listOf(true, false),
        )
        manager.start(request(requestId = requestId, maxContinuations = 2), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        val event = captured.get(2, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue("完成事件必须带完整合并正文", event.mergedText.contains("上半段"))
        assertTrue("完成事件必须带完整合并正文", event.mergedText.contains("下半段"))
        assertEquals("完成事件必须带已渲染图片", 1, event.answerImagePaths.size)
        // 界面不该再依赖 result.reply 当完整正文——它与 mergedText 可能不同。
        assertNotEquals(
            "result.reply 只是最后一轮，不能当完整正文用",
            event.result.reply,
            event.mergedText,
        )
        collector.cancel()
    }

    /** 被"全应用单飞"拒绝的提交必须收成终态，不能永远停在 PREPARING。 */
    @Test
    fun `abandoned submission becomes retryable instead of staying preparing`() = runBlocking {
        val requestId = seed()
        manager.abandonSubmission(requestId, "另一个会话正在生成回答")
        val stored = repository.get(requestId)!!
        assertEquals(
            "被拒绝的提交必须收成终态，不能停在 PREPARING",
            RequestStatus.INTERRUPTED.name,
            stored.status,
        )
        assertEquals(FailureKind.NETWORK.name, stored.failureKind)
        assertEquals("另一个会话正在生成回答", stored.failureMessage)
        // 不该再被启动扫描当成孤儿（已经是终态）。
        assertEquals(0, repository.recoverOrphans().size)
        // 可以正常重试。
        assertNotNull(repository.beginRetry(requestId, "att2", null, null))
    }

    // ── 服务超时 / 取消的语义（协调者第 1 条） ──

    /**
     * **准备阶段**收到服务超时也必须落 INTERRUPTED 且可重试。
     *
     * 早先准备阶段取消被硬编码成 CANCELLED（不可重试）——
     * 系统打断的请求连重试入口都没有。
     */
    @Test
    fun `service timeout during preparation marks interrupted and retryable`() = runBlocking {
        val requestId = seed()
        // 准备阶段挂住不返回：模拟转写/选上下文还在跑。
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery { preparer.prepare(any(), any(), any()) } coAnswers {
            started.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        val submission = GenerationManager.Submission(
            conversationId = "c1",
            userText = "问题",
            attachmentPaths = emptyList(),
        )
        val created = manager.submit(submission)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))

        manager.onForegroundServiceTimeout()

        assertEquals(
            "准备阶段超时必须落 INTERRUPTED",
            RequestStatus.INTERRUPTED.name,
            awaitTerminal(created.requestId),
        )
        assertTrue(
            "必须可重试",
            FailureKind.fromName(repository.get(created.requestId)!!.failureKind)!!.isRetryable,
        )
        // 事件不能因为协程已取消而丢失：Failed 事件必须发出（收尾在 NonCancellable 里）。
        assertEquals(0, repository.countActive())
    }

    /** **运行阶段**超时保持上一轮已验证的行为（回归保护）。 */
    @Test
    fun `service timeout during running marks interrupted not cancelled`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        manager.start(request(requestId = requestId), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.onForegroundServiceTimeout()
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(requestId))
        assertTrue(FailureKind.fromName(repository.get(requestId)!!.failureKind)!!.isRetryable)
    }

    /**
     * **上一轮的超时不能污染下一轮的主动取消**。
     *
     * 早先结束原因是一个全局变量：一次服务超时留下 SERVICE_TIMEOUT，
     * 之后用户主动取消下一轮会被误判成超时（落 INTERRUPTED、给重试入口）。
     * 现在原因按 request+attempt 绑定。
     */
    @Test
    fun `stale service timeout does not contaminate the next user cancel`() = runBlocking {
        // ── 第一轮：运行中服务超时（留下"超时"痕迹）──
        val firstId = seed(requestId = "r1", attemptId = "att1", answerMessageId = "a1", userMessageId = "u1")
        val started1 = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started1.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        manager.start(request(requestId = firstId, attemptId = "att1", answerMessageId = "a1"), firstId)
        assertTrue(started1.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.onForegroundServiceTimeout()
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(firstId))

        // ── 第二轮：用户**主动**取消 ⇒ 必须 CANCELLED（不是上一轮的超时）──
        val secondId = seed(requestId = "r2", attemptId = "att2", answerMessageId = "a2", userMessageId = "u2")
        val started2 = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started2.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        manager.start(request(requestId = secondId, attemptId = "att2", answerMessageId = "a2"), secondId)
        assertTrue(started2.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.cancel(secondId)
        assertEquals(
            "下一轮的用户取消不得被上一轮的超时污染",
            RequestStatus.CANCELLED.name,
            awaitTerminal(secondId),
        )
        assertFalse(
            "用户取消不可重试",
            FailureKind.fromName(repository.get(secondId)!!.failureKind)!!.isRetryable,
        )
    }

    /**
     * **背靠背中断→重试**：`beginRetry` 只允许 INTERRUPTED，所以这里的中断源是
     * **服务超时**（系统打断，用户没做错什么）。
     *
     * 断言重点：旧协程的收尾不得清掉新轮的活动状态，也不得双发终态事件。
     */
    @Test
    fun `back to back interrupt then retry keeps the new turn intact`() = runBlocking {
        val requestId = seed()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        // 收集器必须在**第一轮之前**就订阅：否则旧轮的终态事件根本没被观察，
        // "只发一次"就成了"一次都没发"的假通过。
        val collector = scope.launch {
            manager.events.collect { event ->
                when (event) {
                    is GenerationManager.GenerationEvent.Started -> events += "Started"
                    is GenerationManager.GenerationEvent.Completed -> events += "Completed"
                    is GenerationManager.GenerationEvent.Failed -> events += "Failed"
                    else -> Unit
                }
            }
        }
        val firstStarted = java.util.concurrent.CountDownLatch(1)
        var failFirst = true
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            if (failFirst) {
                firstStarted.countDown()
                delay(2_000)
                throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
            }
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            val payload = """{"reply":"重试成功"}"""
            onEvent(StreamEvent.AnswerDelta(payload))
            ReplyParser.parse(payload, normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId, attemptId = "att1"), requestId)
        assertTrue(firstStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.onForegroundServiceTimeout()
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(requestId))

        // 立刻重试（旧协程的 finally 可能还没跑完）。
        failFirst = false
        assertNotNull(
            "服务超时造成的中断必须能重试",
            repository.beginRetry(requestId, "att2", null, null),
        )
        manager.start(request(requestId = requestId, attemptId = "att2"), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertEquals("重试成功", db.requestDao().getMessage("a1")!!.content)
        delay(100)
        collector.cancel()
        assertEquals(
            "重试后只能有一个 Completed、一个 Failed（旧轮超时中断），不能双发",
            1,
            events.count { it == "Completed" },
        )
        assertEquals(
            "旧轮的中断事件不得在重试成功后再出现一次",
            1,
            events.count { it == "Failed" },
        )
        assertEquals("重试成功后不得驻留保护", 0, guard.activeCount())
    }

    /**
     * 用户**主动取消**的一轮：**不提供重试**。
     *
     * DAO 的 `beginRetry` 只允许从 INTERRUPTED 起步，所以 CANCELLED 必须返回 null ——
     * 这是有意的语义（用户明确不想要了），不是缺陷。此前的断言在这里写错了。
     */
    @Test
    fun `user cancelled turn cannot begin a retry`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(2_000)
            ReplyParser.parse("""{"reply":"不该完成"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId, attemptId = "att1"), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.cancel(requestId)
        assertEquals(RequestStatus.CANCELLED.name, awaitTerminal(requestId))
        assertFalse(
            "用户取消不可重试",
            FailureKind.fromName(repository.get(requestId)!!.failureKind)!!.isRetryable,
        )
        assertNull("主动取消后不得换新的 attempt 重跑", repository.beginRetry(requestId, "att2", null, null))
        // 状态不得被任何迟到写复活。
        repeat(STABILITY_CHECKS) {
            delay(15)
            assertEquals(RequestStatus.CANCELLED.name, repository.get(requestId)!!.status)
        }
    }

    /**
     * **提交后立刻取消**：body 必须真的跑起来并被取消 ⇒ 记录一定会进终态、
     * 一定会发终态事件、保护一定归还。绝不能停在 PREPARING。
     */
    @Test
    fun `immediate cancel after start still settles the request`() = runBlocking {
        val submission = GenerationManager.Submission(
            conversationId = "c1",
            userText = "问题",
            attachmentPaths = emptyList(),
        )
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val collector = scope.launch {
            manager.events.collect { event ->
                if (event is GenerationManager.GenerationEvent.Failed) events += event.kind.name
            }
        }
        // 准备阶段挂住：确保取消落在"已经开始但还没结束"的窗口里。
        coEvery { preparer.prepare(any(), any(), any()) } coAnswers {
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        val created = manager.submit(submission)
        // 不等任何推进，立刻取消。
        manager.cancel(created.requestId)
        assertEquals(
            "立即取消也必须有终态（不能停在 PREPARING）",
            RequestStatus.CANCELLED.name,
            awaitTerminal(created.requestId),
        )
        assertEquals("不得驻留保护", 0, guard.activeCount())
        delay(50)
        collector.cancel()
        assertTrue("必须广播终态事件", events.contains(FailureKind.CANCELLED.name))
    }

    /**
     * **旧 attempt 的迟到取消**真的被调用：不得影响已经换了 attempt 的新一轮。
     */
    @Test
    fun `late cancel targeting an old attempt does not stop the new attempt`() = runBlocking {
        val requestId = seed()
        var attempt = 1
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            if (attempt == 1) {
                throw ModelException(ModelException.Kind.NETWORK, "网络错误")
            }
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            onEvent(StreamEvent.AnswerDelta("""{"reply":"新轮完成"}"""))
            ReplyParser.parse("""{"reply":"新轮完成"}""", normalizeMarkdown = false)
        }
        // 第一轮：网络失败 → INTERRUPTED。
        manager.start(request(requestId = requestId, attemptId = "att1"), requestId)
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(requestId))

        // 换新 attempt 重试，并在它跑的过程中**真的**调用旧 attempt 的迟到取消。
        assertNotNull(repository.beginRetry(requestId, "att2", null, null))
        attempt = 2
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            onEvent(StreamEvent.AnswerDelta("""{"reply":"新轮完成"}"""))
            ReplyParser.parse("""{"reply":"新轮完成"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId, attemptId = "att2"), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        // 迟到取消：**真的调用**，并显式带着旧 attempt（"旧 attempt 的回调"语义）。
        manager.cancel(requestId, attemptId = "att1")
        assertTrue("当前这一轮不得被旧 attempt 的迟到取消掐掉", manager.isRunning())
        release.countDown()
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertEquals("新轮完成", db.requestDao().getMessage("a1")!!.content)
        assertEquals(0, guard.activeCount())
    }

    /**
     * **准备阶段的服务超时**必须落 INTERRUPTED 且可重试；超时后紧接着
     * 主动取消下一轮时，不得被上一轮的超时原因污染。
     */
    @Test
    fun `preparation timeout then next user cancel keeps different outcomes`() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery { preparer.prepare(any(), any(), any()) } coAnswers {
            started.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        val first = manager.submit(
            GenerationManager.Submission(
                conversationId = "c1",
                userText = "问题",
                attachmentPaths = emptyList(),
            )
        )
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.onForegroundServiceTimeout()
        assertEquals(
            "准备阶段超时必须落 INTERRUPTED",
            RequestStatus.INTERRUPTED.name,
            awaitTerminal(first.requestId),
        )
        assertTrue(
            "超时必须可重试",
            FailureKind.fromName(repository.get(first.requestId)!!.failureKind)!!.isRetryable,
        )

        // 下一轮：用户主动取消 ⇒ CANCELLED（不得继承上一轮的超时原因）。
        val running = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            running.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        val secondId = seed(requestId = "r9", attemptId = "att9", answerMessageId = "a9", userMessageId = "u9")
        manager.start(request(requestId = secondId, attemptId = "att9", answerMessageId = "a9"), secondId)
        assertTrue(running.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.cancel(secondId)
        assertEquals(RequestStatus.CANCELLED.name, awaitTerminal(secondId))
    }

    /** body 从未进入时也不能把记录留在 PREPARING（完成回调兜底）。 */
    @Test
    fun `never entered body still leaves no unfinished request`() = runBlocking {
        val requestId = seed()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            delay(2_000)
            ReplyParser.parse("""{"reply":"晚了"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId), requestId)
        manager.cancel(requestId)
        awaitTerminal(requestId)
        assertEquals("不得留下在途记录", 0, repository.countActive())
        assertEquals(0, guard.activeCount())
    }

    /** 取消**错 id** 时不得写入结束原因（校验先于写入），也不影响正在跑的任务。 */
    @Test
    fun `cancel with mismatched id records no stop reason`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(300)
            ReplyParser.parse("""{"reply":"完成"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        // 取消一个不存在的请求：必须完全无副作用。
        manager.cancel("not-the-active-one")
        assertTrue(manager.isRunning())
        assertEquals(requestId, manager.activeRequestId())
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
    }

    /** 旧 attempt 的**迟到取消**不得影响已换新 attempt 的那一轮。 */
    @Test
    fun `late cancel from an old attempt does not stop the new attempt`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(300)
            ReplyParser.parse("""{"reply":"新轮完成"}""", normalizeMarkdown = false)
        }
        // 先制造中断，再换 attempt 重试。
        repository.interrupt(requestId, "att1", "", FailureKind.NETWORK, "网络错误")
        repository.beginRetry(requestId, "att2", null, null)
        manager.start(request(requestId = requestId, attemptId = "att2"), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        // 这里用"已不匹配的 id+attempt"显式模拟旧 attempt 的迟到取消。
        manager.cancel(requestId, attemptId = "att1")
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertEquals("新轮完成", db.requestDao().getMessage("a1")!!.content)
    }

    /**
     * **普通取消仍必须生效**：正在重试的当前这一轮被 `cancel(requestId)` 取消时，
     * 必须真的停下来并落 CANCELLED（不能为迁就旧 attempt 的测试而弱化）。
     */
    @Test
    fun `current attempt cancellation still stops the running retry`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        repository.interrupt(requestId, "att1", "", FailureKind.NETWORK, "网络错误")
        assertNotNull(repository.beginRetry(requestId, "att2", null, null))
        manager.start(request(requestId = requestId, attemptId = "att2"), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        // 不带 attempt：这是当前用户针对当前轮的取消，必须生效。
        manager.cancel(requestId)
        assertEquals(RequestStatus.CANCELLED.name, awaitTerminal(requestId))
        assertNull("主动取消后不得再换 attempt 重跑", repository.beginRetry(requestId, "att3", null, null))
        assertEquals(0, guard.activeCount())
    }

    /** 同一 (requestId, attemptId) 重复 `start`：必须被拒，模型只被调用一次。 */
    @Test
    fun `duplicate start for the same identity does not launch a second model call`() = runBlocking {
        val requestId = seed()
        val modelCalls = AtomicInteger(0)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            modelCalls.incrementAndGet()
            delay(400)
            ReplyParser.parse("""{"reply":"正文"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId, attemptId = "att1"), requestId)
        assertEquals(
            "同名 request+attempt 的重复 start 必须被拒",
            true,
            runCatching {
                manager.start(request(requestId = requestId, attemptId = "att1"), requestId)
            }.exceptionOrNull() is GenerationManager.AlreadyRunningException,
        )
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        delay(100)
        assertEquals("只允许一次模型调用", 1, modelCalls.get())
    }

    /**
     * **捕获到的旧 attempt 超时回调**（延迟触发）：在新 attempt 执行期间到达时
     * 不得影响新轮。
     */
    @Test
    fun `delayed timeout callback for an old attempt does not stop the new attempt`() = runBlocking {
        val requestId = seed()
        val firstStarted = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            firstStarted.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        manager.start(request(requestId = requestId, attemptId = "att1"), requestId)
        assertTrue(firstStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
        // 同步捕获当时的身份快照（模拟"系统回调记录了 request+attempt"）。
        manager.onForegroundServiceTimeout()
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(requestId))

        // 换新 attempt 重试并重跑：期间再次触发**旧的**超时回调。
        assertNotNull(repository.beginRetry(requestId, "att2", null, null))
        val releaseModel = java.util.concurrent.CountDownLatch(1)
        val secondStarted = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            secondStarted.countDown()
            releaseModel.await(5, java.util.concurrent.TimeUnit.SECONDS)
            ReplyParser.parse("""{"reply":"新轮完成"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId, attemptId = "att2"), requestId)
        assertTrue(secondStarted.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("旧 attempt 的迟到超时不得掐掉新轮", manager.isRunning())
        releaseModel.countDown()
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertEquals("新轮完成", db.requestDao().getMessage("a1")!!.content)
    }

    /**
     * 假的桩即使**忽略取消**（照常返回完整回答），被取消的一轮也不得保存成功。
     */
    @Test
    fun `cancelled turn whose fake ignores cancellation never completes`() = runBlocking {
        val requestId = seed()
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val subscribed = java.util.concurrent.CountDownLatch(1)
        val collector = scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            subscribed.countDown()
            manager.events.collect { event ->
                when (event) {
                    is GenerationManager.GenerationEvent.Completed -> events += "Completed"
                    is GenerationManager.GenerationEvent.Failed -> events += "Failed"
                    else -> Unit
                }
            }
        }
        assertTrue("订阅必须在任一世事之前真正建立", subscribed.await(2, java.util.concurrent.TimeUnit.SECONDS))
        val started = java.util.concurrent.CountDownLatch(1)
        val cancelObserved = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            // 真的等到"取消已发生"，然后仍返回完整答案：模拟忽略取消的慢网络。
            assertTrue(cancelObserved.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            onEvent(StreamEvent.AnswerDelta("""{"reply":"迟到答案"}"""))
            ReplyParser.parse("""{"reply":"迟到答案"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.cancel(requestId)
        // 放行桩：它此后照常返回完整答案（真的忽略取消）。
        cancelObserved.countDown()
        assertEquals(RequestStatus.CANCELLED.name, awaitTerminal(requestId))
        assertNotEquals(
            "被取消的一轮不得保存成功",
            RequestStatus.COMPLETED.name,
            repository.get(requestId)!!.status,
        )
        assertEquals("回答位置不得被写入", "", db.requestDao().getMessage("a1")!!.content)
        collector.cancel()
        assertTrue("必须广播失败终态", events.contains("Failed"))
        assertFalse("被取消的一轮不得广播 Completed", events.contains("Completed"))
    }

    private class SlowRenderer : FigureRenderer {
        /** 进入渲染与放行渲染的两道闩，用于「绘图期间被取消」的场景。 */
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)

        override suspend fun render(figures: List<ReplyFigure>): List<String> {
            if (figures.isEmpty()) return emptyList()
            entered.countDown()
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            // 取消发生后照常返回：模拟"渲染忽略取消"的慢路径。
            return figures.map { "fake://slow.png" }
        }
    }

    /**
     * **绘图期间的取消**必须阻止提交 COMPLETED。
     *
     * 以前整段 finalizeTurn 包在 NonCancellable 里，渲染再慢也收不到取消，
     * 于是取消之后仍会落 COMPLETED —— 用户看到"已取消的答案被保存了"。
     */
    @Test
    fun `cancelling during figure rendering prevents a completed commit`() = runBlocking {
        val requestId = seed()
        val slowRenderer = SlowRenderer()
        val localManager = GenerationManager(
            modelClient = modelClient,
            requestRepository = repository,
            conversationDao = db.conversationDao(),
            credentialStore = mockk(relaxed = true),
            diagnostics = GenerationDiagnostics(),
            guard = guard,
            monotonicClock = MonotonicClock.SYSTEM,
            finalizer = GenerationFinalizer(repository, slowRenderer),
            preparer = preparer,
        )
        stubStreaming("""{"reply":"带图的回答","plots":[{"title":"P","series":[{"expr":"x"}]}]}""")
        localManager.start(request(requestId = requestId, attemptId = "att1"), requestId)
        assertTrue("必须真的进入渲染阶段", slowRenderer.entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        localManager.cancel(requestId)
        // 放行渲染：它照常返回图片路径，但提交终态之前必须看到取消。
        slowRenderer.release.countDown()
        assertEquals(
            "绘图期间取消不得落 COMPLETED",
            RequestStatus.CANCELLED.name,
            awaitTerminal(requestId),
        )
        assertEquals("不得写入回答正文", "", db.requestDao().getMessage("a1")!!.content)
        assertEquals(0, guard.activeCount())
    }

    /** 渲染抛 CancellationException 必须传播，不能被当成"渲染失败"吞掉。 */
    @Test
    fun `renderer cancellation propagates instead of being swallowed`() = runBlocking {
        val requestId = seed()
        val cancelRenderer = object : FigureRenderer {
            override suspend fun render(figures: List<ReplyFigure>): List<String> {
                throw CancellationException("渲染被取消")
            }
        }
        val localManager = GenerationManager(
            modelClient = modelClient,
            requestRepository = repository,
            conversationDao = db.conversationDao(),
            credentialStore = mockk(relaxed = true),
            diagnostics = GenerationDiagnostics(),
            guard = guard,
            monotonicClock = MonotonicClock.SYSTEM,
            finalizer = GenerationFinalizer(repository, cancelRenderer),
            preparer = preparer,
        )
        stubStreaming("""{"reply":"带图的回答","plots":[{"title":"P","series":[{"expr":"x"}]}]}""")
        localManager.start(request(requestId = requestId, attemptId = "att1"), requestId)
        assertEquals(
            "渲染取消必须传播成 CANCELLED，不能继续提交",
            RequestStatus.CANCELLED.name,
            awaitTerminal(requestId),
        )
    }

    /**
     * **首次接受的结束原因固定**：用户取消之后再来的服务超时
     * 不得把不可重试的 CANCELLED 改写成可重试的 INTERRUPTED。
     */
    @Test
    fun `first accepted stop reason wins for cancel then timeout`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        manager.start(request(requestId = requestId, attemptId = "att1"), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.cancel(requestId)
        manager.onForegroundServiceTimeout()
        assertEquals(RequestStatus.CANCELLED.name, awaitTerminal(requestId))
        assertFalse(
            "先到的用户取消必须固定为不可重试",
            FailureKind.fromName(repository.get(requestId)!!.failureKind)!!.isRetryable,
        )
        assertNull(repository.beginRetry(requestId, "att2", null, null))
    }

    /** 反向顺序：先服务超时、再用户取消 ⇒ 仍是可重试的 INTERRUPTED。 */
    @Test
    fun `first accepted stop reason wins for timeout then cancel`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            delay(30_000)
            throw ModelException(ModelException.Kind.NETWORK, "不该走到这里")
        }
        manager.start(request(requestId = requestId, attemptId = "att1"), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.onForegroundServiceTimeout()
        manager.cancel(requestId)
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(requestId))
        assertTrue(
            "先到的服务超时必须保持可重试",
            FailureKind.fromName(repository.get(requestId)!!.failureKind)!!.isRetryable,
        )
        assertNotNull(repository.beginRetry(requestId, "att2", null, null))
    }

    /** owner 必须持有到 guard 令牌释放之后（保护不提前掉落、也不被新轮抢先）。 */
    @Test
    fun `ownership is held until the guard token is released`() = runBlocking {
        val requestId = seed()
        stubStreaming("""{"reply":"正文"}""", chunkSize = 4)
        manager.start(request(requestId = requestId, attemptId = "att1"), requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        assertTrue("必须发生过 guard 释放", guard.ownerAtRelease.isNotEmpty())
        assertTrue(
            "释放保护令牌时 owner 必须还认领着这一轮",
            guard.ownerAtRelease.all { it == requestId },
        )
        assertEquals(0, guard.activeCount())
        assertNull("收尾完成后不得再持有 owner", manager.activeRequestId())
    }

    /**
     * 超时回调在**回调边界同步**固定原因：先超时、随后用户取消 ⇒ 仍是 INTERRUPTED。
     * （旧实现把登记放进 scope.launch，迟到的用户取消会抢先占据"首次原因"。）
     */
    @Test
    fun `timeout reason claimed synchronously before a later user cancel`() = runBlocking {
        val requestId = seed()
        val started = java.util.concurrent.CountDownLatch(1)
        val modelSeen = java.util.concurrent.CountDownLatch(1)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            started.countDown()
            modelSeen.await(5, java.util.concurrent.TimeUnit.SECONDS)
            // 假装模型忽略取消照常返回：取消路径必须仍然成立。
            ReplyParser.parse("""{"reply":"晚了"}""", normalizeMarkdown = false)
        }
        manager.start(request(requestId = requestId, attemptId = "att1"), requestId)
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        manager.onForegroundServiceTimeout()   // 同步登记 SERVICE_TIMEOUT
        manager.cancel(requestId)              // 迟到的用户取消不得覆盖
        modelSeen.countDown()
        assertEquals(
            "超时必须先固定为 INTERRUPTED（可重试）",
            RequestStatus.INTERRUPTED.name,
            awaitTerminal(requestId),
        )
        assertTrue(
            FailureKind.fromName(repository.get(requestId)!!.failureKind)!!.isRetryable,
        )
    }

    // ── 快照集成（coordinator-snapshot-integration.md）──

    private class SnapshotIdentity(
        /** 主模型是否带视觉（true ⇒ 照片走 direct 直送路由）。 */
        primaryVisionEnabled: Boolean = false,
        val primary: com.moge.app.data.credential.AiResolvedIdentity =
            com.moge.app.data.credential.AiResolvedIdentity(
                profileId = "profile-1",
                baseUrl = "https://relay.example/v1",
                model = "test-model",
                apiKey = "test-key",
                visionEnabled = primaryVisionEnabled,
                searchProtocol = com.moge.app.data.credential.AiSearchProtocol.OFF,
                reasoningEffort = com.moge.app.data.credential.AiReasoningEffort.LOW,
            ),
        val vision: com.moge.app.data.credential.AiResolvedIdentity =
            com.moge.app.data.credential.AiResolvedIdentity(
                profileId = "vision-1",
                baseUrl = "https://vision.example/v1",
                model = "vision-model",
                apiKey = "vision-key",
                visionEnabled = true,
                searchProtocol = com.moge.app.data.credential.AiSearchProtocol.OFF,
                reasoningEffort = com.moge.app.data.credential.AiReasoningEffort.LOW,
            ),
    )

    /**
     * 拼一个**真实 preparer** 的管理器（mock 只挡网络客户端），用于快照行为验收。
     */
    private fun snapshotManager(
        identity: SnapshotIdentity = SnapshotIdentity(),
        credentialStore: com.moge.app.data.credential.AiCredentialStore = mockk {
            every { resolveActiveIdentity() } returns identity.primary
            every { resolveIdentityFor("vision-1") } returns identity.vision
            every { resolveIdentityFor("profile-1") } returns identity.primary
            every { questionVisionProfileId() } returns "vision-1"
        },
        visionTranscribe: String = "转写出来的题目",
    ): Triple<GenerationManager, GenerationPreparer, com.moge.app.data.credential.AiCredentialStore> {
        val store = credentialStore
        coEvery {
            modelClient.completeWithProfile(any(), any(), any(), any())
        } returns visionTranscribe
        val realPreparer = GenerationPreparer(
            modelClient = modelClient,
            conversationDao = db.conversationDao(),
            settings = mockk(relaxed = true) {
                coEvery { current() } returns com.moge.app.data.prefs.UserSettings()
            },
            credentialStore = store,
        )
        val local = GenerationManager(
            modelClient = modelClient,
            requestRepository = repository,
            conversationDao = db.conversationDao(),
            credentialStore = mockk(relaxed = true),
            diagnostics = GenerationDiagnostics(),
            guard = guard,
            monotonicClock = MonotonicClock.SYSTEM,
            finalizer = GenerationFinalizer(repository, renderer),
            preparer = realPreparer,
        )
        return Triple(local, realPreparer, store)
    }

    /** 提交后立刻（网络前）就有可重试快照：转写被打断也能按原档案重跑。 */
    @Test
    fun `interrupted transcription retries through the original vision profile`() = runBlocking {
        val photo = java.io.File.createTempFile("snap", ".png").apply { writeBytes(ByteArray(64) { 9 }) }
        val (local, _, _) = snapshotManager(visionTranscribe = "转写出来的题目")
        stubStreaming("""{"reply":"看图回答"}""")
        // Install the failure before submit launches preparation on its worker scope.
        coEvery { modelClient.completeWithProfile(any(), any(), any(), any()) } throws
            ModelException(ModelException.Kind.NETWORK, "识别服务断开")
        val created = local.submit(
            GenerationManager.Submission(
                conversationId = "c1",
                userText = "",
                attachmentPaths = listOf(photo.absolutePath),
            ),
        )
        // 快照随请求一起落库：转写路由 + 识别档案 id + 有附件（纯照片题原问题为空仍可重试）。
        val initial = com.moge.app.data.llm.SnapshotCodec
            .decode(repository.get(created.requestId)!!.snapshotJson)!!
        assertTrue(initial.isComplete)
        assertEquals(
            com.moge.app.data.llm.RequestSnapshot.PHOTO_ROUTE_TRANSCRIBE,
            initial.photoRoute,
        )
        assertEquals("vision-1", initial.visionProfileId)
        assertEquals("vision-model", initial.visionModel)
        assertFalse(initial.prepared)

        // **准备阶段（转写）失败** → INTERRUPTED，用落库快照重试。
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(created.requestId))

        // 重试：转写**真的重新执行**（同一识别档案），正文来自转写结果，主模型 0 张图。
        val retried = repository.beginRetry(created.requestId, "att-snap-retry", null, null)
        assertNotNull("准备中断必须可重试", retried)
        val profileIds = java.util.concurrent.CopyOnWriteArrayList<String>()
        val imageCounts = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val sentLastUser = java.util.concurrent.CopyOnWriteArrayList<String>()
        coEvery { modelClient.completeWithProfile(any(), any(), any(), any()) } coAnswers {
            profileIds += arg<String>(0)
            "转写出来的题目"
        }
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            imageCounts += arg<List<String>>(1).size
            sentLastUser += arg<List<ChatMessage>>(0).lastOrNull { it.role == "user" }?.content.orEmpty()
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            val payload = """{"reply":"看图回答"}"""
            onEvent(StreamEvent.AnswerDelta(payload))
            ReplyParser.parse(payload, normalizeMarkdown = false)
        }
        local.submitRetry(
            requestId = created.requestId,
            attemptId = "att-snap-retry",
        )
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(created.requestId))
        assertEquals("重试必须用**原识别档案**（不是当前配的）", listOf("vision-1"), profileIds.toList())
        assertTrue("重试转写后不得把图直发文字主模型", imageCounts.all { it == 0 })
        assertEquals(
            "本轮发给模型的最后一条 user 消息必须是转写文本",
            "转写出来的题目",
            sentLastUser.lastOrNull().orEmpty(),
        )
        photo.delete()
        Unit
    }

    /**
     * **准备已完成的 direct 重试必须重发原图**（snapshot-direct-image-fix）。
     *
     * 缺陷：`preparedRetryFromSnapshot()` 一律 `imageBase64s = emptyList()`，
     * 于是准备已完成（转写/选上下文已做完、快照已补写）后的断流重试，
     * 会把一张看图题**静默降级成无图空问**发给主模型。
     *
     * 修复后：按快照钉下的 direct 路由重读落库附件并重新编码，
     * 主模型收到的图片数量必须等于原图数量。
     */
    @Test
    fun `prepared direct retry resends the original images to the main model`() = runBlocking {
        val photos = (1..2).map { index ->
            java.io.File.createTempFile("direct-$index", ".png")
                .apply { writeBytes(ByteArray(64) { (index * 5).toByte() }) }
        }
        try {
            val (local, _, _) = snapshotManager(
                identity = SnapshotIdentity(primaryVisionEnabled = true),
            )
            // **同一个桩覆盖首发与重试两段**：首发吐一半后断流（打断流式），
            // 重试（第二次 chatStreaming 调用）完整回答 —— 重试的图片数量才能被独立捕获。
            val imageCounts = java.util.concurrent.CopyOnWriteArrayList<Int>()
            coEvery {
                modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
            } coAnswers {
                imageCounts += arg<List<String>>(1).size
                val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
                if (imageCounts.size == 1) {
                    onEvent(StreamEvent.AnswerDelta("""{"reply":"生成到一半"""))
                    throw ModelException(ModelException.Kind.NETWORK, "流式连接中断")
                }
                val payload = """{"reply":"重试看图回答"}"""
                onEvent(StreamEvent.AnswerDelta(payload))
                ReplyParser.parse(payload, normalizeMarkdown = false)
            }
            val created = local.submit(
                GenerationManager.Submission(
                    conversationId = "c1",
                    userText = "解这道图里的题",
                    attachmentPaths = photos.map { it.absolutePath },
                ),
            )
            // 首发：direct 直送带全部原图，流在半截被打断（打断在准备完成之后，
            // 快照已补写 prepared=true —— 正是要验证的重试场景）。
            assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(created.requestId))
            val stored = com.moge.app.data.llm.SnapshotCodec
                .decode(repository.get(created.requestId)!!.snapshotJson)!!
            assertEquals(
                com.moge.app.data.llm.RequestSnapshot.PHOTO_ROUTE_DIRECT,
                stored.photoRoute,
            )
            assertTrue(stored.prepared)
            assertEquals(
                "正常首发必须带全部原图（修复不得改变首发）",
                listOf(2), imageCounts.take(1),
            )

            // 重试：快照已是 prepared ⇒ 走 preparedRetryFromSnapshot。
            val retried = repository.beginRetry(created.requestId, "att-direct-retry", null, null)
            assertNotNull("断流必须可重试", retried)
            local.submitRetry(
                requestId = created.requestId,
                attemptId = "att-direct-retry",
            )
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(created.requestId))
            assertEquals("首发（断流）+ 重试共两次主模型调用", 2, imageCounts.size)
            assertEquals(
                "prepared direct 重试必须重发全部原图",
                2, imageCounts.last(),
            )
        } finally {
            photos.forEach { it.delete() }
        }
        Unit
    }

    /**
     * **direct 重试缺图必须明确失败且不发主模型**（snapshot-direct-image-fix）。
     *
     * 附件文件全部丢失时：不能发 0 张图的"看图题"（模型只能瞎编），
     * 也不能发部分图（数量不符）—— 必须落 ATTACHMENT_MISSING 终态，
     * 且主模型一次网络请求都不发生。
     */
    @Test
    fun `prepared direct retry fails clearly without calling the main model when images are gone`() = runBlocking {
        val photo = java.io.File.createTempFile("direct-gone", ".png")
            .apply { writeBytes(ByteArray(64) { 11 }) }
        val (local, _, _) = snapshotManager(identity = SnapshotIdentity(primaryVisionEnabled = true))
        // 同一个桩覆盖首发与重试两段：首发 direct 直送、流半截被打断
        // （准备已完成、快照已补写 prepared=true）；重试若真的发出请求会被计数。
        val mainModelCalls = java.util.concurrent.CopyOnWriteArrayList<Int>()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            mainModelCalls += arg<List<String>>(1).size
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            if (mainModelCalls.size == 1) {
                onEvent(StreamEvent.AnswerDelta("""{"reply":"生成到一半"""))
                throw ModelException(ModelException.Kind.NETWORK, "流式连接中断")
            }
            val payload = """{"reply":"重试看图回答"}"""
            onEvent(StreamEvent.AnswerDelta(payload))
            ReplyParser.parse(payload, normalizeMarkdown = false)
        }
        val created = local.submit(
            GenerationManager.Submission(
                conversationId = "c1",
                userText = "解这道图里的题",
                attachmentPaths = listOf(photo.absolutePath),
            ),
        )
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(created.requestId))
        assertEquals(
            "首发必须走 direct 直送",
            listOf(1), mainModelCalls.toList(),
        )
        // 首发之后附件文件被清理 —— 重试时编码必然数量不符。
        photo.delete()

        val retried = repository.beginRetry(created.requestId, "att-direct-gone", null, null)
        assertNotNull(retried)
        local.submitRetry(
            requestId = created.requestId,
            attemptId = "att-direct-gone",
        )
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(created.requestId))
        val record = repository.get(created.requestId)!!
        assertEquals(
            "缺图必须归类为 ATTACHMENT_MISSING",
            FailureKind.ATTACHMENT_MISSING.name,
            record.failureKind,
        )
        assertTrue(
            "失败信息要指向图片问题",
            record.failureMessage.contains("图片") || record.failureMessage.contains("附件") ||
                record.failureMessage.contains("原图"),
        )
        assertEquals(
            "缺图失败绝不能发出主模型请求（首发 1 次之后再无调用）",
            1, mainModelCalls.size,
        )
    }

    /**
     * **transcribe 路由的 prepared 重试仍然 0 图 + 用保存的转写文本**
     * （snapshot-direct-image-fix）。
     *
     * 准备已完成 ⇒ 转写不重跑、识别模型不被再次调用；
     * 文字主模型绝不能收到任何图片 base64。
     */
    @Test
    fun `prepared transcribe retry sends zero images and reuses the saved transcription`() = runBlocking {
        val photo = java.io.File.createTempFile("snap-t", ".png").apply { writeBytes(ByteArray(64) { 9 }) }
        try {
            val (local, _, _) = snapshotManager(visionTranscribe = "转写出来的题目")
            // 同一个桩覆盖首发与重试两段：首发流断（打断在准备完成后），
            // 重试（第二次调用）完整回答 —— 重试的图片数量与正文才能被独立捕获。
            val imageCounts = java.util.concurrent.CopyOnWriteArrayList<Int>()
            val sentLastUser = java.util.concurrent.CopyOnWriteArrayList<String>()
            coEvery {
                modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
            } coAnswers {
                imageCounts += arg<List<String>>(1).size
                sentLastUser += arg<List<ChatMessage>>(0).lastOrNull { it.role == "user" }?.content.orEmpty()
                val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
                if (imageCounts.size == 1) {
                    onEvent(StreamEvent.AnswerDelta("""{"reply":"生成到一半"""))
                    throw ModelException(ModelException.Kind.NETWORK, "流式连接中断")
                }
                val payload = """{"reply":"重试转写回答"}"""
                onEvent(StreamEvent.AnswerDelta(payload))
                ReplyParser.parse(payload, normalizeMarkdown = false)
            }
            val created = local.submit(
                GenerationManager.Submission(
                    conversationId = "c1",
                    userText = "",
                    attachmentPaths = listOf(photo.absolutePath),
                ),
            )
            // 首发：转写完成、主模型收到 0 图 + 转写文本，然后流在半截被打断。
            assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(created.requestId))
            val stored = com.moge.app.data.llm.SnapshotCodec
                .decode(repository.get(created.requestId)!!.snapshotJson)!!
            assertEquals(
                com.moge.app.data.llm.RequestSnapshot.PHOTO_ROUTE_TRANSCRIBE,
                stored.photoRoute,
            )
            assertTrue(stored.prepared)
            assertEquals(0, imageCounts.single())
            assertEquals("转写出来的题目", sentLastUser.single())

            val visionCalls = AtomicInteger(0)
            coEvery { modelClient.completeWithProfile(any(), any(), any(), any()) } coAnswers {
                visionCalls.incrementAndGet()
                "不该被重新转写"
            }

            val retried = repository.beginRetry(created.requestId, "att-trans-retry", null, null)
            assertNotNull(retried)
            local.submitRetry(
                requestId = created.requestId,
                attemptId = "att-trans-retry",
            )
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(created.requestId))
            assertEquals("首发（断流）+ 重试共两次主模型调用", 2, imageCounts.size)
            assertTrue(
                "转写路由的首发与重试都必须是 0 图",
                imageCounts.all { it == 0 },
            )
            assertEquals("prepared 重试不得重新转写", 0, visionCalls.get())
            assertEquals(
                "重试正文必须仍是**保存的**转写文本",
                "转写出来的题目",
                sentLastUser.lastOrNull().orEmpty(),
            )
        } finally {
            photo.delete()
        }
        Unit
    }

    /** 快照序列化绝不包含密钥、图片 base64 或 URL 查询凭证。 */
    @Test
    fun `initial snapshot never contains secrets or image data`() = runBlocking {
        val photo = java.io.File.createTempFile("snap", ".png").apply { writeBytes(ByteArray(64) { 3 }) }
        val (local, preparer, _) = snapshotManager()
        stubStreaming("""{"reply":"回答"}""")
        val created = local.submit(
            GenerationManager.Submission(
                conversationId = "c1",
                userText = "题目",
                attachmentPaths = listOf(photo.absolutePath),
            ),
        )
        awaitTerminal(created.requestId)
        val raw = repository.get(created.requestId)!!.snapshotJson
        listOf("test-key", "vision-key", "Bearer", "sk-", "api_key", "base64,", "data:image").forEach { needle ->
            assertFalse("快照不得包含 $needle", raw.contains(needle, ignoreCase = true))
        }
        photo.delete()
        Unit
    }

    /** 缺照片路线的快照必须明确失败，不能猜路由或换供应商。 */
    @Test
    fun `snapshot without photo route fails retry clearly`() = runBlocking {
        val requestId = seed(requestId = "rv1", attemptId = "attv1", answerMessageId = "av1", userMessageId = "uv1")
        // 手工写一份缺模型名的 JSON（isComplete=false）。
        val legacyJson = """{"endpointIdentity":"https://old.example/v1","protocol":"CHAT_COMPLETIONS"}"""
        db.requestDao().updateSnapshotForAttempt(
            requestId, "attv1", legacyJson, java.time.Instant.now(),
        )
        val (local, preparer, _) = snapshotManager()
        val record = repository.get(requestId)!!
        val prepared = preparer.prepareRetry(record)
        assertTrue("旧快照必须明确失败", prepared.failure != null)
        assertTrue(
            "失败信息要说明原因而不是猜路由",
            prepared.failure.orEmpty().contains("旧版本") || prepared.failure.orEmpty().contains("不完整"),
        )
    }

    /** request+attempt 围栏：旧 attempt 的快照补写不得覆盖新 attempt。 */
    @Test
    fun `snapshot write is fenced by request and attempt`() = runBlocking {
        val requestId = seed()
        val old = com.moge.app.data.llm.RequestSnapshot(
            model = "old-model",
            endpointIdentity = "https://old.example/v1",
            protocol = "chat_completions",
            sourceUserText = "旧",
        )
        assertFalse(repository.saveSnapshotForAttempt(requestId, "att-old", old))
        val fresh = com.moge.app.data.llm.RequestSnapshot(
            model = "new-model",
            endpointIdentity = "https://new.example/v1",
            protocol = "chat_completions",
            sourceUserText = "新",
        )
        assertTrue(repository.saveSnapshotForAttempt(requestId, "att1", fresh))
        assertEquals("新", com.moge.app.data.llm.SnapshotCodec
            .decode(repository.get(requestId)!!.snapshotJson)!!.sourceUserText)
    }

    /** 异常路径下活动标记必须释放，否则后续请求都会被"单飞"永久挡住。 */
    @Test
    fun `activity marker is released after a failed generation`() = runBlocking {
        val requestId = seed()
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers { throw ModelException(ModelException.Kind.NETWORK, "断网") }
        manager.start(request(requestId = requestId), requestId)
        awaitTerminal(requestId)
        assertNull("失败后活动标记必须释放", manager.activeRequestId())
        assertFalse(manager.isRunning())
        // 并且能立刻开始下一轮（不会被永久挡住）。
        val secondId = seed(requestId = "r2", attemptId = "att2", answerMessageId = "a2", userMessageId = "u2")
        stubStreaming("""{"reply":"第二轮"}""")
        manager.start(request(requestId = secondId, attemptId = "att2", answerMessageId = "a2"), secondId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(secondId))
    }

    private companion object {
        val TERMINAL_STATUSES = setOf(
            RequestStatus.COMPLETED.name,
            RequestStatus.INTERRUPTED.name,
            RequestStatus.CANCELLED.name,
        )

        /** 终态稳定期复核次数：防止"闪过 COMPLETED 又变回 RUNNING"。 */
        const val STABILITY_CHECKS = 3
    }
}
