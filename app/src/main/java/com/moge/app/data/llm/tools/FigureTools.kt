package com.moge.app.data.llm.tools

import com.moge.app.data.parse.ReplyFigure
import com.moge.app.data.parse.ReplyParser
import com.moge.app.data.parse.orderedFigures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Runtime supplies local rendering. The network layer does not depend on Android UI/renderers. */
fun interface FigureToolRenderer {
    suspend fun render(figure: ReplyFigure): Boolean
}

/** Per-turn state, never shared across conversations. Missing slots reserve their original IDs. */
internal class FigureTools(scope: CoroutineScope? = null, private val renderer: FigureToolRenderer) {
    private val renderQueue = scope?.let { FigureRenderQueue(it, renderer) }
    private val slots = arrayOfNulls<ReplyFigure>(MAX_FIGURES)
    private val attempts = IntArray(MAX_FIGURES)
    private val providedGuides = mutableSetOf<String>()
    private var highestId = 0
    val figures: List<ReplyFigure> get() = (0 until highestId).map { index ->
        (if (renderQueue != null) renderQueue.figure(index + 1) else slots[index]) ?: ReplyFigure.Missing
    }

    suspend fun awaitRenders() { renderQueue?.awaitAll() }

    suspend fun execute(name: String, arguments: JsonObject): String {
        currentCoroutineContext().ensureActive()
        return try {
            when (name) {
                "get_figure_guide" -> guide(arguments["kind"]?.jsonPrimitive?.content.orEmpty())
                "render_figure" -> render(arguments).toString()
                else -> error("未知工具：$name")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            buildJsonObject {
                put("status", "error"); put("error", error.message.orEmpty().take(1200))
                put("instruction", "仅修正失败参数并沿用原figure_id；不要重画成功的其它图。")
            }.toString()
        }
    }

    private fun guide(kind: String): String {
        if (kind in providedGuides) return "绘图说明 $kind 已在本轮前文提供，请沿用该说明直接提交 render_figure。"
        return FigureToolCatalog.guide(kind).also { providedGuides += kind }
    }

    private suspend fun render(arguments: JsonObject): JsonObject {
        val id = arguments["figure_id"]?.jsonPrimitive?.intOrNull ?: error("缺少整数figure_id")
        require(id in 1..MAX_FIGURES) { "每轮最多4张图，figure_id需为1~4" }
        highestId = maxOf(highestId, id)
        require(attempts[id - 1]++ < MAX_ATTEMPTS) { "此图已达到3次尝试上限，请保留已有结果并完成文字回答" }
        val spec = arguments["spec"] as? JsonObject ?: error("spec必须是一张图的对象")
        require(spec.toString().length <= 80_000) { "单图参数过长，请使用表达式或专用模板" }
        val field = when (arguments["type"]?.jsonPrimitive?.content) {
            "plot" -> "plots"
            "diagram" -> "diagrams"
            else -> error("type只支持plot/diagram")
        }
        val parsed = ReplyParser.parse(buildJsonObject {
            put("reply", ""); put(field, JsonArray(listOf(spec)))
        }.toString())
        require(parsed.warnings.isEmpty()) { parsed.warnings.joinToString("；") }
        val figure = parsed.orderedFigures().singleOrNull()?.takeUnless { it == ReplyFigure.Missing }
            ?: error("图形参数无效，请读取对应绘图说明后修正")
        if (renderQueue != null) {
            renderQueue.submit(id, figure)
            currentCoroutineContext().ensureActive()
        } else if (slots[id - 1] != figure) {
            check(renderer.render(figure)) { "本地图片渲染失败，请简化规格后重试；不要声称此图已生成" }
            currentCoroutineContext().ensureActive()
            slots[id - 1] = figure
        }
        return buildJsonObject {
            put("figure_id", id); put("status", if (renderQueue == null) "ok" else "accepted"); put("anchor", "[[FIGURE:$id]]")
        }
    }

    companion object { const val MAX_FIGURES = 4; private const val MAX_ATTEMPTS = 3 }
}
