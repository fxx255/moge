package com.moge.app.data.document

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.llm.ChatMessage
import com.moge.app.runtime.PosixAtomicFileShadow
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
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
            AiApiProtocol.CHAT_COMPLETIONS, true,
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

    @Test fun ordinaryFollowupMayAnswerWithoutRereadingHistoryButNewAttachmentsRequireEvidence() = runBlocking {
        val doc = document()
        val final = """{"choices":[{"message":{"role":"assistant","content":"{\"reply\":\"不客气\"}"}}]}"""
        val agent = DocumentAgentClient(context, store)
        val followup = agent.run(client(final), "https://gateway.example/v1", "test", "test-key",
            AiApiProtocol.CHAT_COMPLETIONS, false, listOf(ChatMessage("user", "谢谢", documentPaths = listOf(doc.path))),
            emptyList(), "return JSON", false, false, {}, {}, requireRead = false)
        assertEquals("不客气", followup.reply)
        val fresh = runCatching { agent.run(client(final), "https://gateway.example/v1", "test", "test-key",
            AiApiProtocol.CHAT_COMPLETIONS, false, listOf(ChatMessage("user", "分析附件", documentPaths = listOf(doc.path))),
            emptyList(), "return JSON", false, false, {}, {}, requireRead = true) }
        assertTrue(fresh.exceptionOrNull()?.message.orEmpty().contains("没有读取附件"))
    }

    @Test fun bothProtocolsReadPdfPagesThroughToolsWithoutSendingNativeFileBlocks() = runBlocking {
        PDFBoxResourceLoader.init(context)
        val source = File(context.cacheDir, "evidence.pdf")
        PDDocument().use { pdf ->
            val page = PDPage(); pdf.addPage(page)
            PDPageContentStream(pdf, page).use { content ->
                content.beginText(); content.setFont(PDType1Font.HELVETICA, 12f)
                content.newLineAtOffset(40f, 700f); content.showText("pdf-evidence-9284"); content.endText()
            }
            pdf.save(source)
        }
        val doc = store.attachment(store.import(Uri.fromFile(source)))
        for (protocol in AiApiProtocol.entries) {
            requests.clear(); payloads.clear()
            val arguments = """{"document_id":"${doc.id}","locator":"page:1"}"""
            val call = if (protocol == AiApiProtocol.RESPONSES) buildJsonObject {
                put("output", buildJsonArray { add(buildJsonObject {
                    put("type", "function_call"); put("name", "read_document"); put("call_id", "read-page")
                    put("arguments", arguments)
                }) })
            } else buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject {
                    put("role", "assistant"); put("content", JsonNull)
                    put("tool_calls", buildJsonArray { add(buildJsonObject {
                        put("id", "read-page"); put("type", "function")
                        put("function", buildJsonObject { put("name", "read_document"); put("arguments", arguments) })
                    }) })
                }) }) })
            }
            val finalText = """{"reply":"已核对页面证据"}"""
            val final = if (protocol == AiApiProtocol.RESPONSES) buildJsonObject {
                put("output", buildJsonArray { add(buildJsonObject {
                    put("type", "message"); put("role", "assistant")
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", finalText) }) })
                }) })
            } else buildJsonObject {
                put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject {
                    put("role", "assistant"); put("content", finalText)
                }) }) })
            }
            val result = DocumentAgentClient(context, store).run(client(call.toString(), final.toString()),
                "https://gateway.example/v1", "test", "test-key", protocol, true,
                listOf(ChatMessage("user", "分析PDF", documentPaths = listOf(doc.path))),
                emptyList(), "return JSON", false, false, {}, {})
            assertEquals(2, requests.size)
            assertFalse(payloads.first().toString().contains("file_data"))
            assertFalse(payloads.first().toString().contains("input_file"))
            assertFalse(payloads.first().toString().contains("pdf-evidence-9284"))
            assertTrue(payloads.last().toString().contains("pdf-evidence-9284"))
            assertTrue(result.reply.contains("page:1"))
            assertTrue(File(doc.path).exists())
        }
    }
}
