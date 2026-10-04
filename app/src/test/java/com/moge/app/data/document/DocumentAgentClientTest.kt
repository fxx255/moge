package com.moge.app.data.document

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.llm.ChatMessage
import com.moge.app.runtime.PosixAtomicFileShadow
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class DocumentAgentClientTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = DocumentStore(context)
    private val requests = mutableListOf<Request>()
    private val payloads = mutableListOf<JsonObject>()
    private fun client(vararg responses: String) = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
        payloads += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
            .body(responses[(requests.size - 1).coerceAtMost(responses.lastIndex)].toResponseBody("application/json".toMediaType())).build()
    }.build()
    private suspend fun document(extension: String = "txt"): DocumentAttachment {
        val source = File(context.cacheDir, "讲义.$extension").apply { writeText("nonce-evidence-9284") }
        return store.attachment(store.import(Uri.fromFile(source)))
    }

    @Test fun chatToolsReadEvidenceAndKeepQuestionSeparateFromOriginal() = runBlocking {
        val doc = document()
        val call = buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject {
                put("role", "assistant"); put("content", JsonNull)
                put("tool_calls", buildJsonArray { add(buildJsonObject {
                    put("id", "read-call"); put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "read_document"); put("arguments", """{"document_id":"${doc.id}","locator":"section:1"}""")
                    })
                }) })
            }) }) })
        }.toString()
        val final = """{"choices":[{"message":{"role":"assistant","content":"{\"reply\":\"nonce-evidence-9284\"}"}}],"usage":{"prompt_tokens":10,"completion_tokens":5}}"""
        val result = DocumentAgentClient(context, store).run(
            client(call, final), "https://gateway.example/v1", "test", "test-key",
            AiApiProtocol.CHAT_COMPLETIONS, false, true,
            listOf(ChatMessage("user", "这份文档写了什么？", documentPaths = listOf(doc.path))),
            emptyList(), "return JSON", false, false, {}, {},
        )
        val firstUser = payloads.first()["messages"]!!.jsonArray.last().jsonObject
        assertFalse(firstUser.toString().contains("nonce-evidence"))
        val tool = payloads.last()["messages"]!!.jsonArray.first { it.jsonObject["role"]?.jsonPrimitive?.content == "tool" }.jsonObject
        assertEquals("read-call", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertTrue(tool["content"]!!.jsonPrimitive.content.contains("nonce-evidence-9284"))
        assertTrue(result.reply.contains("section:1"))
        assertTrue(result.reply.contains("moge-document://"))
        assertTrue(File(doc.path).isFile)
    }

    @Test fun anthropicReceivesOriginalPdfDocumentBlockWhileSearchIsOff() = runBlocking {
        val doc = document("pdf")
        val result = DocumentAgentClient(context, store).run(
            client("""{"content":[{"type":"text","text":"{\"reply\":\"PDF analyzed\"}"}],"stop_reason":"end_turn"}"""),
            "https://gateway.example/api/v1/messages", "test", "test-key",
            AiApiProtocol.ANTHROPIC_MESSAGES, true, true,
            listOf(ChatMessage("user", "检查图表", documentPaths = listOf(doc.path))),
            emptyList(), "return JSON", false, false, {}, {},
        )
        assertEquals("/api/v1/messages", requests.single().url.encodedPath)
        val blocks = payloads.single()["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        val document = blocks.last().jsonObject
        assertEquals("document", document["type"]!!.jsonPrimitive.content)
        assertEquals("讲义.pdf", document["title"]!!.jsonPrimitive.content)
        assertEquals("application/pdf", document["source"]!!.jsonObject["media_type"]!!.jsonPrimitive.content)
        assertTrue(result.reply.contains("PDF analyzed"))
        assertTrue(payloads.single()["tools"]!!.jsonArray.none { it.jsonObject["name"]?.jsonPrimitive?.content == "web_search" })
    }

    @Test fun responsesUseInputFileAndDoNotFallbackToChatWithSearchOff() = runBlocking {
        val doc = document("pdf")
        DocumentAgentClient(context, store).run(
            client("""{"incomplete_details":null,"output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"{\"reply\":\"analyzed\"}"}]}]}"""),
            "https://gateway.example/v1/responses", "test", "test-key",
            AiApiProtocol.RESPONSES, true, true,
            listOf(ChatMessage("user", "分析", documentPaths = listOf(doc.path))),
            emptyList(), "return JSON", false, false, {}, {},
        )
        assertEquals("/v1/responses", requests.single().url.encodedPath)
        val file = payloads.single()["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray.last().jsonObject
        assertEquals("input_file", file["type"]!!.jsonPrimitive.content)
        assertEquals("讲义.pdf", file["filename"]!!.jsonPrimitive.content)
        assertFalse(payloads.single()["tools"]!!.jsonArray.any { it.jsonObject["type"]?.jsonPrimitive?.content == "web_search" })
    }

    @Test fun ordinaryFollowupMayAnswerWithoutRereadingHistoryButNewAttachmentsRequireEvidence() = runBlocking {
        val doc = document()
        val final = """{"choices":[{"message":{"role":"assistant","content":"{\"reply\":\"不客气\"}"}}]}"""
        val agent = DocumentAgentClient(context, store)
        val followup = agent.run(client(final), "https://gateway.example/v1", "test", "test-key",
            AiApiProtocol.CHAT_COMPLETIONS, false, false, listOf(ChatMessage("user", "谢谢", documentPaths = listOf(doc.path))),
            emptyList(), "return JSON", false, false, {}, {}, requireRead = false)
        assertEquals("不客气", followup.reply)
        val fresh = runCatching { agent.run(client(final), "https://gateway.example/v1", "test", "test-key",
            AiApiProtocol.CHAT_COMPLETIONS, false, false, listOf(ChatMessage("user", "分析附件", documentPaths = listOf(doc.path))),
            emptyList(), "return JSON", false, false, {}, {}, requireRead = true) }
        assertTrue(fresh.exceptionOrNull()?.message.orEmpty().contains("没有读取附件"))
    }
}
