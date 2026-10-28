package com.moge.app.data.llm.tools

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.document.DocumentStore
import com.moge.app.data.llm.*
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.parse.orderedFigures
import com.moge.app.runtime.PosixAtomicFileShadow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class SolveToolClientTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = DocumentStore(context)
    private val rendered = mutableListOf<ReplyFigure>()
    private val harness = SolveToolClient({ rendered += it; true }, context, store)
    private val payloads = mutableListOf<JsonObject>()
    private val samples = mutableListOf<UsageSample?>()
    private val events = mutableListOf<StreamEvent>()
    private val request = Request.Builder().url("https://gateway.example/v1/chat/completions").header("Authorization", "Bearer test-key").build()

    private fun transport(vararg replies: Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
        val index = payloads.size
        payloads += Json.parseToJsonElement(buffer.readUtf8()).jsonObject
        val (code, body) = replies[index]
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody("text/event-stream".toMediaType())).build()
    }.build()

    private fun payload(protocol: AiApiProtocol, extra: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("model", "test-model"); put("max_tokens", 4096)
        if (protocol == AiApiProtocol.RESPONSES) {
            put("instructions", SOLVE_SYSTEM_PROMPT)
            put("input", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "ORIGINAL-QUESTION") }) })
        } else put("messages", buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", SOLVE_SYSTEM_PROMPT) })
            add(buildJsonObject { put("role", "user"); put("content", "ORIGINAL-QUESTION") })
        })
        extra.forEach { (k, v) -> put(k, v) }
    }

    private suspend fun run(client: OkHttpClient, protocol: AiApiProtocol = AiApiProtocol.CHAT_COMPLETIONS,
        messages: List<ChatMessage> = listOf(ChatMessage("user", "ORIGINAL-QUESTION")),
        initial: JsonObject = payload(protocol)) = harness.run(client, request, initial, protocol, messages, false,
        buildJsonObject { put("reasoning_effort", "low") }, samples::add, events::add)

    @Test fun ordinaryQuestionCompletesInOneStreamingRequestWithSmallDeclarations() = runBlocking {
        val final = """{"conversation_title":"代数","answer":"4","reply":"二加二等于四。"}"""
        val result = run(transport(200 to chatText(final)))!!
        assertEquals("二加二等于四。", result.reply.reply)
        assertEquals(1, payloads.size); assertEquals(1, samples.size)
        assertEquals(listOf(final), events.filterIsInstance<StreamEvent.AnswerDelta>().map { it.text })
        val first = payloads.single()
        val promptChars = SOLVE_SYSTEM_PROMPT.length + first["tools"].toString().length
        assertTrue("common prompt + schemas = $promptChars vs ${LEGACY_SOLVE_SYSTEM_PROMPT.length}", promptChars < LEGACY_SOLVE_SYSTEM_PROMPT.length * .35)
        assertFalse(first.toString().contains("textbook_dual_branch"))
        assertFalse(first.containsKey("response_format")) // JSON format mode can conflict with function calling.
        assertTrue(first["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
    }

    @Test fun guideThenBatchedMixedFiguresReplayReasoningAndReturnCompactResults() = runBlocking {
        val guide = chatCalls(call("g", "get_figure_guide", """{"kind":"curve"}"""))
        val draw = chatCalls(call("d", "render_figure", FigureToolsTest.args(1, "diagram", FigureToolsTest.DIAGRAM).toString()),
            call("p", "render_figure", FigureToolsTest.args(2, "plot", FigureToolsTest.CURVE).toString()), reasoning = "tool planning")
        val final = """{"reply":"流程。\n[[FIGURE:1]]\n曲线。\n[[FIGURE:2]]"}"""
        val result = run(transport(200 to guide, 200 to draw, 200 to chatText(final)))!!
        assertEquals(3, payloads.size); assertEquals(3, samples.size)
        assertTrue(result.reply.orderedFigures()[0] is ReplyFigure.Diagram)
        assertTrue(result.reply.orderedFigures()[1] is ReplyFigure.Plot)
        val third = payloads.last()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("tool planning", third.last { it["role"]?.jsonPrimitive?.content == "assistant" }["reasoning_content"]!!.jsonPrimitive.content)
        val results = third.filter { it["role"]?.jsonPrimitive?.content == "tool" }.takeLast(2)
        assertTrue(results.all { it["content"]!!.jsonPrimitive.content.length < 100 })
        assertFalse(results.toString().contains("base64"))
        assertEquals(listOf(final), events.filterIsInstance<StreamEvent.AnswerDelta>().map { it.text })
    }

    @Test fun fragmentedChatCallArgumentsAreReassembledWithoutLeakingIntoAnswer() = runBlocking {
        val args = FigureToolsTest.args(1, "plot", FigureToolsTest.CURVE).toString()
        val prefix = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("id", "draw"); put("type", "function")
                put("function", buildJsonObject { put("name", "render_figure"); put("arguments", args.take(20)) })
            }) }) })
        }) }) }
        val suffix = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("tool_calls", buildJsonArray { add(buildJsonObject {
                put("index", 0); put("function", buildJsonObject { put("arguments", args.drop(20)) })
            }) }) }); put("finish_reason", "tool_calls")
        }) }) }
        val result = run(transport(200 to (sse(prefix) + sse(suffix) + "data: [DONE]\n\n"), 200 to chatText("""{"reply":"图。\n[[FIGURE:1]]"}""")))!!
        assertEquals(1, rendered.size)
        assertTrue(result.reply.orderedFigures().single() is ReplyFigure.Plot)
        assertEquals(1, events.filterIsInstance<StreamEvent.AnswerDelta>().size)
    }

    @Test fun finalProseStreamsWhileLocalRenderingIsPendingAndSolveWaitsBeforeReturning() = runBlocking {
        val renderStarted = CompletableDeferred<Unit>()
        val releaseRender = CompletableDeferred<Unit>()
        val proseStarted = CompletableDeferred<Unit>()
        val parallel = SolveToolClient({ renderStarted.complete(Unit); releaseRender.await(); true }, context, store)
        val network = transport(200 to chatCalls(call("p", "render_figure", FigureToolsTest.args(1, "plot", FigureToolsTest.CURVE).toString())),
            200 to chatText("""{"reply":"同一份规格绘图。\n[[FIGURE:1]]"}"""))
        val solve = async {
            parallel.run(network, request, payload(AiApiProtocol.CHAT_COMPLETIONS), AiApiProtocol.CHAT_COMPLETIONS,
                emptyList(), false, JsonObject(emptyMap()), samples::add, onEvent = { event ->
                    if (event is StreamEvent.AnswerDelta) proseStarted.complete(Unit)
                })
        }
        try {
            withTimeout(5000) { renderStarted.await(); proseStarted.await() }
            assertEquals(2, payloads.size)
            assertFalse(solve.isCompleted)
            assertTrue(payloads.last().toString().contains("accepted"))
        } finally { releaseRender.complete(Unit) }
        assertTrue(solve.await()!!.reply.orderedFigures().single() is ReplyFigure.Plot)
    }

    @Test fun toolRoundsOnlyAppendToExactPriorMessagesAndKeepDefinitionsAndInstructionsStable() = runBlocking {
        for (protocol in listOf(AiApiProtocol.CHAT_COMPLETIONS, AiApiProtocol.RESPONSES)) {
            payloads.clear()
            val responses = protocol == AiApiProtocol.RESPONSES
            fun round(id: String, name: String, args: String): String = if (!responses) chatCalls(call(id, name, args), reasoning = "same-plan")
                else sse(buildJsonObject {
                    put("type", "response.completed"); put("response", buildJsonObject { put("output", buildJsonArray {
                        add(buildJsonObject { put("type", "reasoning"); put("id", "reason-$id"); put("encrypted_content", "signature-$id") })
                        add(buildJsonObject { put("type", "function_call"); put("id", "item-$id"); put("call_id", id); put("name", name); put("arguments", args) })
                    }) })
                })
            val sequence = if (responses) "input" else "messages"
            val initial = payload(protocol)
            val history = initial[sequence]!!.jsonArray.dropLast(1) + listOf(
                buildJsonObject { put("role", "user"); put("content", "原封不动的旧时间与题目") },
                buildJsonObject { put("role", "assistant"); put("content", "原封不动的历史正文") },
                buildJsonObject { put("role", "user"); put("content", buildJsonArray {
                    add(buildJsonObject { put("type", if (responses) "input_text" else "text"); put("text", "固定本轮时间与原题") })
                    add(buildJsonObject {
                        put("type", if (responses) "input_image" else "image_url")
                        if (responses) put("image_url", "data:image/jpeg;base64,ORIGINAL-IMAGE")
                        else put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,ORIGINAL-IMAGE"); put("detail", "high") })
                    })
                }) },
            )
            val final = """{"reply":"相同推导。\n[[FIGURE:1]]"}"""
            run(transport(200 to round("g1", "get_figure_guide", """{"kind":"curve"}"""),
                200 to round("g2", "get_figure_guide", """{"kind":"curve"}"""),
                200 to round("p", "render_figure", FigureToolsTest.args(1, "plot", FigureToolsTest.CURVE).toString()),
                200 to if (responses) responseFinal(final) else chatText(final)), protocol,
                initial = JsonObject(initial + (sequence to JsonArray(history))))
            assertEquals(4, payloads.size)
            for ((previous, next) in payloads.zipWithNext()) {
                val prefix = previous[sequence]!!.jsonArray
                assertEquals(prefix.toString(), JsonArray(next[sequence]!!.jsonArray.take(prefix.size)).toString())
                assertEquals(previous["tools"].toString(), next["tools"].toString())
                assertEquals(previous["instructions"], next["instructions"])
                assertTrue(next[sequence].toString().contains("ORIGINAL-IMAGE"))
            }
            val outputs = payloads.last()[sequence]!!.jsonArray.map { it.jsonObject }.mapNotNull {
                (it[if (responses) "output" else "content"] as? JsonPrimitive)?.content
            }
            assertEquals(1, outputs.count { it == FigureToolCatalog.guide("curve") })
            assertEquals(1, outputs.count { it.contains("已在本轮前文提供") })
        }
    }

    @Test fun responsesReplaysEncryptedReasoningAndFunctionItemsWithCorrectCallId() = runBlocking {
        val fn = buildJsonObject {
            put("id", "fc-1"); put("type", "function_call"); put("call_id", "draw-1"); put("name", "render_figure")
            put("arguments", FigureToolsTest.args(1, "plot", FigureToolsTest.CURVE).toString())
        }
        val reasoning = buildJsonObject { put("id", "rs-1"); put("type", "reasoning"); put("encrypted_content", "opaque-signature"); put("summary", JsonArray(emptyList())) }
        val first = sse(buildJsonObject { put("type", "response.output_item.added"); put("output_index", 1); put("item", JsonObject(fn + ("arguments" to JsonPrimitive("")))) }) +
            sse(buildJsonObject { put("type", "response.function_call_arguments.delta"); put("output_index", 1); put("delta", fn["arguments"]!!) }) +
            sse(buildJsonObject { put("type", "response.completed"); put("response", buildJsonObject { put("output", JsonArray(listOf(reasoning, fn))) }) })
        val finalText = """{"reply":"图像。\n[[FIGURE:1]]"}"""
        val final = responseFinal(finalText, streamDelta = true)
        val result = run(transport(200 to first, 200 to final), AiApiProtocol.RESPONSES)!!
        assertEquals("图像。\n[[FIGURE:1]]", result.reply.reply)
        val items = payloads.last()["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(reasoning, items.first { it["type"]?.jsonPrimitive?.content == "reasoning" })
        val toolResult = items.first { it["type"]?.jsonPrimitive?.content == "function_call_output" }
        assertEquals("draw-1", toolResult["call_id"]!!.jsonPrimitive.content)
        assertEquals(1, events.filterIsInstance<StreamEvent.AnswerDelta>().size)
        assertEquals(2, samples.size)
    }

    @Test fun invalidSpecReturnsFeedbackThenRepairReusesItsOriginalId() = runBlocking {
        val bad = FigureToolsTest.args(1, "diagram", """{"nodes":[{"id":"a","label":"A"}],"edges":[{"from":"a","to":"missing"}]}""")
        val result = run(transport(200 to chatCalls(call("bad", "render_figure", bad.toString())),
            200 to chatCalls(call("repair", "render_figure", FigureToolsTest.args(1, "diagram", FigureToolsTest.DIAGRAM).toString())),
            200 to chatText("""{"reply":"[[FIGURE:1]]"}""")))!!
        assertEquals(1, result.reply.orderedFigures().size); assertEquals(1, rendered.size)
        assertTrue(payloads[1].toString().contains("连线终点不存在"))
    }

    @Test fun targetedMissingFigureRepairCanReturnOnlyTheRenderedFigure() = runBlocking {
        val draw = chatCalls(call("repair", "render_figure", FigureToolsTest.args(1, "plot", FigureToolsTest.CURVE).toString()))
        val result = run(transport(200 to draw, 200 to chatText("""{"reply":"","answer":""}""")))!!
        assertEquals("", result.reply.reply)
        assertTrue(result.reply.orderedFigures().single() is ReplyFigure.Plot)
        assertEquals(2, payloads.size)
    }

    @Test fun boundedLoopForcesOneFinalAnswerWithoutTools() = runBlocking {
        val repeated = chatCalls(call("g", "get_figure_guide", """{"kind":"coding"}"""))
        val replies = List(SolveToolClient.MAX_TOOL_ROUNDS) { 200 to repeated } + (200 to chatText("""{"reply":"已有结果"}"""))
        val result = run(transport(*replies.toTypedArray()))!!
        assertEquals(SolveToolClient.MAX_TOOL_ROUNDS + 1, payloads.size)
        assertFalse(payloads.last().containsKey("tools"))
        assertTrue(result.reply.warnings.any { it.contains("上限") })
    }

    @Test fun reasoningOnlyResponseGetsOneRecoveryAndPreservesAllEarlierUsage() = runBlocking {
        val empty = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"},\"finish_reason\":\"length\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":4096}}\n\ndata: [DONE]\n\n"
        val result = run(transport(200 to empty, 200 to chatText("""{"reply":"最终正文"}""")))!!
        assertEquals("最终正文", result.reply.reply); assertEquals(2, samples.size)
        assertEquals(100L, samples.first()!!.inputTokens)
        assertFalse(payloads.last().containsKey("tools"))
        assertEquals("low", payloads.last()["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test fun unsupportedToolsReturnCompatibilitySignalAndAreCachedPerEndpointAndCredential() = runBlocking {
        val network = transport(400 to "Unknown parameter: tools")
        assertNull(run(network)); assertNull(run(network))
        assertEquals(1, payloads.size); assertEquals(1, samples.size)
        assertFalse(isFunctionToolsRejected(ModelException(ModelException.Kind.SERVER, "tools schema invalid", rejectedHttpStatus = 400)))
        assertFalse(isFunctionToolsRejected(ModelException(ModelException.Kind.SERVER, "unsupported web_search", rejectedHttpStatus = 400)))
    }

    @Test fun realFailuresNeverFallbackOrReplayEvenIfErrorMentionsTools() = runBlocking {
        for (code in listOf(401, 403, 429, 500, 400)) {
            payloads.clear(); samples.clear()
            try { run(transport(code to "invalid request tools schema")); fail("must fail") }
            catch (_: ModelException) { }
            assertEquals(1, payloads.size); assertEquals(1, samples.size)
        }
    }

    @Test fun explicitStreamRejectionUsesOneNonstreamRequestAndRemembersItForToolContinuation() = runBlocking {
        val jsonCall = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("message", buildJsonObject { put("role", "assistant"); put("tool_calls", JsonArray(listOf(call("g", "get_figure_guide", """{"kind":"curve"}""")))) })
        }) }) }.toString()
        val final = """{"choices":[{"message":{"role":"assistant","content":"{\"reply\":\"完成\"}"},"finish_reason":"stop"}]}"""
        assertEquals("完成", run(transport(400 to "stream is not supported", 200 to jsonCall, 200 to final))!!.reply.reply)
        assertEquals(listOf(true, false, false), payloads.map { it["stream"]!!.jsonPrimitive.boolean })
        assertEquals(3, samples.size)
    }

    @Test fun negotiatedBudgetIsReusedAcrossToolRounds() = runBlocking {
        val calls = chatCalls(call("g", "get_figure_guide", """{"kind":"curve"}"""))
        run(transport(400 to "max_tokens must be <= 1024", 200 to calls, 200 to chatText("""{"reply":"完成"}""")))
        assertEquals(listOf(4096, 1024, 1024), payloads.map { it["max_tokens"]!!.jsonPrimitive.int })
        assertEquals(3, samples.size)
    }

    @Test fun negotiatedEffortDoesNotOverrideLowerRecoveryEffort() = runBlocking {
        val empty = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"analysis\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n"
        run(transport(400 to "reasoning_effort unsupported; supported values: 'medium', 'low'", 200 to empty,
            200 to chatText("""{"reply":"完成"}""")), initial = payload(AiApiProtocol.CHAT_COMPLETIONS,
            buildJsonObject { put("reasoning_effort", "high") }))
        assertEquals(listOf("high", "medium", "low"), payloads.map { it["reasoning_effort"]!!.jsonPrimitive.content })
    }

    @Test fun renamedThenReducedBudgetStillAppliesToTheNextToolRound() = runBlocking {
        val calls = chatCalls(call("g", "get_figure_guide", """{"kind":"curve"}"""))
        run(transport(400 to "max_tokens unsupported", 400 to "max_completion_tokens must be <= 1024",
            200 to calls, 200 to chatText("""{"reply":"完成"}""")))
        assertEquals(1024, payloads.last()["max_completion_tokens"]!!.jsonPrimitive.int)
        assertFalse(payloads.last().containsKey("max_tokens"))
        assertEquals(4, samples.size)
    }

    @Test fun connectionCutKeepsPartialTextAndNeverStartsASecondRequest() = runBlocking {
        var requests = 0
        val partial = "data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"reply\\\":\\\"保留这段\"}}]}\n\n"
        val network = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            val pending = Buffer().writeUtf8(partial)
            val source = object : Source {
                override fun read(sink: Buffer, byteCount: Long): Long = if (pending.size > 0) pending.read(sink, byteCount) else throw IOException("cut")
                override fun timeout() = Timeout.NONE
                override fun close() { }
            }.buffer()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(object : ResponseBody() {
                override fun contentType() = "text/event-stream".toMediaType()
                override fun contentLength() = -1L
                override fun source() = source
            }).build()
        }.build()
        try { run(network); fail("must be interrupted") } catch (_: IOException) { }
        assertEquals(1, requests); assertEquals(1, samples.size)
        assertTrue(events.filterIsInstance<StreamEvent.AnswerDelta>().single().text.contains("保留这段"))
        assertFalse(events.any { it == StreamEvent.AnswerReset })
    }

    @Test fun cancelledLocalRenderDoesNotLaunchFinalGeneration() = runBlocking {
        val cancelled = SolveToolClient({ throw CancellationException("cancel") }, context, store)
        val network = transport(200 to chatCalls(call("p", "render_figure", FigureToolsTest.args(1, "plot", FigureToolsTest.CURVE).toString())))
        try {
            cancelled.run(network, request, payload(AiApiProtocol.CHAT_COMPLETIONS), AiApiProtocol.CHAT_COMPLETIONS,
                emptyList(), false, JsonObject(emptyMap()), samples::add, events::add)
            fail("must cancel")
        } catch (_: CancellationException) { }
        assertEquals(1, payloads.size)
    }

    @Test fun documentReadAndDrawingShareOneStreamingLoopWithoutChangingOriginalQuestion() = runBlocking {
        val source = File(context.cacheDir, "harness-evidence.txt").apply { writeText("evidence-5481") }
        val attachment = store.attachment(store.import(Uri.fromFile(source)))
        val read = chatCalls(call("r", "read_document", """{"document_id":"${attachment.id}","locator":"section:1"}"""))
        val plot = chatCalls(call("p", "render_figure", FigureToolsTest.args(1, "plot", FigureToolsTest.CURVE).toString()))
        val result = run(transport(200 to read, 200 to plot, 200 to chatText("""{"reply":"evidence-5481\n[[FIGURE:1]]"}""")),
            messages = listOf(ChatMessage("user", "ORIGINAL-QUESTION", documentPaths = listOf(attachment.path))))!!
        assertFalse(payloads.first().toString().contains("evidence-5481"))
        assertTrue(payloads[1].toString().contains("evidence-5481"))
        assertTrue(result.reply.reply.contains("moge-document://"))
        assertEquals(1, result.reply.orderedFigures().size)
    }

    companion object {
        fun call(id: String, name: String, arguments: String) = buildJsonObject {
            put("id", id); put("type", "function")
            put("function", buildJsonObject { put("name", name); put("arguments", arguments) })
        }
        fun sse(value: JsonObject) = "data: $value\n\n"
        fun chatCalls(vararg calls: JsonObject, reasoning: String = "") = sse(buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("delta", buildJsonObject { put("tool_calls", JsonArray(calls.mapIndexed { index, obj -> JsonObject(obj + ("index" to JsonPrimitive(index))) }));
                    if (reasoning.isNotEmpty()) put("reasoning_content", reasoning) })
                put("finish_reason", "tool_calls")
            }) })
        }) + "data: [DONE]\n\n"
        fun chatText(text: String) = sse(buildJsonObject {
            put("choices", buildJsonArray { add(buildJsonObject {
                put("delta", buildJsonObject { put("content", text) }); put("finish_reason", "stop")
            }) })
        }) + "data: [DONE]\n\n"
        fun responseFinal(text: String, streamDelta: Boolean = false): String {
            val terminal = sse(buildJsonObject {
                put("type", "response.completed"); put("response", buildJsonObject { put("output", buildJsonArray {
                    add(buildJsonObject { put("type", "message"); put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "output_text"); put("text", text) })
                    }) })
                }) })
            })
            return (if (streamDelta) sse(buildJsonObject { put("type", "response.output_text.delta"); put("delta", text) }) else "") + terminal
        }
    }
}
