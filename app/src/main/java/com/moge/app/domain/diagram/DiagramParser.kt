package com.moge.app.domain.diagram

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 解析模型给出的 `diagrams` 数组。
 *
 * 校验结构与规模，把写坏的图挡在渲染之前：
 * - 重复 id → 该图拒收（无法唯一寻址，布局必然错乱）；
 * - 连线的端点不存在 → 该图拒收（画出来就是断线）；
 * - 超长文字拒收，避免截断公式而改变含义；
 * - 单张图不合法只丢这一张，并记一条可展示的警告，**不影响正文与其它图**。
 */
object DiagramParser {

    fun parseArray(array: JsonArray?, warnings: MutableList<String>): List<DiagramSpec> =
        parseSlots(array, warnings).filterNotNull()

    fun parseSlots(array: JsonArray?, warnings: MutableList<String>): List<DiagramSpec?> {
        if (array == null) return emptyList()
        if (array.size > DiagramLimits.MAX_DIAGRAMS) warnings += "框图最多支持 ${DiagramLimits.MAX_DIAGRAMS} 张，超出部分已忽略"
        return array.take(DiagramLimits.MAX_DIAGRAMS).mapIndexed { index, element ->
            val obj = element as? JsonObject
                ?: run { warnings += "第 ${index + 1} 张框图不是对象，已忽略"; return@mapIndexed null }
            runCatching { parseOne(obj) }
                .onFailure { warnings += "有一张框图没能生成：${it.message ?: "参数不合法"}" }
                .getOrNull()
        }
    }

    fun parseOne(obj: JsonObject): DiagramSpec {
        val rawNodes = obj["nodes"] as? JsonArray ?: error("缺少 nodes")
        val rawEdges = obj["edges"] as? JsonArray ?: error("缺少 edges")
        require(rawNodes.size in 1..DiagramLimits.MAX_NODES) {
            "节点数需在 1~${DiagramLimits.MAX_NODES} 之间，收到 ${rawNodes.size}"
        }
        require(rawEdges.size <= DiagramLimits.MAX_EDGES) {
            "连线数需在 0~${DiagramLimits.MAX_EDGES} 之间，收到 ${rawEdges.size}"
        }

        val nodes = ArrayList<DiagramNode>(rawNodes.size)
        val seen = HashSet<String>()
        for (element in rawNodes) {
            val node = parseNode(element as? JsonObject ?: error("节点不是对象"))
            require(seen.add(node.id)) { "节点 id 重复：${node.id}" }
            nodes += node
        }

        val edges = ArrayList<DiagramEdge>(rawEdges.size)
        for (element in rawEdges) {
            val edge = parseEdge(element as? JsonObject ?: error("连线不是对象"))
            // 端点必须存在：不存在的端点画出来是断线，不如整张拒收让用户重问
            require(edge.from in seen) { "连线起点不存在：${edge.from}" }
            require(edge.to in seen) { "连线终点不存在：${edge.to}" }
            require(edge.from != edge.to) { "自环请拆成反馈支路节点" }
            edges += edge
        }

        return DiagramSpec(
            title = obj.boundedText("title", DiagramLimits.MAX_TITLE_CHARS).orEmpty(),
            nodes = nodes,
            edges = edges,
            direction = parseDirection(obj["direction"]),
            profile = parseProfile(obj["profile"] ?: obj["template"]),
        )
    }

    private fun parseNode(obj: JsonObject): DiagramNode {
        val id = obj.boundedText("id", DiagramLimits.MAX_ID_CHARS).orEmpty()
        require(id.isNotEmpty()) { "节点缺少 id" }
        val label = obj.boundedText("label", DiagramLimits.MAX_LABEL_CHARS).orEmpty()
        require(label.isNotEmpty()) { "节点 $id 缺少 label" }
        return DiagramNode(
            id = id,
            label = label,
            shape = parseShape(obj["shape"]),
            glyph = obj.boundedText("glyph", 4),
            subLabel = obj.boundedText("subLabel", DiagramLimits.MAX_SUB_LABEL_CHARS),
            row = obj.index("row", DiagramLimits.MAX_ROWS),
            column = obj.index("column", DiagramLimits.MAX_COLUMNS),
            role = obj.boundedText("role", DiagramLimits.MAX_ROLE_CHARS),
        )
    }

