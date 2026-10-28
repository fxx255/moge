package com.moge.app.data.llm.tools

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.credential.*
import com.moge.app.data.document.DocumentStore
import com.moge.app.data.llm.*
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.ConversationMode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
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
class SolveToolRoutingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val payloads = mutableListOf<JsonObject>()
    private fun model(protocol: AiApiProtocol, declared: Boolean? = null, replies: List<Pair<Int, String>>): ModelClient {
        val settings = mockk<SettingsRepository>()
        coEvery { settings.current() } returns UserSettings(nickname = "测试称呼")
        val credentials = mockk<AiCredentialStore>()
        every { credentials.resolveActiveIdentity() } returns AiResolvedIdentity("p", "https://gateway.example/v1", "test-model", "test-key", true,
            AiSearchProtocol.OFF, AiReasoningEffort.HIGH, protocol)
        val transport = OkHttpClient.Builder().addInterceptor { chain ->
            val (code, text) = if (chain.request().method == "GET") 200 to buildJsonObject {
                put("data", buildJsonArray { add(buildJsonObject { put("id", "test-model"); if (declared != null) put("supports_tools", declared) }) })
            }.toString() else {
                val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
                val index = payloads.size; payloads += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                replies[index]
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(text.toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        return ModelClient(settings, credentials, Dispatchers.Unconfined, solveToolClient = SolveToolClient({ true }, context, DocumentStore(context))).also {
            ModelClient::class.java.getDeclaredField("client").apply { isAccessible = true }.set(it, transport)
        }
    }

    private fun final(protocol: AiApiProtocol) = if (protocol == AiApiProtocol.RESPONSES) SolveToolClientTest.responseFinal("""{"reply":"正文"}""")
        else SolveToolClientTest.chatText("""{"reply":"正文"}""")
    private fun system(payload: JsonObject, protocol: AiApiProtocol) = if (protocol == AiApiProtocol.RESPONSES) payload["instructions"]!!.jsonPrimitive.content
        else payload["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonPrimitive.content

    @Test fun productionEntryUsesCompactToolsWithBothProtocols() = runBlocking {
        for (protocol in AiApiProtocol.entries) {
            payloads.clear()
            val result = model(protocol, replies = listOf(200 to final(protocol))).chatStreaming(listOf(ChatMessage("user", "问题"))) {}
            assertEquals("正文", result.reply)
            assertTrue(system(payloads.single(), protocol).startsWith(SOLVE_SYSTEM_PROMPT))
            assertTrue(system(payloads.single(), protocol).contains("测试称呼"))
            assertEquals(2, payloads.single()["tools"]!!.jsonArray.size)
            assertFalse(system(payloads.single(), protocol).contains("textbook_dual_branch"))
        }
    }

    @Test fun explicitRejectionFallsBackOnceToSameProtocolAndKeepsOriginalImage() = runBlocking {
        for (protocol in AiApiProtocol.entries) {
            payloads.clear()
            val samples = mutableListOf<UsageSample?>()
            val result = model(protocol, replies = listOf(400 to "function calling is not supported", 200 to final(protocol)))
                .chatStreaming(listOf(ChatMessage("user", "原问题")), listOf("ORIGINAL-IMAGE"), onUsage = samples::add) {}
            assertEquals(2, payloads.size); assertEquals(2, samples.size)
            assertTrue(system(payloads.first(), protocol).startsWith(SOLVE_SYSTEM_PROMPT))
            assertTrue(system(payloads.last(), protocol).startsWith(LEGACY_SOLVE_SYSTEM_PROMPT))
            assertTrue(payloads.all { it.toString().contains("ORIGINAL-IMAGE") })
            assertFalse(payloads.last().containsKey("tools"))
            assertTrue(result.warnings.any { it.contains("兼容绘图") })
        }
    }

    @Test fun declaredNoToolsSkipsProbeAndOrdinaryChatKeepsPlainOutput() = runBlocking {
        for (protocol in AiApiProtocol.entries) {
            payloads.clear()
            model(protocol, declared = false, replies = listOf(200 to final(protocol))).chatStreaming(listOf(ChatMessage("user", "题目"))) {}
            assertTrue(system(payloads.single(), protocol).startsWith(LEGACY_SOLVE_SYSTEM_PROMPT))
            assertFalse(payloads.single().containsKey("tools"))
            payloads.clear()
            val text = """{"reply":"JSON示例"}"""
            val body = if (protocol == AiApiProtocol.RESPONSES) SolveToolClientTest.responseFinal(text) else SolveToolClientTest.chatText(text)
            val result = model(protocol, replies = listOf(200 to body)).chatStreaming(listOf(ChatMessage("user", "聊天")),
                policy = RequestPolicy(apiProtocol = protocol.name, conversationMode = ConversationMode.CHAT)) {}
            assertEquals(text, result.reply)
            assertFalse(payloads.single().containsKey("tools"))
            assertFalse(system(payloads.single(), protocol).contains("render_figure"))
        }
    }

    @Test fun imageStaysWithOriginalQuestionInToolContinuation() = runBlocking {
        val guide = SolveToolClientTest.chatCalls(SolveToolClientTest.call("guide", "get_figure_guide", """{"kind":"curve"}"""))
        model(AiApiProtocol.CHAT_COMPLETIONS, replies = listOf(200 to guide, 200 to final(AiApiProtocol.CHAT_COMPLETIONS)))
            .chatStreaming(listOf(ChatMessage("user", "原问题")), listOf("IMAGE-ONCE")) {}
        for (payload in payloads) {
            val pictureMessages = payload["messages"]!!.jsonArray.filter { it.toString().contains("IMAGE-ONCE") }
            assertEquals(1, pictureMessages.size)
            assertTrue(pictureMessages.single().toString().contains("原问题"))
        }
    }
}
