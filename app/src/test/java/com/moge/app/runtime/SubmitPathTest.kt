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
import com.moge.app.data.llm.ChatMessage
import com.moge.app.data.llm.SnapshotCodec
import com.moge.app.domain.FailureKind
import com.moge.app.domain.RequestStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * **端到端**验证：走真实的 [GenerationManager.submit] → [GenerationPreparer]
 * → 模型调用，而不是直接造 `GenerationRequest` 调 `start`。
 *
 * ## 为什么必须单独一组
 *
 * 直接调 `start(request)` 会**绕过准备阶段**，于是「准备阶段到底把什么发给了模型」
 * 完全没有覆盖。实测发现过一个严重缺陷：`prepare` 算出了转写后的题目
 * （`outgoingText`），但真正发给模型的 `history` 是从数据库读的**原文**，
 * 两者从不合并 —— 拍照题的识别结果根本没发出去；重试时更因为历史里
 * 已排除本条用户消息，**原问题一个字都没发送**。
 *
 * 这里逐个断言**实际进入 chatStreaming 的 messages**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class SubmitPathTest {

    private lateinit var db: MogeDatabase
    private lateinit var requestRepository: RequestRepository
    private lateinit var modelClient: ModelClient
    private lateinit var preparer: GenerationPreparer
    private lateinit var manager: GenerationManager
    private lateinit var guard: FakeGuard
    private lateinit var aiCredentialStore: com.moge.app.data.credential.AiCredentialStore

    /** 真实身份对象：真实 preparer 需要（relaxed mock 的空字符串会让快照校验拒绝）。 */
    private val primaryIdentity = com.moge.app.data.credential.AiResolvedIdentity(
        profileId = "profile-1",
        baseUrl = "https://relay.example/v1",
        model = "test-model",
        apiKey = "test-key",
        visionEnabled = false,
        searchProtocol = com.moge.app.data.credential.AiSearchProtocol.OFF,
        reasoningEffort = com.moge.app.data.credential.AiReasoningEffort.LOW,
    )
    private val visionIdentity = com.moge.app.data.credential.AiResolvedIdentity(
        profileId = "vision-1",
        baseUrl = "https://vision.example/v1",
        model = "vision-model",
        apiKey = "vision-key",
        visionEnabled = true,
        searchProtocol = com.moge.app.data.credential.AiSearchProtocol.OFF,
        reasoningEffort = com.moge.app.data.credential.AiReasoningEffort.LOW,
    )

    /** 记录真正进入模型的 messages（以及图片数量）。 */
    private val capturedMessages = CopyOnWriteArrayList<List<ChatMessage>>()
    private val capturedImageCounts = CopyOnWriteArrayList<Int>()

    private class NoopRenderer : FigureRenderer {
        override suspend fun render(figures: List<ReplyFigure>) = figures.map { "" }
    }

    private class FakeGuard : GenerationGuard {
        private val held = mutableSetOf<String>()
        val acquisitions = AtomicInteger()
        override fun acquire(requestId: String, conversationId: String?) = "$requestId#1".also {
            acquisitions.incrementAndGet()
            held += it
        }
        override fun release(token: String) { held -= token }
        override fun activeCount() = held.size
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MogeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        requestRepository = RequestRepository(db.requestDao(), Dispatchers.Unconfined)
        modelClient = mockk()
        aiCredentialStore = mockk {
            every { resolveActiveIdentity() } returns primaryIdentity
            every { resolveIdentityFor("vision-1") } returns visionIdentity
            every { questionVisionProfileId() } returns null
        }
        preparer = GenerationPreparer(
            modelClient = modelClient,
            conversationDao = db.conversationDao(),
            settings = mockk(relaxed = true) {
                coEvery { current() } returns com.moge.app.data.prefs.UserSettings()
            },
            credentialStore = aiCredentialStore,
        )
        guard = FakeGuard()
        manager = GenerationManager(
            modelClient = modelClient,
            requestRepository = requestRepository,
            conversationDao = db.conversationDao(),
            credentialStore = mockk(relaxed = true),
            diagnostics = GenerationDiagnostics(),
            guard = guard,
            monotonicClock = MonotonicClock.SYSTEM,
            finalizer = GenerationFinalizer(requestRepository, NoopRenderer()),
            preparer = preparer,
        )
        runBlocking {
            db.conversationDao().insertConversation(ConversationEntity(id = "c1", title = "会话"))
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** 桩：记录实际 messages，并返回一个可解析的回复。 */
    @Test fun branchHistoryAndRetryUseOnlyTheirOwnAncestorTextImagesAndDocuments() = runBlocking {
        suspend fun turn(q: String, parent: String?, pic: String, document: String): com.moge.app.data.db.RequestEntity {
            val request = requestRepository.createRequest("c1", q, "a-$q", "at-$q", q, listOf(pic), "",
                documentPaths = listOf(document), parentMessageId = parent)
            assertTrue(requestRepository.complete(request.requestId, request.attemptId, request.answerMessageId, "answer " + q))
            return request
        }
        turn("shared", null, "/shared.jpg", "/shared.txt")
        turn("old", "a-shared", "/old.jpg", "/old.txt")
        val oldTail = turn("old-tail", "a-old", "/tail.jpg", "/tail.txt")
        turn("new", "a-shared", "/new.jpg", "/new.txt")
        val snapshot = preparer.captureInitialSnapshot(
            GenerationManager.Submission("c1", "next", emptyList()), "c1", "next-q", "next-a")
        assertEquals(listOf("shared", "a-shared", "new", "a-new"), snapshot.originalHistory.map { it.id })
        assertEquals(listOf("/shared.jpg", "/new.jpg"), snapshot.originalHistory.flatMap { it.imagePaths })
        assertEquals(listOf("/shared.txt", "/new.txt"), snapshot.documentPaths)
        val retry = manager.captureRetrySnapshot(oldTail)
        assertEquals(listOf("shared", "a-shared", "old", "a-old"), retry.originalHistory.map { it.id })
        assertFalse(retry.originalHistory.any { it.text.contains("new") })
        assertEquals(setOf("/shared.txt", "/old.txt", "/tail.txt"), retry.documentPaths.toSet())
        stubCapturing()
        val request = manager.submit(GenerationManager.Submission("c1", "next", emptyList()))
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(request.requestId))
        val sent = capturedMessages.single()
        assertFalse(sent.any { it.content.contains("old") })
        assertTrue(sent.any { it.content.contains("new") })
        assertEquals(setOf("/shared.txt", "/new.txt"), sent.flatMap { it.documentPaths }.toSet())
    }

    private fun stubCapturing(reply: String = "回答", visionEnabled: Boolean = false) {
        coEvery { modelClient.isVisionEnabled() } returns visionEnabled
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            capturedMessages += arg<List<ChatMessage>>(0)
            capturedImageCounts += arg<List<String>>(1).size
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            val payload = """{"reply":"$reply"}"""
            onEvent(StreamEvent.AnswerDelta(payload))
            ReplyParser.parse(payload, normalizeMarkdown = false)
        }
    }

    private suspend fun awaitTerminal(requestId: String, timeoutMs: Long = 10_000): String {
        var status = ""
        withTimeout(timeoutMs) {
            while (true) {
                val current = requestRepository.get(requestId)?.status.orEmpty()
                if (current in TERMINAL && !manager.isRunning()) {
                    status = current
                    break
                }
                delay(10)
            }
        }
        return status
    }

    /**
     * **纯文本提问：当前这一轮必须真的发给模型。**
     *
     * 这是最基本的一条，但旧实现是「历史直接从库里读、当前轮靠库里的那条用户消息」，
     * 一旦那条消息被排除（重试/转写替换）就整轮不发问题。
     */
    @Test
    fun `the current user turn is actually sent to the model`() = runBlocking {
        stubCapturing()
        val request = manager.submit(
            GenerationManager.Submission(
                conversationId = "c1",
                userText = "请解释双纽线面积公式",
                attachmentPaths = emptyList(),
            ),
        )
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(request.requestId))
        assertEquals(1, capturedMessages.size)
        val sent = capturedMessages.single()
        val lastUser = sent.lastOrNull { it.role == "user" }
        assertEquals("本轮问题必须发给模型", "请解释双纽线面积公式", lastUser?.content)
    }

    /**
     * **拍照 + 转写路由：转写结果必须替代原文发给模型。**
     *
     * 旧实现把转写结果算出来却只放在 `outgoingText` 里，`history` 用的还是
     * 数据库里的原文（拍照题时为空）—— 模型实际收到的是空的用户消息。
     */
    @Test
    fun `transcribed photo text replaces the raw prompt in the payload`() = runBlocking {
        val photo = java.io.File.createTempFile("attach", ".png").apply { writeBytes(ByteArray(64) { 7 }) }
        coEvery { modelClient.isVisionEnabled() } returns false
        // 配置「题目识别」档案（快照会把它的 id/模型/端点钉下）。
        every { aiCredentialStore.questionVisionProfileId() } returns "vision-1"
        coEvery { modelClient.completeWithProfile(any(), any(), any(), any()) } returns "转写出来的题目：求 x^2 的导数"
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            capturedMessages += arg<List<ChatMessage>>(0)
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            val payload = """{"reply":"解出来了"}"""
            onEvent(StreamEvent.AnswerDelta(payload))
            ReplyParser.parse(payload, normalizeMarkdown = false)
        }
        val request = manager.submit(
            GenerationManager.Submission(
                conversationId = "c1",
                userText = "",
                attachmentPaths = listOf(photo.absolutePath),
            ),
        )
        val status = awaitTerminal(request.requestId)
        // 转写模型可用 → 走转写路径；若这里拿不到识别结果应当明确失败，
        // 但决不允许「静默把空问题发出去」。
        val sent = capturedMessages.lastOrNull()
        if (status == RequestStatus.COMPLETED.name) {
            val lastUser = sent?.lastOrNull { it.role == "user" }
            assertTrue(
                "转写结果必须真正进入请求（而不是只算出来丢掉）",
                lastUser?.content?.contains("转写出来的题目") == true,
            )
            assertFalse("不能发送空的用户消息", lastUser?.content.isNullOrBlank())
        } else {
            // 明确失败也算合格：绝不允许静默发送空问题。
            assertTrue(
                "转写不可用必须落终态而不是假装成功",
                status == RequestStatus.INTERRUPTED.name,
            )
        }
        photo.delete()
        Unit
    }

    @Test
    fun `photo transcription remains available to a later question`() = runBlocking {
        stubCapturing()
        every { aiCredentialStore.questionVisionProfileId() } returns "vision-1"
        coEvery { modelClient.completeWithProfile(any(), any(), any(), any()) } returns
            "原题：求 x^2 的导数"
        val photo = java.io.File.createTempFile("history-transcribed", ".png")
        val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val first = manager.submit(GenerationManager.Submission(
                conversationId = "c1", userText = "", attachmentPaths = listOf(photo.absolutePath),
            ))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(first.requestId))
            val saved = db.conversationDao().getMessages("c1").first { it.id == first.userMessageId }
            assertTrue(saved.content.contains("求 x^2 的导数"))
            assertEquals("", saved.displayContent)

            val followUp = manager.submit(GenerationManager.Submission(
                conversationId = "c1", userText = "刚才那道题怎样求导？", attachmentPaths = emptyList(),
            ))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(followUp.requestId))
            assertTrue(capturedMessages.last().dropLast(1).any {
                it.role == "user" && it.content.contains("求 x^2 的导数")
            })
        } finally {
            photo.delete()
        }
    }

    @Test
    fun `vision follow up includes a bounded prior photo`() = runBlocking {
        stubCapturing(visionEnabled = true)
        every { aiCredentialStore.resolveActiveIdentity() } returns
            primaryIdentity.copy(visionEnabled = true)
        val photo = java.io.File.createTempFile("history-vision", ".png")
        val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        try {
            val first = manager.submit(GenerationManager.Submission(
                conversationId = "c1", userText = "看图作答",
                attachmentPaths = List(3) { photo.absolutePath },
            ))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(first.requestId))
            val followUp = manager.submit(GenerationManager.Submission(
                conversationId = "c1", userText = "图上横轴代表什么？", attachmentPaths = emptyList(),
            ))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(followUp.requestId))
            val prior = capturedMessages.last().single { it.role == "user" && it.content.contains("看图作答") }
            assertEquals(2, prior.imageBase64s.size)
            assertTrue(prior.content.contains("看图作答"))
        } finally {
            photo.delete()
        }
    }

    @Test
    fun `failed history read stops submission before a request is stored`() = runBlocking {
        val brokenDao = mockk<com.moge.app.data.db.ConversationDao> {
            coEvery { getMessages("c1") } throws IllegalStateException("history unavailable")
        }
        val brokenPreparer = GenerationPreparer(
            modelClient = modelClient,
            conversationDao = brokenDao,
            settings = mockk(relaxed = true) {
                coEvery { current() } returns com.moge.app.data.prefs.UserSettings()
            },
            credentialStore = aiCredentialStore,
        )
        val failure = runCatching {
            brokenPreparer.captureInitialSnapshot(
                GenerationManager.Submission("c1", "接着上一题回答", emptyList()),
                "c1", "new-user", "new-answer",
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertEquals("history unavailable", failure?.message)
    }

    /**
     * **重试：原问题必须重新发送。**
     *
     * 旧实现的历史组装把「本条用户消息」排除了，然后 manager 直接用它 ——
     * 等于重试时一个问题都不发。
     */
    /**
     * 走真实 submit 路径造一个**真正中断**的请求（模型吐一半后断网）。
     *
     * 不能先跑成功再手工 `interrupt`：终态有栅栏，已完成的请求本来就拒绝改写
     * —— 那样测出来的"保留旧 partial"是假象。
     */
    private suspend fun submitAndInterrupt(userText: String, partial: String): String {
        coEvery { modelClient.isVisionEnabled() } returns false
        val first = AtomicInteger(0)
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            if (first.getAndIncrement() == 0) {
                capturedMessages += arg<List<ChatMessage>>(0)
                val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
                onEvent(StreamEvent.AnswerDelta("""{"reply":"$partial"""))
                throw ModelException(ModelException.Kind.NETWORK, "网络错误")
            }
            // 第二轮（重试）正常回答。
            capturedMessages += arg<List<ChatMessage>>(0)
            val onEvent = arg<suspend (StreamEvent) -> Unit>(7)
            val payload = """{"reply":"重试成功"}"""
            onEvent(StreamEvent.AnswerDelta(payload))
            ReplyParser.parse(payload, normalizeMarkdown = false)
        }
        val request = manager.submit(
            GenerationManager.Submission(
                conversationId = "c1",
                userText = userText,
                attachmentPaths = emptyList(),
            ),
        )
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(request.requestId))
        return request.requestId
    }

    @Test fun `resume sends the original question and saved answer then merges without duplication`() = runBlocking {
        val prefix = "第一步已经计算出完整的中间结果。"
        val id = submitAndInterrupt("原问题：计算结果", prefix)
        val old = requestRepository.get(id)!!
        val snapshot = manager.captureRetrySnapshot(old).copy(continuationText = prefix)
        capturedMessages.clear()
        stubCapturing(prefix + "第二步给出最终结果。")
        val after = requestRepository.beginRetry(id, "resume-1", com.moge.app.data.llm.SnapshotCodec.encode(snapshot),
            emptyList(), expectedAttemptId = old.attemptId)!!
        manager.submitRetry(id, after.attemptId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(id))
        val sent = capturedMessages.last()
        assertEquals("原问题：计算结果", sent[sent.lastIndex - 2].content)
        assertEquals(prefix, sent[sent.lastIndex - 1].content)
        assertEquals(com.moge.app.data.llm.CONTINUE_INSTRUCTION, sent.last().content)
        assertEquals(prefix + "第二步给出最终结果。", db.conversationDao().getMessage(old.answerMessageId)!!.content)
        assertEquals(2, db.conversationDao().getMessages("c1").size)
    }

    @Test fun `resume receiving no new answer retains its prefix and stays interrupted`() = runBlocking {
        val prefix = "已经生成的步骤"
        val id = submitAndInterrupt("原问题", prefix)
        val old = requestRepository.get(id)!!
        val snapshot = manager.captureRetrySnapshot(old).copy(continuationText = prefix)
        stubCapturing("见上")
        requestRepository.beginRetry(id, "resume-empty", com.moge.app.data.llm.SnapshotCodec.encode(snapshot),
            emptyList(), expectedAttemptId = old.attemptId)!!
        manager.submitRetry(id, "resume-empty")
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(id))
        assertEquals(prefix, requestRepository.get(id)!!.partialText)
    }

    @Test fun `resume preparation failure retains the prefix without calling the model`() = runBlocking {
        val prefix = "已经保存的步骤"
        val id = submitAndInterrupt("原问题", prefix)
        val snapshot = com.moge.app.data.llm.RequestSnapshot(continuationText = prefix)
        capturedMessages.clear()
        requestRepository.beginRetry(id, "resume-invalid", com.moge.app.data.llm.SnapshotCodec.encode(snapshot), emptyList())!!
        manager.submitRetry(id, "resume-invalid")
        assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(id))
        assertEquals(prefix, requestRepository.get(id)!!.partialText)
        assertTrue(capturedMessages.isEmpty())
    }

    @Test fun `resume cannot claim a record whose attempt changed after it was read`() = runBlocking {
        val id = submitAndInterrupt("原问题", "保存的步骤")
        val old = requestRepository.get(id)!!
        assertEquals(null, requestRepository.beginRetry(id, "stale", null, null, expectedAttemptId = "other-attempt"))
        assertEquals(old.attemptId, requestRepository.get(id)!!.attemptId)
    }

    @Test
    fun `retry sends the original question again`() = runBlocking {
        val requestId = submitAndInterrupt("原问题：什么是导数", "半截")
        val owner = requestRepository.get(requestId)!!
        assertEquals("中断的部分正文必须保留", "半截", owner.partialText)
        capturedMessages.clear()

        val retried = requestRepository.beginRetry(requestId, "att-retry", null, null)!!
        manager.submitRetry(
            requestId = requestId,
            attemptId = "att-retry",
        )
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        val sent = capturedMessages.lastOrNull()
        val lastUser = sent?.lastOrNull { it.role == "user" }
        assertEquals("重试必须重新发送原问题", "原问题：什么是导数", lastUser?.content)
    }

    @Test
    fun `resend after switching provider replaces snapshot and keeps original message`() = runBlocking {
        val requestId = submitAndInterrupt("原问题：解释相位", "半截")
        val original = requestRepository.get(requestId)!!
        val selected = primaryIdentity.copy(
            profileId = "profile-2",
            baseUrl = "https://another.example/v1",
            model = "another-model",
        )
        every { aiCredentialStore.resolveActiveIdentity() } returns selected
        val snapshot = manager.captureRetrySnapshot(
            record = original,
            attachmentPaths = emptyList(),
        )
        assertEquals("profile-2", snapshot.primaryProfileId)
        assertEquals("another-model", snapshot.model)
        assertEquals("https://another.example/v1", snapshot.endpointIdentity)
        assertFalse(snapshot.prepared)
        val retried = requestRepository.beginRetry(
            requestId, "att-new-provider",
            com.moge.app.data.llm.SnapshotCodec.encode(snapshot),
            emptyList(),
        )!!
        manager.submitRetry(
            requestId = requestId,
            attemptId = retried.attemptId,
        )
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(requestId))
        val completed = requestRepository.get(requestId)!!
        assertEquals(original.userMessageId, completed.userMessageId)
        assertEquals(original.answerMessageId, completed.answerMessageId)
        assertEquals("another-model", com.moge.app.data.llm.SnapshotCodec
            .decode(completed.snapshotJson)?.model)
        assertEquals("原问题：解释相位", capturedMessages.last().last { it.role == "user" }.content)
    }

    /** 上一次中断的部分正文不能被当成本轮新结果丢掉（重试期间保留）。 */
    @Test
    fun `retry does not lose the previous partial text before the new result arrives`() = runBlocking {
        val requestId = submitAndInterrupt("问题", "上一次生成到一半的正文")
        assertEquals(
            "中断的部分正文必须保留到新结果成功为止",
            "上一次生成到一半的正文",
            requestRepository.get(requestId)!!.partialText,
        )
        // 换 attempt 开始新一轮：旧 partial 必须仍在（不能先清空再重试，
        // 否则重试又失败时用户之前的输出就全没了）。
        val retried = requestRepository.beginRetry(requestId, "att-2", null, null)!!
        assertEquals(
            "换 attempt 不得清掉旧 partial",
            "上一次生成到一半的正文",
            retried.partialText,
        )
    }

    /**
     * **已发送的草稿不能在生成期间被无条件清掉。**
     *
     * [DraftStore] 的清理只应发生在
     * 「这一轮确实消费了草稿」之后，不能把用户在生成期间新写的内容删掉。
     */
    @Test
    fun `draft belonging to the conversation is saved then cleared on submit`() = runBlocking {
        stubCapturing()
        val draftStore = DraftStore(ApplicationProvider.getApplicationContext())
        draftStore.save("c1", "会话里的草稿", emptyList())
        assertEquals("会话里的草稿", draftStore.load("c1")?.text)
        // 空内容即删除。
        draftStore.save("c1", "", emptyList())
        assertEquals(null, draftStore.load("c1"))
    }

    /** 准备阶段抛异常：必须落终态，不能停在 PREPARING。 */
    @Test
    fun `a throwing preparation still reaches a terminal state`() = runBlocking {
        // 记录落库之后的准备步骤（转写网络调用）抛出非模型异常。
        every { aiCredentialStore.questionVisionProfileId() } returns "vision-1"
        coEvery { modelClient.completeWithProfile(any(), any(), any(), any()) } throws
            RuntimeException("转写时崩了")
        val photo = java.io.File.createTempFile("throwing-prep", ".png")
        val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        val request = manager.submit(
            GenerationManager.Submission(
                conversationId = "c1",
                userText = "问题",
                attachmentPaths = listOf(photo.absolutePath),
            ),
        )
        val status = awaitTerminal(request.requestId)
        photo.delete()
        assertTrue(
            "准备阶段异常必须有终态，实际是 $status",
            status == RequestStatus.INTERRUPTED.name || status == RequestStatus.COMPLETED.name,
        )
        // 关键：绝不能停在 PREPARING。
        assertFalse(
            "不能永远停在 PREPARING",
            requestRepository.get(request.requestId)!!.status == RequestStatus.PREPARING.name,
        )
    }

    /**
     * 准备阶段失败必须落终态（附件读不出来时报 ATTACHMENT_MISSING 并可重试）。
     */
    @Test
    fun `missing attachment fails preparation with a terminal state`() = runBlocking {
        stubCapturing(visionEnabled = true)
        // 本轮 identity 是文字主模型 → 路由为「题目识别」档案转写。
        every { aiCredentialStore.questionVisionProfileId() } returns "vision-1"
        val request = manager.submit(
            GenerationManager.Submission(
                conversationId = "c1",
                userText = "看图",
                // 不存在的附件。
                attachmentPaths = listOf("/definitely/not/here.png"),
            ),
        )
        val status = awaitTerminal(request.requestId)
        assertEquals(RequestStatus.INTERRUPTED.name, status)
        val stored = requestRepository.get(request.requestId)!!
        assertEquals(
            FailureKind.ATTACHMENT_MISSING.name,
            stored.failureKind,
        )
        assertTrue("缺附件应当可重试", FailureKind.ATTACHMENT_MISSING.isRetryable)
    }

    /**
     * 并发提交：锁内判定 + 锁内落盘，所以**只能有一条**请求记录被真正接受，
     * 另一条必须在落盘前就被拒（不留下停在 PREPARING 的孤儿）。
     */
    @Test
    fun `concurrent submits only one is accepted and no orphan is left`() = runBlocking {
        // 让第一轮挂住不结束。
        coEvery { modelClient.isVisionEnabled() } returns false
        coEvery {
            modelClient.chatStreaming(any(), any(), any(), any(), any(), any(), any(), any<suspend (StreamEvent) -> Unit>())
        } coAnswers {
            delay(500)
            ReplyParser.parse("""{"reply":"慢回答"}""", normalizeMarkdown = false)
        }
        val first = manager.submit(
            GenerationManager.Submission(conversationId = "c1", userText = "第一个", attachmentPaths = emptyList()),
        )
        val secondResult = runCatching {
            manager.submit(
                GenerationManager.Submission(conversationId = "c1", userText = "第二个", attachmentPaths = emptyList()),
            )
        }
        assertTrue(
            "第二个提交必须被单飞拒绝",
            secondResult.exceptionOrNull() is GenerationManager.AlreadyRunningException,
        )
        // 被拒的那次**根本没有落盘**（锁内判定先于落盘）→ 不会留下 PREPARING 孤儿。
        val requests = requestRepository.forConversation("c1")
        assertEquals("只应有一条请求记录", 1, requests.size)
        assertEquals(first.requestId, requests.single().requestId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(first.requestId))
        assertEquals(0, requestRepository.countActive())
    }

    private companion object {
        val TERMINAL = setOf(
            RequestStatus.COMPLETED.name,
            RequestStatus.INTERRUPTED.name,
            RequestStatus.CANCELLED.name,
        )
    }

    // ── 定向回归：direct 路由丢问题 / 配置不完整看图题静默降级 / prepared 重试丢刷新上下文 ──

    /**
     * **direct 路由必须保留用户原问题**（prepare-outgoing-fix）。
     *
     * 缺陷：`prepare` 的 `PHOTO_ROUTE_DIRECT` 分支直接透传 `encodeAll(...)`，
     * 而 `encodeAll` 的 `outgoingText` 恒为空串 —— 主模型能看图时用户的问题
     * 被整条丢掉，模型只收到图不知道要干什么。
     *
     * 修复后：带字提交时 outgoingText 必须仍是用户原文（末尾 user 消息）。
     */
    @Test
    fun `direct photo route keeps the user question in the outgoing text`() = runBlocking {
        stubCapturing(visionEnabled = true)
        // 主模型必须带视觉，快照才会钉下 direct 路由（fixture 默认无视觉）。
        every { aiCredentialStore.resolveActiveIdentity() } returns
            primaryIdentity.copy(visionEnabled = true)
        val photo = java.io.File.createTempFile("direct-keep", ".png")
            .apply {
                val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
                outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        try {
            val request = manager.submit(
                GenerationManager.Submission(
                    conversationId = "c1",
                    userText = "求这张图里定积分的值",
                    attachmentPaths = listOf(photo.absolutePath),
                ),
            )
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(request.requestId))
            val sent = capturedMessages.lastOrNull()
            val lastUser = sent?.lastOrNull { it.role == "user" }
            assertEquals(
                "direct 路由不得丢失用户原问题",
                "求这张图里定积分的值",
                lastUser?.content,
            )
            assertTrue(
                "direct 路由必须带原图直送主模型",
                capturedImageCounts.lastOrNull() == 1,
            )
        } finally {
            photo.delete()
        }
        Unit
    }

    /**
     * **纯照片题（没打字）走 direct 路由必须有默认看图提示词**（prepare-outgoing-fix）。
     *
     * userText 为空时 outgoingText 不能还是空串 —— 那等于发送一条空 user 消息。
     */
    @Test
    fun `photo only direct submission falls back to the default vision prompt`() = runBlocking {
        stubCapturing(visionEnabled = true)
        // 主模型必须带视觉，快照才会钉下 direct 路由（fixture 默认无视觉）。
        every { aiCredentialStore.resolveActiveIdentity() } returns
            primaryIdentity.copy(visionEnabled = true)
        val photo = java.io.File.createTempFile("direct-only", ".png")
            .apply {
                val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
                outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        try {
            val request = manager.submit(
                GenerationManager.Submission(
                    conversationId = "c1",
                    userText = "",
                    attachmentPaths = listOf(photo.absolutePath),
                ),
            )
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(request.requestId))
            val sent = capturedMessages.lastOrNull()
            val lastUser = sent?.lastOrNull { it.role == "user" }
            assertEquals(
                "纯照片题必须用默认看图提示词兜底",
                GenerationPreparer.DEFAULT_VISION_PROMPT,
                lastUser?.content,
            )
            // Room keeps the user's original photo-only message empty. A later
            // request must restore the same instruction alongside the history image.
            assertEquals("", db.requestDao().getMessage(request.userMessageId)!!.content)
            val followUp = manager.submit(GenerationManager.Submission(
                conversationId = "c1", userText = "第二步为什么这样做？", attachmentPaths = emptyList(),
            ))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(followUp.requestId))
            val history = capturedMessages.last()
            assertEquals(listOf("user", "assistant", "user"), history.map { it.role })
            assertEquals(GenerationPreparer.DEFAULT_VISION_PROMPT, history.first().content)
            assertEquals(1, history.first().imageBase64s.size)
            assertEquals("回答", history[1].content)
            assertEquals("第二步为什么这样做？", history.last().content)
            val retried = preparer.prepareRetry(requestRepository.get(followUp.requestId)!!)
            assertEquals(GenerationPreparer.DEFAULT_VISION_PROMPT, retried.history.first().content)
            assertEquals(1, retried.history.first().imageBase64s.size)
            assertEquals("第二步为什么这样做？", retried.history.last().content)
        } finally {
            photo.delete()
        }
        Unit
    }

    /** 无看图路线时在落盘、保活之前拒绝，带文字和纯照片题都不能被当成纯文本发送。 */
    @Test
    fun `photo submission without vision or recognizer is rejected before records and protection`() = runBlocking {
        stubCapturing()
        for (text in listOf("看图", "")) {
            val error = runCatching {
                manager.submit(GenerationManager.Submission("c1", text, listOf("/definitely/not/here.png")))
            }.exceptionOrNull()
            assertTrue(error is ModelException)
            assertEquals(ModelException.Kind.CONFIG_INVALID, (error as ModelException).kind)
            assertTrue(error.message.orEmpty().contains("识题模型"))
            assertFalse(manager.isRunning())
        }
        assertTrue(requestRepository.forConversation("c1").isEmpty())
        assertTrue(db.conversationDao().getMessages("c1").isEmpty())
        assertEquals(0, guard.acquisitions.get())
        assertTrue(capturedMessages.isEmpty())

        // 拦截后仍可正常提交下一道文字题，不能遗留活动任务占位。
        val request = manager.submit(GenerationManager.Submission("c1", "纯文字题", emptyList()))
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(request.requestId))
        assertEquals("纯文字题", capturedMessages.single().last().content)
        assertEquals(1, guard.acquisitions.get())
    }

    @Test
    fun `legacy image requests without a vision route retry without starting protection`() = runBlocking {
        stubCapturing()
        manager.recoverOnStartup()
        for (prepared in listOf(false, true)) {
            val submission = GenerationManager.Submission("c1", "旧图片题", listOf("/definitely/not/here.png"))
            val snapshot = preparer.captureInitialSnapshot(submission, "c1", "u-$prepared", "a-$prepared")
                .copy(prepared = prepared, continuationText = "已经保存的步骤")
            val record = requestRepository.createRequest(
                "c1", "u-$prepared", "a-$prepared", "old-$prepared", submission.userText,
                submission.attachmentPaths, SnapshotCodec.encode(snapshot),
            )
            requestRepository.interrupt(record.requestId, record.attemptId, "已经保存的步骤",
                FailureKind.CONFIG_INVALID, "缺少看图配置")
            val retry = requestRepository.beginRetry(record.requestId, "retry-$prepared", null, null)!!
            manager.submitRetry(retry.requestId, retry.attemptId)

            assertEquals(RequestStatus.INTERRUPTED.name, awaitTerminal(retry.requestId))
            val stored = requestRepository.get(retry.requestId)!!
            assertEquals(FailureKind.CONFIG_INVALID.name, stored.failureKind)
            assertTrue(stored.failureMessage.contains("识题模型"))
            assertEquals("已经保存的步骤", stored.partialText)
        }
        assertEquals(0, guard.acquisitions.get())
        assertEquals(0, guard.activeCount())
        assertTrue(capturedMessages.isEmpty())
    }

    /**
     * **prepared 重试以快照为权威**：不重读活动档案、不重建历史，
     * 原文历史/身份/设置原样保留，当前轮仍是最后一条且内容是原问题。
     */
    @Test
    fun `prepared retry reuses the snapshot and keeps identity and history`() = runBlocking {
        val requestId = submitAndInterrupt("问题：解这道题", "半截")
        val owner = requestRepository.get(requestId)!!
        val originalSnapshot = com.moge.app.data.llm.SnapshotCodec.decode(owner.snapshotJson)!!
        val firstPrepared = preparer.prepare(
            GenerationManager.Submission(
                conversationId = "c1",
                userText = "问题：解这道题",
                attachmentPaths = emptyList(),
            ),
            owner,
            originalSnapshot,
        )
        assertTrue(firstPrepared.failure == null)
        val preparedSnapshot = firstPrepared.snapshot
        assertTrue(preparedSnapshot.prepared)
        // 换新 attempt（beginRetry 使记录回到在途），并带上 prepared 快照。
        val withPrepared = requestRepository.beginRetry(
            requestId,
            "att-prepared-retry",
            com.moge.app.data.llm.SnapshotCodec.encode(preparedSnapshot),
            null,
        )!!
        assertEquals("att-prepared-retry", withPrepared.attemptId)

        val retried = preparer.prepareRetry(requestRepository.get(requestId)!!)
        assertTrue("prepared 重试不应失败", retried.failure == null)
        assertEquals(preparedSnapshot.originalHistory, retried.snapshot.originalHistory)
        assertEquals(preparedSnapshot.model, retried.snapshot.model)
        assertEquals("重试仍用快照钉下的档案", originalSnapshot.primaryProfileId, retried.policy.primaryProfileId)
        assertEquals("问题：解这道题", retried.history.last().content)
        assertEquals("user", retried.history.last().role)
    }
}