    private fun parseEdge(obj: JsonObject): DiagramEdge {
        val from = obj.text("from")?.trim().orEmpty()
        val to = obj.text("to")?.trim().orEmpty()
        require(from.isNotEmpty() && to.isNotEmpty()) { "连线缺少 from / to" }
        return DiagramEdge(
            from = from,
            to = to,
            label = obj.boundedText("label", DiagramLimits.MAX_LABEL_CHARS),
            fromPort = parsePort(obj["fromPort"]),
            toPort = parsePort(obj["toPort"]),
            dashed = (obj["dashed"] as? JsonPrimitive)?.contentOrNull == "true",
            polarity = obj.boundedText("polarity", 1)?.also {
                require(it == "+" || it == "-" || it == "−") { "polarity 只能是 + 或 -" }
            }?.replace('-', '−'),
        )
    }

    private fun parseShape(element: JsonElement?): DiagramNodeShape {
        val raw = (element as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return DiagramNodeShape.BLOCK
        return when (raw) {
            "block", "box", "rect", "rectangle" -> DiagramNodeShape.BLOCK
            "mixer", "multiply", "multiplier", "product", "circle" -> DiagramNodeShape.MIXER
            "sum", "adder", "add", "summation", "merge" -> DiagramNodeShape.SUM
            "io", "input", "output", "signal", "text", "label", "plain" -> DiagramNodeShape.IO
            "junction", "point", "connector", "dot", "node" -> DiagramNodeShape.JUNCTION
            else -> error("不支持的节点形状：$raw")
        }
    }

    private fun parsePort(element: JsonElement?): DiagramPort {
        val raw = (element as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: return DiagramPort.AUTO
        return when (raw) {
            "left", "west", "l" -> DiagramPort.LEFT
            "right", "east", "r" -> DiagramPort.RIGHT
            "top", "north", "t" -> DiagramPort.TOP
            "bottom", "south", "b" -> DiagramPort.BOTTOM
            "auto" -> DiagramPort.AUTO
            else -> error("不支持的端口：$raw")
        }
    }

    private fun parseDirection(element: JsonElement?): DiagramDirection {
        val value = (element as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        require(value == null || value == "lr") { "目前框图支持从左到右（LR）的主链布局" }
        return DiagramDirection.LR
    }

    /**
     * Unknown profiles fall back to the generic layout.  This keeps a newer
     * model from making an otherwise valid old-style diagram disappear on an
     * older client; known profiles remain explicit and deterministic.
     */
    private fun parseProfile(element: JsonElement?): DiagramLayoutProfile {
        val value = (element as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: return DiagramLayoutProfile.GENERIC
        return when (value) {
            "textbook_dual_branch", "textbook-dual-branch", "dual_branch",
            "dual-branch", "ssb", "ssb_demodulator", "ssb-demodulator" ->
                DiagramLayoutProfile.TEXTBOOK_DUAL_BRANCH
            "iq_demodulator", "iq-demodulator", "iq_demod", "iq-demod",
            "qpsk_demodulator", "16qam_demodulator" -> DiagramLayoutProfile.IQ_DEMODULATOR
            "generic", "auto" -> DiagramLayoutProfile.GENERIC
            else -> DiagramLayoutProfile.GENERIC
        }
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.boundedText(key: String, max: Int): String? = text(key)
        ?.let(DiagramText::normalizeEscapedLineBreaks)
        ?.trim()?.also {
            require(it.length <= max) { "$key 超过 $max 字符，请精简标签" }
        }?.takeIf { it.isNotEmpty() }

    private fun JsonObject.index(key: String, max: Int): Int? {
        if (this[key] == null) return null
        val number = text(key)?.toIntOrNull()
        require(number != null && number in 0 until max) { "$key 需在 0~${max - 1} 之间" }
        return number
    }
}
