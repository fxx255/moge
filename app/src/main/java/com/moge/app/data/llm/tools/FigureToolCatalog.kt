package com.moge.app.data.llm.tools

import com.moge.app.data.llm.LEGACY_SOLVE_SYSTEM_PROMPT
import kotlinx.serialization.json.*

/** Small declarations are always available; the detailed contracts are returned only on demand. */
internal object FigureToolCatalog {
    val definitions: List<JsonObject> = listOf(
        definition("get_figure_guide", "按需读取本地绘图参数说明；每种kind本轮只需读取一次，普通解题无需调用。", buildJsonObject {
            put("kind", buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(listOf("curve", "polar", "constellation", "diagram", "coding").map(::JsonPrimitive)))
            })
        }),
        definition("render_figure", "校验后后台绘图。accepted即可用返回的[[FIGURE:n]]写正文，无需等待或轮询；图号1~4，修正沿用图号。", buildJsonObject {
            put("figure_id", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 4) })
            put("type", buildJsonObject { put("type", "string"); put("enum", JsonArray(listOf("plot", "diagram").map(::JsonPrimitive))) })
            put("spec", buildJsonObject { put("type", "object"); put("additionalProperties", true); put("description", "一张图的完整规格，参数见get_figure_guide") })
        }),
    )

    private fun definition(name: String, description: String, properties: JsonObject) = buildJsonObject {
        put("name", name); put("description", description); put("strict", false)
        put("parameters", buildJsonObject {
            put("type", "object"); put("properties", properties)
            put("required", JsonArray(properties.keys.map(::JsonPrimitive))); put("additionalProperties", false)
        })
    }

    // Reuse the compatibility contract so renderer rules have one source of truth.
    private val curveLines by lazy {
        LEGACY_SOLVE_SYSTEM_PROMPT.substringAfter("9. 需要画图时").substringBefore("10. 需要画**框图**时")
            .lines().filterNot { line ->
                listOf("图表要插在正文里", "每张图最多插入一次锚点", "没有画图需求时").any(line::contains)
            }
    }
    private val diagramLines by lazy {
        LEGACY_SOLVE_SYSTEM_PROMPT.substringAfter("10. 需要画**框图**时").substringBefore("输出格式（必须")
            .lines().filterNot { it.contains("diagrams 与 plots") }
    }

    fun guide(kind: String): String {
        val header = "把下面的一张图规格作为 render_figure 的 spec 对象提交，曲线 type=plot、框图/编码器 type=diagram。" +
            "不要包裹 plots/diagrams 数组。figure_id 从1开始，修正沿用同号；每张图在正文对应位置引用一次。\n"
        val lines = when (kind) {
            "curve", "polar", "constellation" -> curveLines.drop(1).filterNot { line ->
                (kind != "polar" && (line.contains("极坐标") || line.contains("polar.radiusMax"))) ||
                    (kind != "constellation" && line.contains("voronoi"))
            }
            "diagram" -> diagramLines.drop(1).filterNot { it.contains("卷积码编码器使用") }
            "coding" -> diagramLines.filter { it.contains("卷积码编码器使用") }
            else -> throw IllegalArgumentException("kind 只支持 curve/polar/constellation/diagram/coding")
        }
        return header + lines.joinToString("\n").trim()
    }
}
