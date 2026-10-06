package com.moge.app.runtime

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.credential.AiModelProfile
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiResolvedIdentity
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.MogeDatabase
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.llm.GenerationDiagnostics
import com.moge.app.data.llm.ModelClient
import com.moge.app.data.llm.ModelException
import com.moge.app.data.llm.MonotonicClock
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.RequestStatus
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
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

/**
 * 端到端：真实 [ModelClient] + 真实 [GenerationPreparer] + Room，对着 MockWebServer 跑
 * **流式 → 截断续写 → 完成 → 画图**整条路径（实施文档 P2）。
 *
 * 只替换图表渲染器（JVM 上没有 Canvas），其余全部是生产代码。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class EndToEndGenerationTest {

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
            coEvery { current() } returns UserSettings(maxContinuations = 2)
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
        val modelClient = ModelClient(settings, credentials, Dispatchers.Unconfined)
        val preparer = GenerationPreparer(modelClient, db.conversationDao(), settings, credentials)
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
            assertEquals(submission.userText, parts.first().jsonObject["text"]!!.jsonPrimitive.content)
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
            assertEquals(GenerationPreparer.DEFAULT_VISION_PROMPT, parts.first().jsonObject["text"]!!.jsonPrimitive.content)
            assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
            assertTrue(sent[1]["content"]!!.jsonPrimitive.content.contains("按原图题目继续解答"))
            assertEquals("解释第二步", sent.last()["content"]!!.jsonPrimitive.content)
        } finally { photo.delete() }
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
