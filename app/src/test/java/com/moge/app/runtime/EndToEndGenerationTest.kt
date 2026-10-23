package com.moge.app.runtime

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.credential.AiModelProfile
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiResolvedIdentity
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.MogeDatabase
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.llm.GenerationDiagnostics
import com.moge.app.data.llm.ModelClient
import com.moge.app.data.llm.ModelException
import com.moge.app.data.llm.MonotonicClock
import com.moge.app.data.llm.SnapshotCodec
import com.moge.app.data.llm.withTestModelCatalog
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.parse.ReplyParser
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.RequestStatus
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.solve.SolveViewModel
import com.moge.app.ui.capture.CaptureStore
import com.moge.app.ui.Routes
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * 端到端：真实 [ModelClient] + 真实 [GenerationPreparer] + Room，对着 MockWebServer 跑
 * **流式 → 截断续写 → 完成 → 画图**整条路径（实施文档 P2）。
 *
 * 只替换图表渲染器（JVM 上没有 Canvas），其余全部是生产代码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class EndToEndGenerationTest {

    @Test fun `conflicting page and saved modes are rejected before creating a request or calling the model`() = runBlocking {
        val failure = runCatching {
            manager.submit(GenerationManager.Submission("c1", "画图", emptyList(), conversationMode = ConversationMode.CHAT))
        }.exceptionOrNull()
        assertTrue(failure is ModelException)
        assertEquals(ModelException.Kind.CONFIG_INVALID, (failure as ModelException).kind)
        assertTrue(repository.forConversation("c1").isEmpty())
        assertTrue(db.conversationDao().getMessages("c1").isEmpty())
        assertEquals(0, server.requestCount)
        assertFalse(manager.isRunning())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Config(shadows = [PosixAtomicFileShadow::class])
    @Test fun `switching new page modes keeps display database prompt and figure parsing aligned`() = runBlocking {
        val main = StandardTestDispatcher()
        Dispatchers.setMain(main)
        val collectors = CoroutineScope(SupervisorJob() + main)
        val models = ViewModelStore()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val drafts = DraftStore(context)
        val settings = mockk<SettingsRepository> {
            coEvery { current() } returns UserSettings()
            every { this@mockk.settings } returns kotlinx.coroutines.flow.flowOf(UserSettings())
        }
        fun newPage(mode: ConversationMode?): SolveViewModel = SolveViewModel(
            SavedStateHandle(mapOf(Routes.ARG_DRAFT_MODE to mode?.name)), manager, repository,
            ConversationRepository(db.conversationDao(), Dispatchers.Unconfined),
            drafts, settings, CaptureStore(context), mockk(relaxed = true), mockk(relaxed = true)).also {
            models.put(UUID.randomUUID().toString(), it)
            collectors.launch { it.uiState.collect { } }
        }
        suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) {
            while (true) {
                main.scheduler.runCurrent()
                if (condition()) break
                delay(10)
            }
        }
        try {
            drafts.clear(null)
            // Both directions, including switching away from a restored CHAT draft.
            val cases = listOf(null to ConversationMode.CHAT, null to ConversationMode.SOLVE,
                ConversationMode.SOLVE to ConversationMode.SOLVE)
            for ((requested, target) in cases) {
                if (requested != null) drafts.persist(drafts.reserveSave(null, "", emptyList(), conversationMode = ConversationMode.CHAT))
                val page = newPage(requested)
                waitFor { page.uiState.value.modeSelectable }
                if (requested == null) {
                    page.selectConversationMode(if (target == ConversationMode.SOLVE) ConversationMode.CHAT else ConversationMode.SOLVE)
                    page.selectConversationMode(target)
                } else assertEquals(target, page.uiState.value.conversationMode)
                page.onInputChange("画出 y=x 的图像")
                server.enqueue(sse(if (target == ConversationMode.SOLVE)
                    """{"conversation_title":"一次函数图像","reply":"见图。\\n\\n[[FIGURE:1]]","plots":[{"title":"y=x","series":[{"expr":"x"}]}]}"""
                    else "对话正文", "stop"))
                // Do not wait for UI recomposition between the last switch and sending.
                page.send()
                waitFor { page.uiState.value.conversationId != null && !page.uiState.value.submitting }
                val id = page.uiState.value.conversationId!!
                val request = repository.forConversation(id).single()
                assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(request.requestId).status)
                waitFor { !manager.isRunning() }
                assertEquals(target, page.uiState.value.conversationMode)
                assertEquals(target.name, db.conversationDao().getConversation(id)!!.conversationMode)
                val snapshot = SnapshotCodec.decode(repository.get(request.requestId)!!.snapshotJson)!!
                assertEquals(target.name, snapshot.conversationMode)
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                assertEquals("A new page must not inherit the previous conversation's history", 2, body["messages"]!!.jsonArray.size)
                val system = body["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content
                assertEquals(snapshot.prompt!!.stableSystem, system)
                val answer = db.requestDao().getMessage(request.answerMessageId)!!
                if (target == ConversationMode.SOLVE) {
                    assertTrue(system.startsWith(ModelClient.SYSTEM_PROMPT))
                    assertEquals("一次函数图像", db.conversationDao().getConversation(id)!!.title)
                    assertEquals(listOf("/figures/0.png"), RequestRepository.decodePathList(answer.imagePaths))
                } else {
                    assertFalse(system.contains(ModelClient.SYSTEM_PROMPT))
                    assertEquals("对话正文", answer.content)
                    // Leave a CHAT draft to exercise mode restoration on the next page.
                    drafts.persist(drafts.reserveSave(null, "", emptyList(), conversationMode = ConversationMode.CHAT))
                }
            }
        } finally {
            collectors.cancel()
            models.clear()
            main.scheduler.runCurrent()
            Dispatchers.resetMain()
        }
    }

    @Test fun `captionless supplemental photo keeps context and nonempty text in chat completions`() = runBlocking {
        verifySupplementalPhoto(AiApiProtocol.CHAT_COMPLETIONS)
    }

    @Test fun `captionless supplemental photo keeps context and nonempty text in responses`() = runBlocking {
        verifySupplementalPhoto(AiApiProtocol.RESPONSES)
    }

    private suspend fun verifySupplementalPhoto(protocol: AiApiProtocol) {
        val identity = credentials.resolveActiveIdentity()!!.copy(visionEnabled = true, apiProtocol = protocol)
        every { credentials.resolveActiveIdentity() } returns identity
        every { credentials.resolveIdentityFor("p1") } returns identity
        fun enqueueReply(text: String) {
            server.enqueue(if (protocol == AiApiProtocol.CHAT_COMPLETIONS) sse(text, "stop")
                else MockResponse().setHeader("Content-Type", "application/json").setBody(buildJsonObject {
                    put("output", buildJsonArray { add(buildJsonObject {
                        put("type", "message"); put("role", "assistant")
                        put("content", buildJsonArray { add(buildJsonObject {
                            put("type", "output_text"); put("text", text)
                        }) })
                    }) })
                }.toString()))
        }
        val photo = java.io.File.createTempFile("supplemental-constellation", ".png")
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val firstReply = "请补发星座图，以确认第3问的判决域。"
            enqueueReply("""{"reply":"$firstReply"}""")
            val first = manager.submit(GenerationManager.Submission("c1", "", listOf(photo.absolutePath)))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(first.requestId).status)
            withTimeout(3_000) { while (manager.isRunning()) delay(10) }
            enqueueReply("""{"reply":"根据补图，更正第3问的边界。"}""")
            val second = manager.submit(GenerationManager.Submission("c1", "", listOf(photo.absolutePath),
                solveMode = com.moge.app.domain.SolveMode.CHECK_WORK))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(second.requestId).status)
            withTimeout(3_000) { while (manager.isRunning()) delay(10) }
            // A display/content refresh must not rewrite a message already sent as model history.
            db.requestDao().updateMessageContent(first.answerMessageId, "界面后来重新排版的回答")
            enqueueReply("""{"reply":"右上角标的是第二个星座点。"}""")
            val third = manager.submit(GenerationManager.Submission("c1", "右上角标的是什么？", emptyList()))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(third.requestId).status)
            withTimeout(3_000) { while (manager.isRunning()) delay(10) }

            val bodies = List(3) { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
            fun messages(index: Int) = bodies[index][if (protocol == AiApiProtocol.RESPONSES) "input" else "messages"]!!
                .jsonArray.map { it.jsonObject }
            fun imageText(message: kotlinx.serialization.json.JsonObject) =
                message["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content.substringAfter("\n\n【用户消息】\n")
            for (index in 1..2) {
                val previous = messages(index - 1)
                assertEquals("$protocol must append to the exact prior input, including turn instructions and image bytes",
                    previous, messages(index).take(previous.size))
            }
            if (protocol == AiApiProtocol.RESPONSES) {
                assertEquals(bodies[0]["instructions"], bodies[1]["instructions"])
                assertEquals(bodies[1]["instructions"], bodies[2]["instructions"])
            }
            val secondMessages = messages(1)
            val exchange = secondMessages.filter { it["role"]!!.jsonPrimitive.content != "system" }
            assertEquals(listOf("user", "assistant", "user"), exchange.map { it["role"]!!.jsonPrimitive.content })
            assertEquals(PhotoTurnPrompt.INITIAL_QUESTION, imageText(exchange[0]))
            assertTrue(exchange[1]["content"]!!.jsonPrimitive.content.contains(firstReply))
            assertEquals(PhotoTurnPrompt.FOLLOW_UP, imageText(exchange[2]))
            val blocks = exchange[2]["content"]!!.jsonArray.map { it.jsonObject }
            assertEquals(if (protocol == AiApiProtocol.RESPONSES) "input_text" else "text", blocks[0]["type"]!!.jsonPrimitive.content)
            assertTrue(blocks[0]["text"]!!.jsonPrimitive.content.isNotBlank())
            assertEquals(if (protocol == AiApiProtocol.RESPONSES) "input_image" else "image_url", blocks[1]["type"]!!.jsonPrimitive.content)
            assertEquals(exchange.last(), secondMessages.last())
            // Rebuilding history must not relabel the initial image as a follow-up,
            // or the supplemental image as a request for a complete new solution.
            val historicalUsers = messages(2).filter { it["role"]!!.jsonPrimitive.content == "user" }
            assertEquals(PhotoTurnPrompt.INITIAL_QUESTION, imageText(historicalUsers[0]))
            assertEquals(PhotoTurnPrompt.FOLLOW_UP, imageText(historicalUsers[1]))
            assertEquals("右上角标的是什么？", historicalUsers[2]["content"]!!.jsonPrimitive.content.substringAfter("\n\n【用户消息】\n"))
            assertEquals("", db.requestDao().getMessage(second.userMessageId)!!.content)
        } finally { photo.delete() }
    }

    @Test fun `chat resume restores partial prose without adding solving instructions`() = runBlocking {
        db.conversationDao().insertConversation(ConversationEntity(id = "chat", title = "续写", conversationMode = "CHAT"))
        val prefix = "我会先从时间安排说起。"
        val delta = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("content", prefix) })
        }) }) }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $delta\n\n"))
        val record = manager.submit(GenerationManager.Submission("chat", "帮我规划一天", emptyList()))
        val interrupted = awaitTerminal(record.requestId)
        assertEquals(RequestStatus.INTERRUPTED.name, interrupted.status)
        assertEquals(prefix, interrupted.partialText)
        withTimeout(3_000) { while (manager.isRunning()) delay(10) }
        val initial = manager.captureRetrySnapshot(interrupted).copy(continuationText = prefix)
        assertEquals("CHAT", initial.conversationMode)
        val retry = repository.beginRetry(record.requestId, "chat-resume",
            com.moge.app.data.llm.SnapshotCodec.encode(initial), null)!!
        server.enqueue(sse("上午专注工作，下午安排休息。", "stop"))
        manager.submitRetry(retry.requestId, retry.attemptId)
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(record.requestId).status)
        assertEquals(prefix + "上午专注工作，下午安排休息。", db.requestDao().getMessage(record.answerMessageId)!!.content)
        server.takeRequest()
        val payload = server.takeRequest().body.readUtf8()
        assertFalse(payload.contains("完整 JSON 对象"))
        assertFalse(payload.contains("解题模式"))
        assertEquals(2, db.conversationDao().getMessages("chat").size)
    }

    @Test fun `chat images go directly to primary vision model and followups use general image context`() = runBlocking {
        db.conversationDao().insertConversation(ConversationEntity(id = "chat", title = "相片", conversationMode = "CHAT"))
        val identity = credentials.resolveActiveIdentity()!!.copy(visionEnabled = true)
        every { credentials.resolveActiveIdentity() } returns identity
        every { credentials.resolveIdentityFor("p1") } returns identity
        val photo = java.io.File.createTempFile("chat-image", ".png")
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            server.enqueue(sse("这是一张风景照。", "stop"))
            val first = manager.submit(GenerationManager.Submission("chat", "", listOf(photo.absolutePath)))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(first.requestId).status)
            withTimeout(3_000) { while (manager.isRunning()) delay(10) }
            server.enqueue(sse("可以提高亮度。", "stop"))
            val second = manager.submit(GenerationManager.Submission("chat", "怎么调整亮度", emptyList()))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(second.requestId).status)
            val initialPayload = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val followupPayload = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            for (body in listOf(initialPayload, followupPayload)) {
                val parts = body["messages"]!!.jsonArray.map { it.jsonObject }
                    .first { it["role"]!!.jsonPrimitive.content == "user" }["content"]!!.jsonArray
                assertEquals("请描述这些图片。",
                    parts.first().jsonObject["text"]!!.jsonPrimitive.content.substringAfter("\n\n【用户消息】\n"))
                assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
                assertFalse(body.toString().contains("完整推导过程"))
            }
            assertEquals("", db.requestDao().getMessage(first.userMessageId)!!.transcript)
            assertEquals(2, server.requestCount)
        } finally { photo.delete() }
    }

    @Test fun `ordinary chat preserves literal output and only includes its own conversation history`() = runBlocking {
        db.conversationDao().insertConversation(ConversationEntity(id = "chat", title = "周末安排", conversationMode = "CHAT"))
        db.conversationDao().insertConversation(ConversationEntity(id = "other", title = "另一个对话", conversationMode = "CHAT"))
        val literal = """{"reply":"JSON 示例保留原样","answer":"普通字段"}"""
        server.enqueue(sse(literal, "stop", chunk = 3))
        val first = manager.submit(GenerationManager.Submission("chat", "给我一个 JSON 示例", emptyList()))
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(first.requestId).status)
        assertEquals(literal, db.requestDao().getMessage(first.answerMessageId)!!.content)
        assertEquals("", db.requestDao().getMessage(first.answerMessageId)!!.finalAnswer)
        assertEquals("CHAT", com.moge.app.data.llm.SnapshotCodec.decode(first.snapshotJson)!!.conversationMode)
        withTimeout(3_000) { while (manager.isRunning()) delay(10) }
        server.enqueue(sse("当然可以。", "stop"))
        val followup = manager.submit(GenerationManager.Submission("chat", "解释它", emptyList()))
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(followup.requestId).status)
        withTimeout(3_000) { while (manager.isRunning()) delay(10) }
        server.enqueue(sse("你好。", "stop"))
        val other = manager.submit(GenerationManager.Submission("other", "你好", emptyList()))
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(other.requestId).status)
        val firstPayload = server.takeRequest().body.readUtf8()
        val secondPayload = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val otherPayload = server.takeRequest().body.readUtf8()
        assertFalse(firstPayload.contains("拍题解题老师"))
        assertFalse(firstPayload.contains("解题模式"))
        assertFalse(firstPayload.contains("只输出完整 JSON"))
        assertFalse(firstPayload.contains("response_format"))
        val history = secondPayload["messages"]!!.jsonArray.map { it.jsonObject }
        assertTrue(history.any { it["role"]!!.jsonPrimitive.content == "assistant" && it["content"]!!.jsonPrimitive.content == literal })
        assertFalse(otherPayload.contains("JSON 示例保留原样"))
        assertFalse(otherPayload.contains("给我一个 JSON 示例"))
    }

    @Test fun `ordinary chat continuation keeps prose and requests Markdown instead of solving JSON`() = runBlocking {
        db.conversationDao().insertConversation(ConversationEntity(id = "chat", title = "故事", conversationMode = "CHAT"))
        val prefix = "清晨的风穿过窗户。".repeat(32)
        server.enqueue(sse(prefix, "length"))
        server.enqueue(sse("她合上书，走进阳光。", "stop"))
        val record = manager.submit(GenerationManager.Submission("chat", "写个故事", emptyList()))
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(record.requestId).status)
        assertEquals(prefix + "她合上书，走进阳光。", db.requestDao().getMessage(record.answerMessageId)!!.content)
        assertEquals(2, server.requestCount)
        server.takeRequest()
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val last = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        assertFalse(last.contains("JSON"))
        assertTrue(last.contains("直接输出正文"))
    }

    @Test fun `ordinary chat never uses the question recognizer for a text-only primary model`() = runBlocking {
        db.conversationDao().insertConversation(ConversationEntity(id = "chat", title = "图片", conversationMode = "CHAT"))
        every { credentials.questionVisionProfileId() } returns "vision"
        every { credentials.resolveIdentityFor("vision") } returns credentials.resolveActiveIdentity()!!.copy(profileId = "vision", visionEnabled = true)
        val error = runCatching {
            manager.submit(GenerationManager.Submission("chat", "这张照片好看吗", listOf("/photo.png")))
        }.exceptionOrNull()
        assertTrue(error is ModelException)
        assertTrue(error!!.message!!.contains("切换到支持看图"))
        assertFalse(error.message!!.contains("识题"))
        assertEquals(0, server.requestCount)
        assertTrue(db.conversationDao().getMessages("chat").isEmpty())
    }

    private lateinit var server: MockWebServer
    private lateinit var db: MogeDatabase
    private lateinit var repository: RequestRepository
    private lateinit var manager: GenerationManager
    private lateinit var credentials: AiCredentialStore
    private val renderedBatches = mutableListOf<List<ReplyFigure>>()

    private val renderer = object : FigureRenderer {
        override suspend fun render(figures: List<ReplyFigure>): List<String> {
            renderedBatches += figures
            return figures.mapIndexed { i, f -> if (f is ReplyFigure.Missing) "" else "/figures/$i.png" }
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        val baseUrl = server.url("/v1").toString().trimEnd('/')
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MogeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RequestRepository(db.requestDao(), Dispatchers.Unconfined)
        val settings = mockk<SettingsRepository> {
            coEvery { current() } returns UserSettings()
        }
        val profile = AiModelProfile(
            "p1", "测试模型", baseUrl, "test-model", false,
            AiSearchProtocol.OFF, AiReasoningEffort.HIGH, true,
        )
        val identity = AiResolvedIdentity(
            profileId = "p1", baseUrl = baseUrl, model = "test-model", apiKey = "k",
            visionEnabled = false, searchProtocol = AiSearchProtocol.OFF,
            reasoningEffort = AiReasoningEffort.HIGH,
        )
        credentials = mockk<AiCredentialStore>(relaxed = true) {
            every { activeProfile() } returns profile
            every { resolveActiveIdentity() } returns identity
            every { resolveIdentityFor("p1") } returns identity
            every { questionVisionProfileId() } returns null
            every { profiles() } returns listOf(profile)
        }
        val modelClient = ModelClient(settings, credentials, Dispatchers.Unconfined).withTestModelCatalog()
        val preparer = GenerationPreparer(modelClient, db.conversationDao(), settings, credentials, repository,
            com.moge.app.data.llm.VisionInputImageStore(context))
        manager = GenerationManager(
            modelClient = modelClient,
            requestRepository = repository,
            conversationDao = db.conversationDao(),
            credentialStore = credentials,
            diagnostics = GenerationDiagnostics(),
            guard = NoopGuard(),
            monotonicClock = MonotonicClock.SYSTEM,
            finalizer = GenerationFinalizer(repository, renderer),
            preparer = preparer,
            figureCompleter = GenerationFigureCompleter(renderer, modelClient),
        )
        runBlocking { db.conversationDao().insertConversation(ConversationEntity(id = "c1", title = "新题")) }
    }

    @After
    fun tearDown() {
        db.close()
        server.shutdown()
    }

    private class NoopGuard : GenerationGuard {
        override fun acquire(requestId: String, conversationId: String?) = requestId
        override fun release(token: String) = Unit
        override fun activeCount() = 0
    }

    /** 把 [content] 切成小片作为 Chat Completions SSE 发出，最后带 finish_reason 与 usage。 */
    private fun sse(content: String, finish: String, chunk: Int = 16): MockResponse {
        val body = StringBuilder()
        content.chunked(chunk).forEach { piece ->
            val delta = buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject { put("delta", buildJsonObject { put("content", piece) }) })
                })
            }
            body.append("data: ").append(delta).append("\n\n")
        }
        body.append("data: {\"choices\":[{\"finish_reason\":\"").append(finish).append("\"}],")
            .append("\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":40}}\n\n")
        body.append("data: [DONE]\n\n")
        return MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body.toString())
    }

    private suspend fun awaitTerminal(requestId: String): RequestEntity {
        repeat(200) {
            val record = repository.get(requestId)
            if (record != null && RequestStatus.fromName(record.status)?.isInFlight == false) return record
            delay(25)
        }
        error("请求 $requestId 没有在超时内落终态")
    }

    @Test
    fun `unsupported photo sends no HTTP request and can be resent after enabling vision`() = runBlocking {
        val photo = java.io.File.createTempFile("vision-config", ".png")
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val submission = GenerationManager.Submission("c1", "解答图中的题目", listOf(photo.absolutePath))
            val error = runCatching { manager.submit(submission) }.exceptionOrNull()
            assertTrue(error is ModelException)
            assertEquals(ModelException.Kind.CONFIG_INVALID, (error as ModelException).kind)
            assertEquals(0, server.requestCount)
            assertTrue(repository.forConversation("c1").isEmpty())
            assertTrue(db.conversationDao().getMessages("c1").isEmpty())
            assertFalse(manager.isRunning())

            val visionIdentity = credentials.resolveActiveIdentity()!!.copy(visionEnabled = true)
            every { credentials.resolveActiveIdentity() } returns visionIdentity
            every { credentials.resolveIdentityFor("p1") } returns visionIdentity
            server.enqueue(sse("{\"reply\":\"看图回答成功\"}", "stop"))
            val record = manager.submit(submission)
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(record.requestId).status)
            assertEquals(1, server.requestCount)
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val parts = body["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
            assertEquals(submission.userText,
                parts.first().jsonObject["text"]!!.jsonPrimitive.content.substringAfter("\n\n【用户消息】\n"))
            assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("看图回答成功", db.requestDao().getMessage(record.answerMessageId)!!.content)
        } finally { photo.delete() }
    }

    @Test
    fun `photo only first question can be followed up on a backend rejecting blank text blocks`() = runBlocking {
        val baseUrl = server.url("/v1").toString().trimEnd('/')
        val visionIdentity = AiResolvedIdentity("p1", baseUrl, "test-model", "k", true,
            AiSearchProtocol.OFF, AiReasoningEffort.HIGH)
        every { credentials.resolveActiveIdentity() } returns visionIdentity
        every { credentials.resolveIdentityFor("p1") } returns visionIdentity
        every { credentials.activeProfile() } returns AiModelProfile("p1", "测试模型", baseUrl, "test-model", true,
            AiSearchProtocol.OFF, AiReasoningEffort.HIGH, true)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val messages = Json.parseToJsonElement(request.body.clone().readUtf8()).jsonObject["messages"]!!.jsonArray
                val hasBlankText = messages.any { message ->
                    (message.jsonObject["content"] as? kotlinx.serialization.json.JsonArray)?.any { element ->
                        val part = element.jsonObject
                        part["type"]!!.jsonPrimitive.content == "text" && part["text"]!!.jsonPrimitive.content.isBlank()
                    } == true
                }
                if (hasBlankText) return MockResponse().setResponseCode(400)
                    .setBody("{\"error\":{\"message\":\"empty text block rejected\"}}")
                return sse("{\"reply\":\"按原图题目继续解答\"}", "stop")
            }
        }
        val photo = java.io.File.createTempFile("photo-only-followup", ".png")
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
            photo.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val first = manager.submit(GenerationManager.Submission("c1", "", listOf(photo.absolutePath)))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(first.requestId).status)
            assertEquals("", db.requestDao().getMessage(first.userMessageId)!!.content)
            withTimeout(3_000) { while (manager.isRunning()) delay(10) }
            val second = manager.submit(GenerationManager.Submission("c1", "解释第二步", emptyList()))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(second.requestId).status)
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject["messages"]!!.jsonArray
                .map { it.jsonObject }.filter { it["role"]!!.jsonPrimitive.content != "system" }
            assertEquals(listOf("user", "assistant", "user"), sent.map { it["role"]!!.jsonPrimitive.content })
            val parts = sent.first()["content"]!!.jsonArray
            assertEquals(GenerationPreparer.DEFAULT_VISION_PROMPT,
                parts.first().jsonObject["text"]!!.jsonPrimitive.content.substringAfter("\n\n【用户消息】\n"))
            assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(sent[1]["content"]!!.jsonPrimitive.content.contains("按原图题目继续解答"))
            assertEquals("解释第二步", sent.last()["content"]!!.jsonPrimitive.content.substringAfter("\n\n【用户消息】\n"))
        } finally { photo.delete() }
    }

    @Test fun `missing QAM receiver is completed with a second pinned request without rewriting the reply`() = runBlocking {
        val body = requireNotNull(javaClass.getResource("/fixtures/qam-missing-demodulator.md")).readText()
        val modulator = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
            "/diagrams/communication/shared-carrier-qam.json")).readText())
        val receiver = Json.parseToJsonElement(requireNotNull(javaClass.getResource(
            "/diagrams/communication/shared-carrier-qam-receiver.json")).readText())
        val first = buildJsonObject {
            put("conversation_title", "PCM 与 64QAM")
            put("answer", "原结论")
            put("reply", body)
            put("plots", buildJsonArray { add(Json.parseToJsonElement("""{"title":"功率谱","series":[{"expr":"x"}]}""")) })
            put("diagrams", buildJsonArray { add(modulator); add(kotlinx.serialization.json.JsonNull) })
        }
        val repair = buildJsonObject { put("diagrams", buildJsonArray { add(receiver) }) }
        server.enqueue(sse(first.toString(), "stop"))
        server.enqueue(sse(repair.toString(), "stop"))
        val diagnostic = kotlinx.coroutines.CompletableDeferred<GenerationManager.GenerationDiagnostic>()
        manager.diagnosticObserver = { diagnostic.complete(it) }
        val record = manager.submit(GenerationManager.Submission("c1", "帮我批改 PCM 与 64QAM", emptyList()))
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(record.requestId).status)
        assertEquals(2, server.requestCount)
        assertEquals(2, withTimeout(5_000) { diagnostic.await() }.httpCalls)
        val initialRequest = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val repairRequest = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(initialRequest["model"], repairRequest["model"])
        val repairText = repairRequest["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonPrimitive.content
        assertTrue(repairText.contains("[[FIGURE:3]]"))
        val answer = db.requestDao().getMessage(record.answerMessageId)!!
        assertEquals(ReplyParser.parse(first.toString(), normalizeMarkdown = false).reply, answer.content)
        assertEquals("原结论", answer.finalAnswer)
        assertEquals(listOf("/figures/0.png", "/figures/1.png", "/figures/0.png"), RequestRepository.decodePathList(answer.imagePaths))
        assertEquals(2, renderedBatches.size)
        assertTrue(renderedBatches.last().single() is ReplyFigure.Diagram)
        assertEquals("PCM 与 64QAM", db.conversationDao().getConversation("c1")!!.title)
    }

    @Test
    fun `stream then continuation then completion then figures`() = runBlocking {
        // 第 1 轮：被 length 截断，正文里引用第 1 张图，带一张函数图与学科/标题。
        val round1 = """{"conversation_title":"双纽线面积","subject":"MATH","reply":"第一段：先看图像。\n\n[[FIGURE:1]]\n\n由对称性只需算第一象限，接下来展开积分步骤与换元的细节说明，""" +
            """"plots":[{"title":"双纽线","series":[{"expr":"x"}]}]}"""
        // 第 2 轮：续写收尾，又引用了它自己的第 1 张图（应平移为全局第 2 张）。
        val round2 = """{"reply":"所以面积等于 1，最后对照示意图检查结果。\n\n[[FIGURE:1]]","diagrams":[{"title":"示意","nodes":[{"id":"a","label":"输入"},{"id":"b","label":"输出"}],"edges":[{"from":"a","to":"b"}]}]}"""
        server.enqueue(sse(round1, finish = "length"))
        server.enqueue(sse(round2, finish = "stop"))

        val record = manager.submit(
            GenerationManager.Submission(
                conversationId = "c1",
                userText = "求双纽线所围面积",
                attachmentPaths = emptyList(),
            ),
        )
        val done = awaitTerminal(record.requestId)

        assertEquals(RequestStatus.COMPLETED.name, done.status)
        assertEquals("两轮：首轮 + 一次续写", 2, server.requestCount)
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val second = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val firstUser = first["messages"]!!.jsonArray.last().jsonObject["content"].toString()
        assertTrue("首轮最后一条是本轮问题", firstUser.contains("求双纽线所围面积"))
        val secondMessages = second["messages"]!!.jsonArray
        assertTrue(
            "续写请求必须带上已生成的部分作为助手消息",
            secondMessages.any { it.jsonObject["role"]?.jsonPrimitive?.content == "assistant" },
        )

        val answer = db.requestDao().getMessage(record.answerMessageId)!!
        assertTrue(answer.content.contains("第一段"))
        assertTrue(answer.content.contains("所以面积等于 1"))
        assertTrue("首轮图锚点保持 1", answer.content.contains("[[FIGURE:1]]"))
        assertTrue("续写轮图锚点平移到 2", answer.content.contains("[[FIGURE:2]]"))
        assertEquals("两轮的图一次性交给渲染器", 1, renderedBatches.size)
        assertEquals(2, renderedBatches.single().size)
        assertTrue(renderedBatches.single()[0] is ReplyFigure.Plot)
        assertTrue(renderedBatches.single()[1] is ReplyFigure.Diagram)
        assertEquals(
            listOf("/figures/0.png", "/figures/1.png"),
            RequestRepository.decodePathList(answer.imagePaths),
        )
        assertEquals("测试模型", answer.modelLabel)
        assertTrue("usage 已落库", answer.usageJson.contains("inputTokens"))

        val conversation = db.conversationDao().getConversation("c1")!!
        assertEquals("双纽线面积", conversation.title)
    }

    @Test
    fun `three figure turns each keep their own slots after database reload`() = runBlocking {
        repeat(3) { index ->
            val figureNumber = index + 1
            val body = """{"answer":"第 $figureNumber 次结论","reply":"本次图形\n[[FIGURE:$figureNumber]]\n完整说明","plots":[{"title":"图 $figureNumber","series":[{"expr":"x+$index"}]}]}"""
            server.enqueue(sse(body, finish = "stop"))
            val record = manager.submit(GenerationManager.Submission("c1", "再画第 $figureNumber 张图", emptyList()))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(record.requestId).status)
            val answer = db.conversationDao().getMessages("c1").first { it.id == record.answerMessageId }
            assertTrue(answer.content.contains("[[FIGURE:1]]"))
            assertEquals(listOf("/figures/0.png"), RequestRepository.decodePathList(answer.imagePaths))
            assertEquals(record.userMessageId, answer.replyToMessageId)
            withTimeout(5_000) { while (manager.active.value.isRunning) delay(10) }
        }
        val answers = db.conversationDao().getMessages("c1").filter { it.role == "assistant" }
        assertEquals(3, answers.size)
        assertEquals(listOf("第 1 次结论", "第 2 次结论", "第 3 次结论"), answers.map { it.finalAnswer })
        assertEquals(3, renderedBatches.size)
        assertTrue(renderedBatches.all { it.size == 1 })
        val requests = List(3) { server.takeRequest().body.readUtf8() }
        val secondHistory = Json.parseToJsonElement(requests[1]).jsonObject["messages"]!!.jsonArray
            .map { it.jsonObject }.filter { it["role"]!!.jsonPrimitive.content == "assistant" }
        assertTrue(secondHistory.isNotEmpty())
        assertTrue(secondHistory.all { !it["content"]!!.jsonPrimitive.content.contains("[[FIGURE:") })
    }

    @Test
    fun `answer summary and question association survive persistence`() = runBlocking {
        server.enqueue(sse("""{"answer":"最终结论。","reply":"一个完整的回答。"}""", finish = "stop"))
        val record = manager.submit(
            GenerationManager.Submission(conversationId = "c1", userText = "问题", attachmentPaths = emptyList()),
        )
        assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(record.requestId).status)
        val answer = db.conversationDao().getMessages("c1").first { it.id == record.answerMessageId }
        assertEquals("最终结论。", answer.finalAnswer)
        assertEquals(record.userMessageId, answer.replyToMessageId)
        assertTrue("没有图就不调用渲染器", renderedBatches.all { it.isEmpty() })
    }

    @Test
    fun `diagram followed by screenshot style cardioid restores second turn figure and cleans raw member`() = runBlocking {
        val first = """{"answer":"结构见 [[FIGURE:1]]。","reply":"SSB 解调框图。\n[[FIGURE:1]]","diagrams":[{"nodes":[{"id":"a","label":"输入"},{"id":"b","label":"输出"}],"edges":[{"from":"a","to":"b"}]}]}"""
        val second = requireNotNull(javaClass.getResource("/fixtures/cardioid-fenced-members.md")).readText()
        server.enqueue(sse(first, "stop"))
        server.enqueue(sse(second, "stop"))
        for ((index, text) in listOf("画 SSB 解调框图", "再画一个心形线").withIndex()) {
            val record = manager.submit(GenerationManager.Submission("c1", text, emptyList()))
            assertEquals(RequestStatus.COMPLETED.name, awaitTerminal(record.requestId).status)
            withTimeout(5_000) { while (manager.active.value.isRunning) delay(10) }
            val answer = db.requestDao().getMessage(record.answerMessageId)!!
            assertFalse(answer.content.contains("\"plots\""))
            assertEquals(listOf("/figures/0.png"), RequestRepository.decodePathList(answer.imagePaths))
            if (index == 1) assertTrue(answer.content.contains("作图方法"))
        }
        assertTrue(renderedBatches[0].single() is ReplyFigure.Diagram)
        val cardioid = (renderedBatches[1].single() as ReplyFigure.Plot).spec
        assertEquals(25, cardioid.series.single().points!!.size)
        val requests = List(2) { Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject }
        assertFalse(requests[1]["messages"]!!.jsonArray.filter { it.jsonObject["role"]!!.jsonPrimitive.content == "assistant" }
            .any { it.jsonObject["content"]!!.jsonPrimitive.content.contains("[[FIGURE:" ) })
    }
}
