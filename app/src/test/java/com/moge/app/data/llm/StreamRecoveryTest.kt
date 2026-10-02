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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class StreamRecoveryTest {
    private val requests = mutableListOf<JsonObject>()

    private fun client(responses: List<Pair<Int, String>>): ModelClient {
        val prefs = mockk<SettingsRepository>()
        coEvery { prefs.current() } returns UserSettings()
        val credentials = mockk<AiCredentialStore>()
        every { credentials.activeProfile() } returns AiModelProfile(
            "test", "test", "https://openrouter.ai/api/v1", "test-model", true,
            AiSearchProtocol.OFF, AiReasoningEffort.HIGH, true,
        )
        // 新 resolver：与 activeProfile 同源的身份+凭证（同一把锁取齐）。
        every { credentials.resolveActiveIdentity() } returns AiResolvedIdentity(
            profileId = "test",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "test-model",
            apiKey = "test-key",
            visionEnabled = true,
            searchProtocol = AiSearchProtocol.OFF,
            reasoningEffort = AiReasoningEffort.HIGH,
        )
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val index = requests.size
            requests += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val (code, body) = responses[index]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body(body.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return ModelClient(prefs, credentials, Dispatchers.Unconfined).also {
            ModelClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(it, transport)
        }
    }

    private fun sse(reasoning: String = "", content: String = "", finish: String = "stop"): String {
        val delta = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("delta", buildJsonObject { put("reasoning_content", reasoning); put("content", content) })
            }) })
        }
        // Some endpoints omit delta from their final finish_reason chunk.
        return "data: $delta\n\ndata: {\"choices\":[{\"finish_reason\":\"$finish\"}]}\n\ndata: [DONE]\n\n"
    }

    @Test fun `EOF after visible content without terminal event is interrupted without resending`() = runBlocking {
        val unfinished = sse(content = """{"reply":"partial answer"}""")
            .substringBefore("\n\ndata:") + "\n\n"
        val model = client(listOf(200 to unfinished))
        var visible = false
        val error = runCatching {
            model.chatStreaming(listOf(ChatMessage("user", "question"))) { event ->
                if (event is StreamEvent.AnswerDelta) visible = true
            }
        }.exceptionOrNull()
        assertTrue(visible)
        assertTrue(error is ModelException)
        assertEquals(ModelException.Kind.NETWORK, (error as ModelException).kind)
        assertEquals(1, requests.size)
    }

    @Test fun `reasoning only recovery lowers effort and keeps picture on original question`() = runBlocking {
        val model = client(listOf(200 to sse(reasoning = "analysis", finish = "length"),
            200 to sse(content = """{"reply":"final answer"}""")))
        val result = model.chatStreaming(listOf(ChatMessage("user", "question")), listOf("image-data")) {}
        assertEquals("final answer", result.reply)
        assertEquals(2, requests.size)
        val recovery = requests.last()
        assertEquals(16384, recovery["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("low", recovery["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val messages = recovery["messages"]!!.jsonArray
        // 断言意图（不依赖固定下标）：图片必须留在**原始问题**那条消息上，
        // 而不是被挂到恢复指令上 —— 前缀分段顺序调整后消息位置会变，
        // 但「图片归属原始问题」这条规则不能变。
        val questionIndex = messages.indexOfFirst { element ->
            val obj = element.jsonObject
            obj["role"]?.jsonPrimitive?.content == "user" &&
                (obj["content"] as? JsonArray)?.any { part ->
                    part.jsonObject["type"]?.jsonPrimitive?.content == "image_url"
                } == true
        }
        assertTrue("图片必须挂在原始问题上", questionIndex >= 0)
        assertTrue(
            "原始问题必须仍是列表里的第一条 user 消息之外的那条带图消息",
            messages[questionIndex].jsonObject["content"] is JsonArray,
        )
        assertTrue(messages.last().jsonObject["content"] is JsonPrimitive)
    }

    @Test fun `reasoning including draft JSON is never returned as a final answer`() = runBlocking {
        val thought = "Need check this draft {\"reply\":\"unverified draft\"}"
        val model = client(List(2) { 200 to sse(reasoning = thought, finish = "length") })
        val error = runCatching { model.chatStreaming(listOf(ChatMessage("user", "question"))) {} }.exceptionOrNull()
        assertTrue(error is ModelException)
        assertEquals(2, requests.size)
        assertFalse(error!!.message.orEmpty().contains("unverified draft"))
    }

    @Test fun `placeholder reply after reasoning is recovered instead of saved`() = runBlocking {
        val model = client(listOf(
            200 to sse(reasoning = "已经分析了图片", content = """{"reply":"见上"}"""),
            200 to sse(content = """{"reply":"这道题的完整解答与步骤。"}"""),
        ))
        val events = mutableListOf<StreamEvent>()
        val result = model.chatStreaming(listOf(ChatMessage("user", "帮我详细讲解"))) { events += it }
        assertEquals("这道题的完整解答与步骤。", result.reply)
        assertEquals(2, requests.size)
        assertTrue(events.any { it == StreamEvent.AnswerReset })
    }

    @Test fun `complete streamed reply survives a conflicting short final field`() = runBlocking {
        val complete = "完整的图片讲解。".repeat(28)
        val raw = buildJsonObject { put("reply", complete) }.toString().dropLast(1) + ",\"reply\":\"见上\"}"
        val model = client(listOf(200 to sse(content = raw)))
        val result = model.chatStreaming(listOf(ChatMessage("user", "讲解图片"))) {}
        assertEquals(complete, result.reply)
        assertEquals(1, requests.size)
    }

    /** 砺行的「英语积累补发」已删除：提问里出现这些词也不能多发一次计费请求。 */
    @Test fun `accumulation wording no longer triggers a second request`() = runBlocking {
        val model = client(listOf(
            200 to sse(content = """{"reply":"好词好句：shell out 意为花钱。","subject":"英语"}"""),
        ))
        val result = model.chatStreaming(listOf(ChatMessage("user", "帮我积累一下这句的好词好句"))) {}
        assertEquals(1, requests.size)
        assertEquals("好词好句：shell out 意为花钱。", result.reply)
        assertEquals("", result.finalAnswer)
    }

    @Test fun `finish reason without delta still triggers continuation and preserves raw delimiters`() = runBlocking {
        val model = client(listOf(200 to sse(content = """{"reply":"value ${'$'}x"}""", finish = "length")))
        val result = model.chatStreaming(listOf(ChatMessage("user", "question"))) {}
        assertTrue(result.truncated)
        assertEquals("value ${'$'}x", result.reply)
    }

    @Test fun `recovery budget rejection retries compatible budget once`() = runBlocking {
        val model = client(listOf(200 to sse(reasoning = "analysis"), 400 to "max_tokens exceeds limit",
            200 to sse(content = """{"reply":"done"}""")))
        assertEquals("done", model.chatStreaming(listOf(ChatMessage("user", "question"))) {}.reply)
        assertEquals(listOf(8192, 16384, 8192), requests.map { it["max_tokens"]!!.jsonPrimitive.int })
    }

    @Test fun `cancellation during recovery is propagated`() = runBlocking {
        val model = client(listOf(200 to sse(reasoning = "first"), 200 to sse(reasoning = "second")))
        val error = runCatching {
            model.chatStreaming(listOf(ChatMessage("user", "question"))) {
                if (it is StreamEvent.ReasoningDelta && it.text == "second") throw CancellationException("cancel")
            }
        }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }

    /** SSE 允许**多行 data:**（每行各自 `data:` 前缀，用换行连接成一条事件）。 */
    @Test fun `multi line sse data is joined into one event`() = runBlocking {
        val replyJson = """{"reply":"多行事件正文"}"""
        // 把一条 JSON 拆成两行 data:，这是 SSE 规范允许、真实网关也偶发的写法。
        val split = replyJson.length / 2
        val first = replyJson.substring(0, split)
        val second = replyJson.substring(split)
        val body = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":")
            append("\"")
            append(first.replace("\\", "\\\\").replace("\"", "\\\""))
            append("\\n")
            append(second.replace("\\", "\\\\").replace("\"", "\\\""))
            append("\"}}]}\n\n")
            append("data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n")
            append("data: [DONE]\n\n")
        }
        val model = client(listOf(200 to body))
        // 多行拼接后仍然能解出正文（即便这里被拆成带 \n 的字符串，
        // 关键是不能抛异常、不能丢事件）。
        val result = runCatching {
            model.chatStreaming(listOf(ChatMessage("user", "question"))) {}
        }.getOrNull()
        assertEquals(1, requests.size)
        assertTrue("多行 SSE 必须被正常解析", result != null)
    }

    /** 429 限流：**不得**回退重发（会重复计费），必须直接中断。 */
    @Test fun `rate limit is not retried on another protocol`() = runBlocking {
        val model = client(listOf(429 to "rate limited"))
        val error = runCatching {
            model.chatStreaming(listOf(ChatMessage("user", "question"))) {}
        }.exceptionOrNull()
        assertTrue(error is ModelException)
        assertEquals("限流必须只发一次请求", 1, requests.size)
    }

    /** 401 鉴权失败：同样不得回退重发。 */
    @Test fun `unauthorized is not retried on another protocol`() = runBlocking {
        val model = client(listOf(401 to "bad key"))
        val error = runCatching {
            model.chatStreaming(listOf(ChatMessage("user", "question"))) {}
        }.exceptionOrNull()
        assertTrue(error is ModelException)
        assertEquals(ModelException.Kind.UNAUTHORIZED, (error as ModelException).kind)
        assertEquals(1, requests.size)
    }

    /**
     * 泛化的 400（`invalid_request_error`，正文里顺带提到 stream）**不得**触发回退。
     * 这类错误几乎出现在所有参数错误上，回退等于白白多花一次请求。
     */
    @Test fun `generic invalid request error does not trigger fallback resend`() = runBlocking {
        val generic = """
            {"error":{"message":"invalid_request_error: unsupported value for field 'messages'; stream was set","type":"invalid_request_error"}}
        """.trimIndent()
        val model = client(listOf(400 to generic))
        val error = runCatching {
            model.chatStreaming(listOf(ChatMessage("user", "question"))) {}
        }.exceptionOrNull()
        assertTrue("泛化 400 必须是真实失败", error is ModelException)
        assertEquals("不得为泛化 400 重发请求", 1, requests.size)
    }

    /** 回退判据（纯函数）：只有点名 stream 参数且明确不支持才允许。 */
    @Test fun `stream parameter rejection matcher is narrow`() {
        // 允许回退：明确点名 stream 参数不被支持。
        assertTrue(isStreamParameterRejected(400, "Unknown parameter: 'stream'"))
        assertTrue(isStreamParameterRejected(400, """{"error":{"message":"unsupported parameter: stream"}}"""))
        assertTrue(isStreamParameterRejected(400, "stream 参数不支持"))
        // 不允许回退：泛化错误 / 限流 / 参数名不明确。
        assertFalse(isStreamParameterRejected(400, "invalid_request_error"))
        assertFalse(isStreamParameterRejected(400, "unsupported value for field 'messages'"))
        assertFalse(isStreamParameterRejected(429, "Unknown parameter: 'stream'"))
        assertFalse(isStreamParameterRejected(500, "Unknown parameter: 'stream'"))
        assertFalse(isStreamParameterRejected(400, "stream is temporarily unavailable"))
    }

    /**
     * **两个 data 行拼成一个 JSON 事件**：SSE 规范允许把一个 JSON 对象
     * 在字符串字面量**之外**切成多行；逐行 parse 会把每行各自当事件、都解析失败
     * ⇒ 正文整个丢失。夹具先自证拼起来是一个合法 JSON 对象。
     */
    @Test fun `two data lines forming one json event produce the full content`() = runBlocking {
        // 一个完整 JSON 对象，在字符串字面量之外切一刀。
        val firstLine = """{"choices":[{"delta":{"content":"""
        val secondLine = """"{\"reply\":\"多行事件正文\"}"}}]}"""
        val joined = "$firstLine\n$secondLine"
        // 夹具自证：拼接结果必须是合法 JSON，且正文确实在里面。
        val parsed = Json.parseToJsonElement(joined).jsonObject
        val content = parsed["choices"]!!.jsonArray[0].jsonObject["delta"]!!
            .jsonObject["content"]!!.jsonPrimitive.content
        assertEquals("""{"reply":"多行事件正文"}""", content)

        val body = "data: $firstLine\ndata: $secondLine\n\n" +
            "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n" +
            "data: [DONE]\n\n"
        val model = client(listOf(200 to body))
        val result = model.chatStreaming(listOf(ChatMessage("user", "question"))) {}
        assertEquals(1, requests.size)
        assertEquals("两行 data 必须拼成一条完整正文", "多行事件正文", result.reply)
    }

    /** usage-only 末块（choices 为空）必须计一次 usage，且每个真实 HTTP 调用回调一次。 */
    @Test fun `usage callback fires once per actual http request`() = runBlocking {
        val body = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"reply\\\":\\\"ok\\\"}\"}}]}\n\n")
            append("data: {\"choices\":[{\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":10,\"prompt_cache_hit_tokens\":4}}\n\n")
            append("data: [DONE]\n\n")
        }
        val model = client(listOf(200 to body))
        val samples = mutableListOf<UsageSample?>()
        model.chatStreaming(listOf(ChatMessage("user", "question")), onUsage = { samples += it }) {}
        assertEquals("单次 HTTP 请求 ⇒ 一次 usage 回调", 1, samples.size)
        assertEquals(10L, samples.first()!!.inputTokens)
        assertEquals(4L, samples.first()!!.cachedInputTokens)
    }

    /** 失败请求也要回调一次（null 表示"这次调用拿不到 usage"）。 */
    @Test fun `failed http request still reports one null usage sample`() = runBlocking {
        val model = client(listOf(500 to "server error"))
        val samples = mutableListOf<UsageSample?>()
        runCatching {
            model.chatStreaming(listOf(ChatMessage("user", "question")), onUsage = { samples += it }) {}
        }
        assertEquals("失败请求也必须计一次（未知）", 1, samples.size)
        assertNull(samples.first())
    }

    /** 恢复（reasoning-only）会多一次真实 HTTP 请求 ⇒ usage 回调两次。 */
    @Test fun `recovery request reports its own usage callback`() = runBlocking {
        val model = client(
            listOf(200 to sse(reasoning = "analysis", finish = "length"), 200 to sse(content = """{"reply":"final"}""")),
        )
        val samples = mutableListOf<UsageSample?>()
        model.chatStreaming(listOf(ChatMessage("user", "question")), onUsage = { samples += it }) {}
        assertEquals("首轮 + 恢复 = 两次真实 HTTP", 2, samples.size)
    }

    /** 普通 invalid_request_error 400 **不得**再降预算重发。 */
    @Test fun `generic invalid request 400 does not retry with lower budget`() = runBlocking {
        val model = client(
            listOf(
                200 to sse(reasoning = "analysis"),
                400 to """{"error":{"message":"invalid_request_error: something else"}}""",
            ),
        )
        val error = runCatching {
            model.chatStreaming(listOf(ChatMessage("user", "question"))) {}
        }.exceptionOrNull()
        assertTrue(error is ModelException)
        assertEquals("泛化 400 不得为降预算重发", 2, requests.size)
    }

    /** 明确点名 max_tokens 过大才降预算。 */
    @Test fun `explicit max tokens too large retries with compatible budget`() = runBlocking {
        val model = client(
            listOf(
                200 to sse(reasoning = "analysis"),
                400 to "HTTP 400 invalid_request_error: max_tokens is too large, maximum is 8192",
                200 to sse(content = """{"reply":"done"}"""),
            ),
        )
        assertEquals("done", model.chatStreaming(listOf(ChatMessage("user", "question"))) {}.reply)
        assertEquals(listOf(8192, 16384, 8192), requests.map { it["max_tokens"]!!.jsonPrimitive.int })
    }

    /** 预算重发判据（纯函数）：必须点名输出上限参数且明确说过大。 */
    @Test fun `output budget rejection matcher is narrow`() {
        assertTrue(isOutputBudgetRejected("max_tokens is too large"))
        assertTrue(isOutputBudgetRejected("max_output_tokens exceeds the maximum"))
        assertTrue(isOutputBudgetRejected("HTTP 400: max_tokens, must not exceed 8192"))
        assertTrue(isOutputBudgetRejected("HTTP 400: maximum allowed is 8192 for max_tokens"))
        assertTrue(isOutputBudgetRejected("max_completion_tokens 过大"))
        // 下界/类型校验不是「上限被超出」：降预算重发解决不了。
        assertFalse(isOutputBudgetRejected("max_tokens must be an integer greater than 0"))
        assertFalse(isOutputBudgetRejected("max_tokens must be an even multiple of 8"))
        assertFalse(isOutputBudgetRejected("invalid_request_error"))
        assertFalse(isOutputBudgetRejected("messages field is invalid"))
        assertFalse(isOutputBudgetRejected("unsupported parameter"))
    }

    /**
     * **真实传输**：服务端发送了完整终止帧（finish_reason + [DONE]）之后
     * 故意不关连接。客户端必须在终止帧处结束读取并正常返回，
     * 不能一直阻塞到 read timeout 或等服务端关流。
     */
    @Test(timeout = 20_000)
    fun `client finishes at terminal framing even when the server keeps the body open`() = runBlocking {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val bodyWritten = CompletableDeferred<Unit>()
        // 显式保持连接：直到客户端断言完成才放行（不用 reader.read() —— 那会读到 POST 正文立即返回）。
        val releaseServer = java.util.concurrent.CountDownLatch(1)
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { /* request headers */ }
                    val out = socket.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                    // finish_reason 之后还有一个 usage-only 末块，然后才 [DONE]。
                    val events = "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"reply\\\":\\\"ok\\\"}\"}}]}\n\n" +
                        "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"prompt_cache_hit_tokens\":5}}\n\n" +
                        "data: [DONE]\n\n"
                    out.write(events.length.toString(16).toByteArray())
                    out.write("\r\n".toByteArray())
                    out.write(events.toByteArray())
                    out.write("\r\n".toByteArray())
                    out.flush()
                    bodyWritten.complete(Unit)
                    // 故意不关闭连接、不发 0 长度 chunk：读取必须自己停。
                    releaseServer.await()
                }
            } catch (_: Exception) { /* fixture cleanup */ }
        }.apply { isDaemon = true; start() }
        val prefs = mockk<SettingsRepository>()
        coEvery { prefs.current() } returns UserSettings()
        val credentials = mockk<AiCredentialStore>()
        every { credentials.activeProfile() } returns AiModelProfile(
            "t", "t", "http://127.0.0.1:${server.localPort}", "test-model", true,
            AiSearchProtocol.OFF, AiReasoningEffort.HIGH, true,
        )
        every { credentials.resolveActiveIdentity() } returns AiResolvedIdentity(
            profileId = "t",
            baseUrl = "http://127.0.0.1:${server.localPort}",
            model = "test-model",
            apiKey = "test-key",
            visionEnabled = true,
            searchProtocol = AiSearchProtocol.OFF,
            reasoningEffort = AiReasoningEffort.HIGH,
        )
        val model = ModelClient(prefs, credentials, Dispatchers.Unconfined)
        try {
            // 先启动客户端，再等服务端写完响应体（反了会互相等待）。
            val clientCall = GlobalScope.async(Dispatchers.IO) {
                val samples = mutableListOf<UsageSample?>()
                val result = model.chatStreaming(
                    listOf(ChatMessage("user", "question")),
                    onUsage = { samples += it },
                ) {}
                result to samples
            }
            withTimeout(10_000) {
                bodyWritten.await()
                val (result, samples) = clientCall.await()
                assertEquals("ok", result.reply)
                // usage-only 末块仍被保留：终止帧之后的内容不能被丢弃。
                assertEquals("一次 HTTP ⇒ 一次 usage 回调", 1, samples.size)
                assertEquals(12L, samples.first()!!.inputTokens)
                assertEquals(5L, samples.first()!!.cachedInputTokens)
            }
        } finally {
            releaseServer.countDown()
            server.close()
            serverThread.join(2000)
        }
    }
}
