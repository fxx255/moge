package com.moge.app.data.llm

import android.app.Application
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.credential.AiProfileCredentials
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiResolvedIdentity
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AnthropicProtocolTest {
    private val requests = mutableListOf<Request>()
    private val payloads = mutableListOf<JsonObject>()

    private fun client(body: String, code: Int = 200, webSearch: Boolean = true, following: List<String> = emptyList()): ModelClient {
        val settings = mockk<SettingsRepository>()
        coEvery { settings.current() } returns UserSettings(webSearchEnabled = webSearch)
        val credentials = mockk<AiCredentialStore>(relaxed = true)
        val identity = AiResolvedIdentity(
            "anthropic", "https://proxy.example/v1/messages", "claude-test", "test-key",
            true, AiSearchProtocol.ANTHROPIC, AiReasoningEffort.LOW,
        )
        every { credentials.resolveActiveIdentity() } returns identity
        every { credentials.resolveIdentityFor("anthropic") } returns identity
        every { credentials.credentialsFor("anthropic") } returns AiProfileCredentials(
            identity.baseUrl, identity.model, identity.apiKey, identity.searchProtocol,
        )
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            chain.request().body?.let { requestBody ->
                val buffer = Buffer()
                requestBody.writeTo(buffer)
                payloads += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code).message("test").body((following.getOrNull(requests.size - 2) ?: body).toResponseBody("application/json".toMediaType())).build()
        }.build()
        return ModelClient(settings, credentials, Dispatchers.Unconfined).also {
            ModelClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(it, transport)
        }
    }

    private fun sse(vararg chunks: String) = chunks.joinToString("") { "data: $it\n\n" }
    private val textDelta = """{"type":"content_block_delta","delta":{"type":"text_delta","text":"{\"reply\":\"hello\"}"}}"""
    private val end = """{"type":"message_stop"}"""
    private val full = """{"type":"message","content":[{"type":"text","text":"{\"reply\":\"hello\"}"}],"stop_reason":"end_turn"}"""

    @Test
    fun messagesAndModelsUrlsPreserveProxyPrefix() {
        for (url in listOf("https://proxy.example/api", "https://proxy.example/api/v1/", "https://proxy.example/api/v1/messages")) {
            assertEquals("https://proxy.example/api/v1/messages", anthropicMessagesUrl(url))
            assertEquals("https://proxy.example/api/v1/models", anthropicModelsUrl(url))
        }
    }

    @Test
    fun offlineAnthropicStillUsesMessagesAndDoesNotSendSearchTool() = runBlocking {
        val model = client(full, webSearch = false)
        val result = model.chatStreaming(listOf(ChatMessage("user", "question")), webSearchEnabled = true) {}
        assertEquals("hello", result.reply)
        assertEquals("/v1/messages", requests.single().url.encodedPath)
        assertEquals("test-key", requests.single().header("x-api-key"))
        assertNull(requests.single().header("Authorization"))
        assertFalse(payloads.single().containsKey("tools"))
    }

    @Test
    fun forcedSearchNeverSilentlyAnswersOfflineOrUsesUnsupportedForcedChoice() = runBlocking {
        val error = runCatching { client(full).chatStreaming(
            listOf(ChatMessage("user", "question")), webSearchEnabled = true, forceWebSearch = true,
        ) {} }.exceptionOrNull()
        assertTrue(error is ModelException && error.kind == ModelException.Kind.CONFIG_INVALID)
        assertEquals("auto", payloads.single()["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(1, requests.size)
    }

    @Test
    fun pausedSearchResendsCompleteAssistantBlocksIncludingSignatureAndServerInput() = runBlocking {
        val paused = sse(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"checking"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"opaque-signature"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"server-call","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"today\"}"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"pause_turn"}}""", end,
        )
        val usages = mutableListOf<UsageSample?>()
        val reply = client(paused, following = listOf(full)).chatStreaming(
            listOf(ChatMessage("user", "question")), webSearchEnabled = true, onUsage = { usages += it },
        ) {}
        assertEquals("hello", reply.reply)
        assertEquals(2, requests.size)
        val assistant = payloads.last()["messages"]!!.jsonArray.last().jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val blocks = assistant["content"]!!.jsonArray
        assertEquals("opaque-signature", blocks[0].jsonObject["signature"]!!.jsonPrimitive.content)
        assertEquals("checking", blocks[0].jsonObject["thinking"]!!.jsonPrimitive.content)
        assertEquals("today", blocks[1].jsonObject["input"]!!.jsonObject["query"]!!.jsonPrimitive.content)
        assertEquals(2, usages.size)
    }

    @Test
    fun streamMergesPartialUsageIncludingCacheTokensAndThinking() = runBlocking {
        val body = sse(
            """{"type":"message_start","message":{"usage":{"input_tokens":10,"cache_creation_input_tokens":5,"cache_read_input_tokens":20,"output_tokens":0}}}""",
            """{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"check"}}""",
            textDelta,
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}""",
            end,
        )
        val samples = mutableListOf<UsageSample?>()
        val events = mutableListOf<StreamEvent>()
        val result = client(body).chatStreaming(listOf(ChatMessage("user", "q")), onUsage = { samples += it }) { events += it }
        assertEquals("hello", result.reply)
        assertEquals("check", result.rawReasoning)
        assertTrue(events.contains(StreamEvent.ReasoningDelta("check")))
        assertEquals(1, samples.size)
        assertEquals(35L, samples.single()!!.inputTokens)
        assertEquals(20L, samples.single()!!.cachedInputTokens)
        assertEquals(12L, samples.single()!!.outputTokens)
    }

    @Test
    fun citationDeltasAndToolResultsAreDeduplicated() = runBlocking {
        val body = sse(
            """{"type":"content_block_start","content_block":{"type":"server_tool_use","name":"web_search"}}""",
            """{"type":"content_block_start","content_block":{"type":"web_search_tool_result","content":[{"type":"web_search_result","url":"https://example.com/news","title":"News"}]}}""",
            textDelta,
            """{"type":"content_block_delta","delta":{"type":"citations_delta","citation":{"type":"web_search_result_location","url":"https://example.com/news","title":"News"}}}""",
            end,
        )
        val result = client(body).chatStreaming(listOf(ChatMessage("user", "q")), webSearchEnabled = true) {}
        assertTrue(result.reply.contains("[News](https://example.com/news)"))
        assertEquals(1, Regex("https://example.com/news").findAll(result.reply).count())
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun prematureEofKeepsPartialEventsAndThrowsWithoutRetry() = runBlocking {
        val events = mutableListOf<StreamEvent>()
        val error = runCatching {
            client(sse(textDelta)).chatStreaming(listOf(ChatMessage("user", "q"))) { events += it }
        }.exceptionOrNull()
        assertTrue(error is ModelException && error.kind == ModelException.Kind.NETWORK)
        assertTrue(events.any { it is StreamEvent.AnswerDelta })
        assertEquals(1, requests.size)
    }

    @Test
    fun authenticationAndStreamErrorsNeverRetryAsOpenAi() = runBlocking {
        for (code in listOf(401, 429, 200)) {
            requests.clear()
            val body = if (code == 200) sse("""{"type":"error","error":{"message":"overloaded"}}""") else "rejected"
            val error = runCatching { client(body, code).chatStreaming(listOf(ChatMessage("user", "q"))) {} }.exceptionOrNull()
            assertTrue(error is ModelException)
            assertEquals(if (code == 401) ModelException.Kind.UNAUTHORIZED else ModelException.Kind.SERVER, (error as ModelException).kind)
            assertEquals(1, requests.size)
        }
    }

    @Test
    fun fullJsonUsesTextCitationsAndMarksLengthLimit() = runBlocking {
        val body = """{"type":"message","content":[{"type":"text","text":"{\"reply\":\"hello\"}","citations":[{"url":"https://example.com","title":"Example"}]}],"stop_reason":"max_tokens"}"""
        val events = mutableListOf<StreamEvent>()
        val result = client(body).chatStreaming(listOf(ChatMessage("user", "q")), webSearchEnabled = true) { events += it }
        assertTrue(result.truncated)
        assertTrue(result.reply.contains("[Example](https://example.com)"))
        assertTrue(events.any { it is StreamEvent.AnswerDelta })
    }

    @Test
    fun connectionAndVisionCompletionUseNativeMessages() = runBlocking {
        val body = """{"type":"message","content":[{"type":"text","text":"recognized question"}]}"""
        val model = client(body)
        assertTrue(model.testConnection("anthropic").contains("连接成功"))
        assertEquals("recognized question", model.completeWithProfile("anthropic", "transcribe", "read", listOf("JPEG")))
        assertTrue(requests.all { it.url.encodedPath == "/v1/messages" && it.header("anthropic-version") == "2023-06-01" })
        val payload = payloads.last()
        assertEquals("transcribe", payload["system"]!!.jsonPrimitive.content)
        val image = payload["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.last().jsonObject
        assertEquals("JPEG", image["source"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun modelListUsesNativeHeadersForUnsavedProxyConfiguration() = runBlocking {
        val model = client("""{"data":[{"id":"claude-b"},{"id":"claude-a"}]}""")
        assertEquals(listOf("claude-a", "claude-b"), model.fetchModels("https://proxy.example/v1/messages", "test-key", protocol = AiSearchProtocol.ANTHROPIC))
        assertEquals("/v1/models", requests.single().url.encodedPath)
        assertEquals("test-key", requests.single().header("x-api-key"))
    }

    @Test
    fun webSearchProbeStaysOnAnthropicAndReportsSources() = runBlocking {
        val body = """{"content":[{"type":"web_search_tool_result","content":[{"url":"https://example.com","title":"News"}]},{"type":"text","text":"news"}]}"""
        val result = client(body).testWebSearch(
            "https://proxy.example", "test-key", "claude-test", AiSearchProtocol.ANTHROPIC,
        )
        assertTrue(result.contains("已发起搜索"))
        assertTrue(result.contains("来源 1 条"))
        assertEquals(1, requests.size)
    }
}
