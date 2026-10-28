package com.moge.app.data.llm.tools

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.document.DocumentStore
import com.moge.app.data.llm.*
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.parse.orderedFigures
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ToolNetworkRecoveryTest {
    private val context: android.content.Context = ApplicationProvider.getApplicationContext()
    private val payloads = mutableListOf<JsonObject>()
    private val events = mutableListOf<StreamEvent>()
    private val samples = mutableListOf<UsageSample?>()
    private val network = object : NetworkAvailability {
        override val available = true
        override suspend fun awaitAvailable(timeoutMs: Long) = true
    }
    private val request = Request.Builder().url("https://fixture.invalid/v1").build()
    private val prefix = "前面的步骤已说明。\n\n"
    private val rawPrefix = "{\"conversation_title\":\"原题标题\",\"answer\":\"42\",\"reply\":" + JsonPrimitive(prefix).toString().dropLast(1)
    private val finalText = """{"reply":"下面接着讲解。\n[[FIGURE:1]]"}"""

    private fun payload(protocol: AiApiProtocol) = buildJsonObject {
        put("model", "fixture"); put("instructions", "fixed-system")
        put(if (protocol == AiApiProtocol.RESPONSES) "input" else "messages", buildJsonArray {
            add(buildJsonObject { put("role", "user"); put("content", "原问题") })
        })
    }

    private fun draw(protocol: AiApiProtocol): String {
        val args = FigureToolsTest.args(1, "plot", FigureToolsTest.CURVE).toString()
        return if (protocol != AiApiProtocol.RESPONSES) SolveToolClientTest.chatCalls(SolveToolClientTest.call("draw", "render_figure", args))
        else SolveToolClientTest.sse(buildJsonObject {
            put("type", "response.completed"); put("response", buildJsonObject { put("output", buildJsonArray { add(buildJsonObject {
                put("type", "function_call"); put("id", "fc1"); put("call_id", "draw"); put("name", "render_figure"); put("arguments", args)
            }) }) })
        })
    }

    private fun partial(protocol: AiApiProtocol, raw: String) = SolveToolClientTest.sse(buildJsonObject {
        if (protocol == AiApiProtocol.RESPONSES) { put("type", "response.output_text.delta"); put("delta", raw) }
        else put("choices", buildJsonArray { add(buildJsonObject { put("delta", buildJsonObject { put("content", raw) }) }) })
    })

    private fun transport(protocol: AiApiProtocol, noText: Boolean = false, cutAgain: Boolean = false): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            val index = payloads.size
            payloads += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val body: ResponseBody = when {
                index == 0 -> draw(protocol).toResponseBody("text/event-stream".toMediaType())
                index == 1 || cutAgain -> {
                    val pending = Buffer().writeUtf8(partial(protocol, if (noText) "{\"conversation_title\":\"only title\"" else rawPrefix))
                    object : ResponseBody() {
                        override fun contentType() = "text/event-stream".toMediaType()
                        override fun contentLength() = -1L
                        private val input = object : Source {
                            override fun read(sink: Buffer, byteCount: Long) = if (pending.size > 0) pending.read(sink, byteCount) else throw IOException("connection reset")
                            override fun timeout() = Timeout.NONE
                            override fun close() { }
                        }.buffer()
                        override fun source() = input
                    }
                }
                else -> (if (protocol == AiApiProtocol.RESPONSES) SolveToolClientTest.responseFinal(finalText, true)
                    else SolveToolClientTest.chatText(finalText)).toResponseBody("text/event-stream".toMediaType())
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(body).build()
        }.build()

    @Test fun bothProtocolsRetainToolTranscriptFiguresAndUsageAcrossOneContinuation() = runBlocking {
        for (protocol in listOf(AiApiProtocol.CHAT_COMPLETIONS, AiApiProtocol.RESPONSES)) {
            payloads.clear(); events.clear(); samples.clear()
            var renders = 0
            val harness = SolveToolClient({ renders++; true }, context, DocumentStore(context))
            val result = withContext(GenerationNetworkSession(network)) {
                harness.run(transport(protocol), request, payload(protocol), protocol, emptyList(), false,
                    JsonObject(emptyMap()), samples::add, events::add, autoResumeOnNetworkLoss = true)!!
            }
            assertEquals(3, payloads.size)
            assertEquals(3, samples.size)
            assertEquals(1, renders)
            assertEquals("原题标题", result.reply.conversationTitle)
            assertEquals("42", result.reply.finalAnswer)
            assertEquals(prefix + "下面接着讲解。\n[[FIGURE:1]]", result.reply.reply)
            assertTrue(result.reply.orderedFigures().single() is ReplyFigure.Plot)
            val key = if (protocol == AiApiProtocol.RESPONSES) "input" else "messages"
            val prior = payloads[1][key]!!.jsonArray
            assertEquals(prior, JsonArray(payloads[2][key]!!.jsonArray.take(prior.size)))
            assertEquals(payloads[1]["tools"], payloads[2]["tools"])
            val decoder = ConversationResponse(com.moge.app.domain.ConversationMode.SOLVE).streamDecoder()
            events.forEach { when (it) {
                is StreamEvent.AnswerDelta -> decoder.append(it.text)
                StreamEvent.AnswerReset -> decoder.reset()
                else -> Unit
            } }
            assertEquals(result.reply.reply, decoder.finish())
        }
    }

    @Test fun missingBodyAndRepeatedNetworkFailureCannotCauseFurtherModelRequests() = runBlocking {
        for (noText in listOf(true, false)) {
            payloads.clear()
            val harness = SolveToolClient({ true }, context, DocumentStore(context))
            try { withContext(GenerationNetworkSession(network)) {
                harness.run(transport(AiApiProtocol.CHAT_COMPLETIONS, noText, cutAgain = true), request,
                    payload(AiApiProtocol.CHAT_COMPLETIONS), AiApiProtocol.CHAT_COMPLETIONS, emptyList(), false,
                    JsonObject(emptyMap()), samples::add, events::add, autoResumeOnNetworkLoss = true)
            }; fail("must preserve interruption") } catch (_: IOException) { }
            assertEquals(if (noText) 2 else 3, payloads.size)
        }
    }

    @Test fun disconnectBetweenToolRoundsWaitsWithoutDiscardingOrRepeatingTheCompletedTool() = runBlocking {
        var online = true
        val waiting = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val fluctuating = object : NetworkAvailability {
            override val available get() = online
            override suspend fun awaitAvailable(timeoutMs: Long): Boolean {
                if (!online) { waiting.complete(Unit); ready.await(); online = true }
                return true
            }
        }
        var renders = 0
        val harness = SolveToolClient({ renders++; online = false; true }, context, DocumentStore(context))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            val index = payloads.size; payloads += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                .body((if (index == 0) draw(AiApiProtocol.CHAT_COMPLETIONS) else SolveToolClientTest.chatText(finalText))
                    .toResponseBody("text/event-stream".toMediaType())).build()
        }.build()
        val result = async(GenerationNetworkSession(fluctuating)) {
            harness.run(client, request, payload(AiApiProtocol.CHAT_COMPLETIONS), AiApiProtocol.CHAT_COMPLETIONS,
                emptyList(), false, JsonObject(emptyMap()), {}, {})!!
        }
        try {
            withTimeout(5000) { waiting.await() }
            assertEquals(1, payloads.size)
            assertEquals(1, renders)
        } finally { ready.complete(Unit) }
        assertTrue(result.await().reply.orderedFigures().single() is ReplyFigure.Plot)
        assertEquals(2, payloads.size)
        assertEquals(1, renders)
    }
}
