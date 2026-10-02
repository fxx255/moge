package com.moge.app.data.llm

import android.app.Application
import com.moge.app.data.prefs.UserSettings
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.credential.AiModelProfile
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiResolvedIdentity
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.data.parse.ParsedReply
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DeepSeek Responses 协议的 SSE 验收：**真实客户端 + 拦截的 HTTP 响应**。
 *
 * 重点：事件边界、event 名与 JSON type 的别名、失败/不完整终态，
 * 以及「已有正文但 EOF 无终态 ⇒ 中断」不可回退重发。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ResponsesSseTest {

    private val requests = mutableListOf<String>()
    private val bodies = mutableListOf<String>()

    private fun client(vararg responses: Pair<Int, String>): ModelClient {
        val prefs = mockk<SettingsRepository>()
        coEvery { prefs.current() } returns UserSettings(webSearchEnabled = true)
        val credentials = mockk<AiCredentialStore>()
        every { credentials.activeProfile() } returns AiModelProfile(
            "p", "p", "https://api.deepseek.com/v1", "deepseek-chat", true,
            AiSearchProtocol.RESPONSES, AiReasoningEffort.MEDIUM, true,
        )
        every { credentials.resolveActiveIdentity() } returns AiResolvedIdentity(
            profileId = "p",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-chat",
            apiKey = "test-key",
            visionEnabled = true,
            searchProtocol = AiSearchProtocol.RESPONSES,
            reasoningEffort = AiReasoningEffort.MEDIUM,
        )
        reqIndex = 0
        val list = responses.toList()
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            requests += buffer.readUtf8()
            bodies += ""
            val (code, body) = list[reqIndex++]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("test").body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return ModelClient(prefs, credentials, Dispatchers.Unconfined).also {
            ModelClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(it, transport)
        }
    }

    private var reqIndex = 0

    /** 写一个 chunked 编码的 body 块（不发 0 长度结束块）；长度必须是**字节数**。 */
    private fun writeChunk(out: java.io.OutputStream, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        out.write(bytes.size.toString(16).toByteArray(Charsets.US_ASCII))
        out.write("\r\n".toByteArray())
        out.write(bytes)
        out.write("\r\n".toByteArray())
        out.flush()
    }

    private fun respEvent(type: String, extra: String = ""): String =
        """data: {"type":"$type"$extra}

"""

    @Test
    fun `event name is used when json has no type`() = runBlocking {
        val body = "event: response.output_text.delta\n" +
            """data: {"delta":"hello"}""" + "\n\n" +
            "event: response.completed\n" +
            """data: {"type":"response.completed","response":{"usage":{"input_tokens":10,"input_tokens_details":{"cached_tokens":3}}}}""" + "\n\n"
        val model = client(200 to body)
        val text = StringBuilder()
        val samples = mutableListOf<UsageSample?>()
        val result = model.chatStreaming(
            listOf(ChatMessage("user", "question")),
            webSearchEnabled = true,
            onUsage = { samples += it },
        ) { event -> if (event is StreamEvent.AnswerDelta) text.append(event.text) }
        assertEquals("event: 名必须能当类型用", "hello", text.toString())
        assertEquals(1, samples.size)
        assertEquals(10L, samples.first()!!.inputTokens)
        assertEquals(3L, samples.first()!!.cachedInputTokens)
        assertTrue(result.reply.contains("hello"))
    }

    @Test
    fun `response failed terminal is a failure without resend`() = runBlocking {
        val body = respEvent("response.output_text.delta", ""","delta":"partial"""") +
            respEvent("response.failed", ""","message":"search backend down"""")
        val model = client(200 to body)
        val error = runCatching {
            model.chatStreaming(listOf(ChatMessage("user", "q")), webSearchEnabled = true) {}
        }.exceptionOrNull()
        assertTrue(error is ModelException)
        assertEquals(ModelException.Kind.SERVER, (error as ModelException).kind)
        assertEquals("失败终态不得重发", 1, requests.size)
    }

    @Test
    fun `response incomplete terminal is a failure`() = runBlocking {
        val body = respEvent("response.output_text.delta", ""","delta":"partial"""") +
            respEvent("response.incomplete")
        val model = client(200 to body)
        val error = runCatching {
            model.chatStreaming(listOf(ChatMessage("user", "q")), webSearchEnabled = true) {}
        }.exceptionOrNull()
        assertTrue(error is ModelException)
        assertEquals(1, requests.size)
    }

    /** 已有正文但没有有效终态的 EOF ⇒ NETWORK 中断，且不得重发。 */
    @Test
    fun `eof after content without terminal is interrupted without resend`() = runBlocking {
        val body = respEvent("response.output_text.delta", ""","delta":"partial"""")
        val model = client(200 to body)
        val error = runCatching {
            model.chatStreaming(listOf(ChatMessage("user", "q")), webSearchEnabled = true) {}
        }.exceptionOrNull()
        assertTrue(error is ModelException)
        assertEquals(ModelException.Kind.NETWORK, (error as ModelException).kind)
        assertEquals("EOF 断流不得重发", 1, requests.size)
    }

    /** 两个 data 行组成一个 JSON 事件（Responses 路径同样要守住）。 */
    @Test
    fun `two data lines form one responses json event`() = runBlocking {
        val firstLine = """{"type":"response.output_text.delta","delta":"""
        val secondLine = """"多行事件正文"}"""
        val joined = "$firstLine\n$secondLine"
        // 夹具自证：拼接结果必须是一个合法 JSON 对象。
        val parsed = Json.parseToJsonElement(joined).jsonObject
        assertEquals("response.output_text.delta", parsed["type"]!!.jsonPrimitive.content)
        assertEquals("多行事件正文", parsed["delta"]!!.jsonPrimitive.content)

        val body = "data: $firstLine\ndata: $secondLine\n\n" +
            """data: {"type":"response.completed","response":{}}""" + "\n\n"
        val model = client(200 to body)
        val text = StringBuilder()
        model.chatStreaming(listOf(ChatMessage("user", "q")), webSearchEnabled = true) { event ->
            if (event is StreamEvent.AnswerDelta) text.append(event.text)
        }
        assertEquals("多行 data 必须拼成一个完整事件", "多行事件正文", text.toString())
    }

    /**
     * **Responses 终态之后保持响应体打开**：`response.completed` 事件已收齐
     * usage 与正文，客户端必须在那里结束读取，不能等服务端关流。
     */
    @Test(timeout = 20_000)
    fun `responses client finishes at completed even when the server keeps the body open`() = runBlocking {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val bodyWritten = CompletableDeferred<Unit>()
        val releaseServer = java.util.concurrent.CountDownLatch(1)
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* request headers */ }
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                    val events = "event: response.output_text.delta\n" +
                        "data: {\"delta\":\"正文\"}\n\n" +
                        "event: response.completed\n" +
                        "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":9,\"input_tokens_details\":{\"cached_tokens\":2}}}}\n\n"
                    writeChunk(out, events)
                    // 终止事件之后又写了一个完整 chunk（且不发 0 长度 chunk）：
                    // 这样缓冲区里始终有更多字节，读取停止只能是因为 completed 事件本身。
                    writeChunk(out, "data: {\"type\":\"response.output_text.delta\",\"delta\":\"必须丢弃\"}\n\n")
                    bodyWritten.complete(Unit)
                    // 不关连接、不发 0 长度 chunk：completed 之后必须自己停。
                    releaseServer.await()
                }
            } catch (_: Exception) { /* fixture cleanup */ }
        }.apply { isDaemon = true; start() }
        val prefs = mockk<SettingsRepository>()
        coEvery { prefs.current() } returns UserSettings(webSearchEnabled = true)
        val credentials = mockk<AiCredentialStore>()
        every { credentials.activeProfile() } returns AiModelProfile(
            "p", "p", "http://127.0.0.1:${server.localPort}", "deepseek-chat", true,
            AiSearchProtocol.RESPONSES, AiReasoningEffort.MEDIUM, true,
        )
        every { credentials.resolveActiveIdentity() } returns AiResolvedIdentity(
            profileId = "p",
            baseUrl = "http://127.0.0.1:${server.localPort}",
            model = "deepseek-chat",
            apiKey = "test-key",
            visionEnabled = true,
            searchProtocol = AiSearchProtocol.RESPONSES,
            reasoningEffort = AiReasoningEffort.MEDIUM,
        )
        val model = ModelClient(prefs, credentials, Dispatchers.Unconfined)
        var clientCall: kotlinx.coroutines.Deferred<Triple<ParsedReply, List<UsageSample?>, String>>? = null
        try {
            clientCall = async(Dispatchers.IO) {
                val samples = mutableListOf<UsageSample?>()
                val text = StringBuilder()
                val result = model.chatStreaming(
                    listOf(ChatMessage("user", "q")),
                    webSearchEnabled = true,
                    onUsage = { samples += it },
                ) { event -> if (event is StreamEvent.AnswerDelta) text.append(event.text) }
                Triple(result, samples, text.toString())
            }
            withTimeout(10_000) {
                bodyWritten.await()
                val (result, samples, text) = clientCall.await()
                assertEquals("正文", text)
                assertTrue(result.reply.contains("正文"))
                assertEquals(1, samples.size)
                assertEquals(9L, samples.first()!!.inputTokens)
                assertEquals(2L, samples.first()!!.cachedInputTokens)
                // completed 之后的增量不得再进入正文。
                assertFalse(text.contains("必须丢弃"))
            }
        } finally {
            clientCall?.cancel()
            releaseServer.countDown()
            server.close()
            serverThread.join(2000)
        }
    }

    /**
     * **失败终态在 keep-open 连接上同样不能等 EOF**：
     * `response.failed` 已是明确终态，读取必须停止并按失败抛出，且**不得重发**。
     */
    @Test(timeout = 20_000)
    fun `failed terminal stops reading on a kept open connection without resend`() = runBlocking {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val bodyWritten = CompletableDeferred<Unit>()
        val releaseServer = java.util.concurrent.CountDownLatch(1)
        // 服务端收到的连接数：重发会建立第二条连接。
        val connections = java.util.concurrent.atomic.AtomicInteger(0)
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    connections.incrementAndGet()
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* request headers */ }
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                    writeChunk(
                        out,
                        "event: response.output_text.delta\n" +
                            "data: {\"delta\":\"部分正文\"}\n\n" +
                            "event: response.failed\n" +
                            "data: {\"type\":\"response.failed\",\"message\":\"backend down\"}\n\n",
                    )
                    writeChunk(
                        out,
                        "event: response.output_text.delta\n" +
                            "data: {\"delta\":\"终态之后不得出现\"}\n\n",
                    )
                    bodyWritten.complete(Unit)
                    releaseServer.await()
                }
            } catch (_: Exception) { /* fixture cleanup */ }
        }.apply { isDaemon = true; start() }
        val prefs = mockk<SettingsRepository>()
        coEvery { prefs.current() } returns UserSettings(webSearchEnabled = true)
        val credentials = mockk<AiCredentialStore>()
        every { credentials.activeProfile() } returns AiModelProfile(
            "p", "p", "http://127.0.0.1:${server.localPort}", "deepseek-chat", true,
            AiSearchProtocol.RESPONSES, AiReasoningEffort.MEDIUM, true,
        )
        every { credentials.resolveActiveIdentity() } returns AiResolvedIdentity(
            profileId = "p",
            baseUrl = "http://127.0.0.1:${server.localPort}",
            model = "deepseek-chat",
            apiKey = "test-key",
            visionEnabled = true,
            searchProtocol = AiSearchProtocol.RESPONSES,
            reasoningEffort = AiReasoningEffort.MEDIUM,
        )
        val model = ModelClient(prefs, credentials, Dispatchers.Unconfined)
        var clientCall: kotlinx.coroutines.Deferred<Throwable?>? = null
        try {
            clientCall = async(Dispatchers.IO) {
                runCatching {
                    model.chatStreaming(listOf(ChatMessage("user", "q")), webSearchEnabled = true) {}
                }.exceptionOrNull()
            }
            withTimeout(10_000) {
                bodyWritten.await()
                val error = clientCall.await()
                assertTrue("失败终态必须抛出：$error", error is ModelException)
                assertEquals(ModelException.Kind.SERVER, (error as ModelException).kind)
                assertTrue(error.message.orEmpty().contains("backend down"))
                assertEquals("失败终态不得重发", 1, connections.get())
            }
        } finally {
            clientCall?.cancel()
            releaseServer.countDown()
            server.close()
            serverThread.join(2000)
        }
    }

    /** 失败/不完整终态也必须**停止读取**，不能在 keep-open 连接上等 EOF。 */
    @Test
    fun `failed and incomplete terminals stop the reader`() {
        val completed = """
            data: {"type":"response.failed","message":"boom"}

        """.trimIndent()
        // 直接用 reader 验证：回调返回 false 之后不再读后续事件。
        val events = mutableListOf<String>()
        val source = okio.Buffer().apply {
            writeUtf8(
                "data: {\"type\":\"response.failed\",\"message\":\"boom\"}\n\n" +
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"不得出现\"}\n\n",
            )
        }
        runBlocking {
            source.readSseEvents { sse ->
                events += sse.data
                val chunk = Json.parseToJsonElement(sse.data).jsonObject
                val type = chunk["type"]?.jsonPrimitive?.content.orEmpty()
                type != "response.failed" && type != "response.incomplete"
            }
        }
        assertEquals(1, events.size)
        assertTrue(events.single().contains("boom"))
    }
}
