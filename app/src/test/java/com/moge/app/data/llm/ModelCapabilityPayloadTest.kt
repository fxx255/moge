package com.moge.app.data.llm

import android.app.Application
import com.moge.app.data.credential.*
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Captures real HTTP payloads: declarations, recovery and protocol must agree. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ModelCapabilityPayloadTest {
    private val payloads = mutableListOf<JsonObject>()
    private var catalogCalls = 0
    private val baseUrl = "https://gateway.example/v1"

    private fun client(model: String, protocol: AiApiProtocol, catalog: () -> Pair<Int, String>,
        replies: List<Pair<Int, String>>): ModelClient {
        val settings = mockk<SettingsRepository>()
        coEvery { settings.current() } returns UserSettings()
        val credentials = mockk<AiCredentialStore>()
        every { credentials.activeProfileId() } returns null
        every { credentials.resolveActiveIdentity() } returns AiResolvedIdentity("p", baseUrl, model, "test-key", true,
            AiSearchProtocol.OFF, AiReasoningEffort.HIGH, protocol)
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (code, body) = if (request.method == "GET") {
                catalogCalls++
                assertEquals("/v1/models", request.url.encodedPath)
                catalog()
            } else {
                val buffer = Buffer()
                request.body!!.writeTo(buffer)
                val index = payloads.size
                payloads += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                replies[index]
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody((if (request.method == "GET") "application/json" else "text/event-stream").toMediaType())).build()
        }.build()
        return ModelClient(settings, credentials, Dispatchers.Unconfined).also {
            ModelClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(it, transport)
        }
    }

    private fun chatSse(text: String = "", finish: String = "stop") =
        "data: ${buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("content", text) }); put("finish_reason", finish)
        }) }) }}\n\ndata: [DONE]\n\n"

    @Test fun `gateway chat pins flash budget and disables thinking only for final answer recovery`() = runBlocking {
        var declaredLimit = 50000
        val catalog = { 200 to """{"data":[{"id":"cn:deepseek-v4-flash","max_output_tokens":$declaredLimit,"can_disable_thinking":true,"reasoning_supported_efforts":["low","high","xhigh"]}]}""" }
        val model = client("cn:deepseek-v4-flash", AiApiProtocol.CHAT_COMPLETIONS, catalog,
            listOf(200 to chatSse(finish = "length"), 200 to chatSse("""{"reply":"最终答案"}""")))
        var usageCalls = 0
        var refreshed = false
        val result = model.chatStreaming(listOf(ChatMessage("user", "题目")), onUsage = { usageCalls++ }) { event ->
            if (event is StreamEvent.ReasoningDelta && !refreshed) {
                refreshed = true
                declaredLimit = 64000
                model.fetchModelCatalog(baseUrl, "test-key")
            }
        }
        assertEquals("最终答案", result.reply)
        assertEquals(2, usageCalls) // /models is metadata, not a billed generation.
        assertEquals(2, catalogCalls)
        assertEquals(2, payloads.size)
        assertTrue(payloads.all { it["max_tokens"]!!.jsonPrimitive.int == 50000 })
        assertEquals("xhigh", payloads.first()["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("none", payloads.last()["reasoning_effort"]!!.jsonPrimitive.content)
        assertEquals("cn:deepseek-v4-flash", payloads.last()["model"]!!.jsonPrimitive.content)
    }

    @Test fun `gateway responses uses declared step budget and lowest supported recovery effort`() = runBlocking {
        val catalog = { 200 to """{"data":[{"id":"cn:step-5-preview","max_output_tokens":64000,"reasoning_supported_efforts":["low","medium","high"]}]}""" }
        val empty = "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}}\n\n"
        val final = "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"reply\\\":\\\"最终答案\\\"}\"}]}]}}\n\n"
        val model = client("cn:step-5-preview", AiApiProtocol.RESPONSES, catalog, listOf(200 to empty, 200 to final))
        assertEquals("最终答案", model.chatStreaming(listOf(ChatMessage("user", "题目"))) {}.reply)
        assertEquals(1, catalogCalls)
        assertTrue(payloads.all { it["max_output_tokens"]!!.jsonPrimitive.int == 64000 && !it.containsKey("max_tokens") })
        assertEquals("high", payloads.first()["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("low", payloads.last()["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test fun `catalog outage still negotiates full allowance with universal effort`() = runBlocking {
        val model = client("cn:deepseek-v4-flash", AiApiProtocol.CHAT_COMPLETIONS, { 401 to "{}" },
            listOf(200 to chatSse("""{"reply":"正文"}""")))
        assertEquals("正文", model.chatStreaming(listOf(ChatMessage("user", "题目"))) {}.reply)
        assertEquals(Int.MAX_VALUE, payloads.single()["max_tokens"]!!.jsonPrimitive.int)
        assertEquals("high", payloads.single()["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun `ordinary named chat model negotiates token name ceiling and optional effort in order`() = runBlocking {
        val model = client("unseen-model", AiApiProtocol.CHAT_COMPLETIONS, { 404 to "{}" }, listOf(
            400 to "Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead.",
            422 to "max_completion_tokens must be less than or equal to 262144",
            400 to "Unknown parameter: reasoning_effort", 200 to chatSse("""{"reply":"正文"}"""),
        ))
        val usage = mutableListOf<UsageSample?>()
        assertEquals("正文", model.chatStreaming(listOf(ChatMessage("user", "题目")), onUsage = usage::add) {}.reply)
        assertEquals(1, catalogCalls)
        assertEquals(4, usage.size)
        assertEquals(4, payloads.size)
        assertEquals(Int.MAX_VALUE, payloads[0]["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(Int.MAX_VALUE, payloads[1]["max_completion_tokens"]!!.jsonPrimitive.int)
        assertEquals(262144, payloads[2]["max_completion_tokens"]!!.jsonPrimitive.int)
        assertEquals(262144, payloads[3]["max_completion_tokens"]!!.jsonPrimitive.int)
        assertFalse(payloads[3].containsKey("reasoning_effort"))
        assertTrue(payloads.all { it["messages"] == payloads[0]["messages"] })
    }

    @Test fun `ordinary named responses model negotiates maximum and supported effort without metadata`() = runBlocking {
        val final = "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"reply\\\":\\\"正文\\\"}\"}]}]}}\n\n"
        val model = client("unseen-model", AiApiProtocol.RESPONSES, { 404 to "{}" }, listOf(
            422 to "max_output_tokens must be <= 131072", 400 to "reasoning.effort: Input should be 'low' or 'medium'", 200 to final,
        ))
        val usage = mutableListOf<UsageSample?>()
        assertEquals("正文", model.chatStreaming(listOf(ChatMessage("user", "题目")), onUsage = usage::add) {}.reply)
        assertEquals(3, usage.size)
        assertEquals(131072, payloads.last()["max_output_tokens"]!!.jsonPrimitive.int)
        assertEquals("medium", payloads.last()["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test fun `unsupported responses explains the protocol choice without resending`() = runBlocking {
        val model = client("cn:step-5-preview", AiApiProtocol.RESPONSES,
            { 200 to """{"data":[{"id":"cn:step-5-preview","max_output_tokens":64000}]}""" }, listOf(404 to "{}"))
        val error = runCatching { model.chatStreaming(listOf(ChatMessage("user", "题目"))) {} }.exceptionOrNull()
        assertTrue(error is ModelException)
        assertTrue(error!!.message!!.contains("Chat Completions"))
        assertEquals(1, payloads.size)
    }

    @Test fun `responses nonstream fallback honors explicit budget rejection and records each HTTP once`() = runBlocking {
        val catalog = { 200 to """{"data":[{"id":"cn:step-5-preview","max_output_tokens":64000,"reasoning_supported_efforts":["low","medium","high"]}]}""" }
        val final = """{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"reply\":\"最终答案\"}"}]}],"usage":{"input_tokens":10,"output_tokens":3}}"""
        val model = client("cn:step-5-preview", AiApiProtocol.RESPONSES, catalog,
            listOf(400 to "Unknown parameter: 'stream'", 400 to "max_output_tokens too large, maximum is 32000", 200 to final))
        val usage = mutableListOf<UsageSample?>()
        assertEquals("最终答案", model.chatStreaming(listOf(ChatMessage("user", "题目")), onUsage = usage::add) {}.reply)
        assertEquals(3, payloads.size)
        assertEquals(3, usage.size)
        assertNull(usage[0]); assertNull(usage[1]); assertEquals(3L, usage[2]!!.outputTokens)
        assertEquals(64000, payloads[1]["max_output_tokens"]!!.jsonPrimitive.int)
        assertEquals(32000, payloads[2]["max_output_tokens"]!!.jsonPrimitive.int)
    }
}
