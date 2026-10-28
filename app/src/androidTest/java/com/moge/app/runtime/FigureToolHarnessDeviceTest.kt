package com.moge.app.runtime

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.document.DocumentStore
import com.moge.app.data.figure.DiagramImageStore
import com.moge.app.data.figure.PlotImageStore
import com.moge.app.data.llm.ChatMessage
import com.moge.app.data.llm.SOLVE_SYSTEM_PROMPT
import com.moge.app.data.llm.StreamEvent
import com.moge.app.data.llm.tools.SolveToolClient
import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.parse.orderedFigures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real device bitmap/sidecar checks with an in-process API fixture; never uses configured credentials. */
@RunWith(AndroidJUnit4::class)
class FigureToolHarnessDeviceTest {
    @Test fun chatToolsRenderMixedFiguresInBothThemes() = verify(AiApiProtocol.CHAT_COMPLETIONS)
    @Test fun responsesToolsRenderMixedFiguresInBothThemes() = verify(AiApiProtocol.RESPONSES)

    private fun verify(protocol: AiApiProtocol) = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val plots = PlotImageStore(context)
        val diagrams = DiagramImageStore(context)
        for (dark in listOf(false, true)) {
            val paths = mutableListOf<String>()
            val harness = SolveToolClient({ figure ->
                val path = when (figure) {
                    is ReplyFigure.Plot -> plots.render(figure.spec, dark)
                    is ReplyFigure.Diagram -> diagrams.render(figure.spec, dark)
                    ReplyFigure.Missing -> null
                }
                if (path != null) paths += path
                path != null
            }, context, DocumentStore(context))
            var requests = 0
            val calls = listOf(
                "diagram" to Json.parseToJsonElement("""{"title":"编码器","profile":"convolutional_encoder","coding":{"version":1,"memory":2,"generators":[[0,1,2],[0,2]]}}"""),
                "plot" to Json.parseToJsonElement("""{"title":"心形线","polar":{"radiusMax":2.2},"series":[{"expr":"1-cos(theta)"}]}"""),
            ).mapIndexed { index, (type, spec) -> buildJsonObject {
                val arguments = buildJsonObject { put("figure_id", index + 1); put("type", type); put("spec", spec) }.toString()
                if (protocol == AiApiProtocol.RESPONSES) {
                    put("type", "function_call"); put("id", "fc-$index"); put("call_id", "call-$index"); put("name", "render_figure"); put("arguments", arguments)
                } else {
                    put("id", "call-$index"); put("index", index); put("type", "function")
                    put("function", buildJsonObject { put("name", "render_figure"); put("arguments", arguments) })
                }
            } }
            val text = """{"conversation_title":"绘图工具测试","reply":"编码器。\n[[FIGURE:1]]\n极坐标。\n[[FIGURE:2]]"}"""
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val toolRound = requests++ == 0
                val response = if (protocol == AiApiProtocol.RESPONSES) buildJsonObject {
                    put("type", "response.completed"); put("response", buildJsonObject {
                        put("output", if (toolRound) JsonArray(calls) else buildJsonArray { add(buildJsonObject {
                            put("type", "message"); put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
                        }) })
                    })
                } else buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                    put("delta", buildJsonObject { if (toolRound) put("tool_calls", JsonArray(calls)) else put("content", text) })
                    put("finish_reason", if (toolRound) "tool_calls" else "stop")
                }) }) }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("ok")
                    .body(("data: $response\n\n" + if (protocol == AiApiProtocol.CHAT_COMPLETIONS) "data: [DONE]\n\n" else "").toResponseBody("text/event-stream".toMediaType())).build()
            }.build()
            val payload = buildJsonObject {
                put("model", "local-test"); put("stream", true)
                if (protocol == AiApiProtocol.RESPONSES) {
                    put("instructions", SOLVE_SYSTEM_PROMPT)
                    put("input", buildJsonArray { add(buildJsonObject { put("role", "user"); put("content", "画编码器和心形线") }) })
                } else put("messages", buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", SOLVE_SYSTEM_PROMPT) })
                    add(buildJsonObject { put("role", "user"); put("content", "画编码器和心形线") })
                })
            }
            val visible = mutableListOf<StreamEvent>()
            val result = harness.run(client, Request.Builder().url("https://local-fixture.invalid/v1").build(), payload,
                protocol, listOf(ChatMessage("user", "画编码器和心形线")), false, JsonObject(emptyMap()), {}, visible::add)!!
            assertEquals(2, requests); assertEquals(2, paths.size)
            assertTrue(result.reply.orderedFigures()[0] is ReplyFigure.Diagram)
            assertTrue(result.reply.orderedFigures()[1] is ReplyFigure.Plot)
            assertEquals(listOf(text), visible.filterIsInstance<StreamEvent.AnswerDelta>().map { it.text })
            for (path in paths) {
                assertTrue(File(path).length() > 1000)
                val bitmap = BitmapFactory.decodeFile(path)
                assertNotNull(bitmap)
                assertTrue(bitmap.width >= 500); assertTrue(bitmap.height >= 200)
                bitmap.recycle()
                val resolved = if (path.contains("/plots/")) plots.resolve(path, !dark) else diagrams.resolve(path, !dark)
                assertNotNull("Switching theme must recreate the figure from its saved spec", resolved)
                assertTrue(File(resolved!!).length() > 1000)
            }
        }
    }
}
