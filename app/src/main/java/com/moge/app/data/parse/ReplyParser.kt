package com.moge.app.data.parse

import com.moge.app.domain.diagram.DiagramParser
import com.moge.app.domain.diagram.DiagramSpec
import com.moge.app.domain.plot.Axis
import com.moge.app.domain.plot.ExprEval
import com.moge.app.domain.plot.MarkArea
import com.moge.app.domain.plot.MarkLine
import com.moge.app.domain.plot.PlotSpec
import com.moge.app.domain.plot.Series
import com.moge.app.domain.plot.ShadeRegion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 解析后的模型回复：最终答案、完整讲解、标题及图表。 */
data class ParsedReply(
    val reply: String,
    val warnings: List<String> = emptyList(),
    /** 模型因达到输出长度上限（finish_reason=length）被截断。 */
    val truncated: Boolean = false,
    /** 模型要求绘制的图表：结构化参数，由客户端本地渲染成图后挂到这条消息上。 */
    val plots: List<PlotSpec> = emptyList(),
    /**
     * 模型要求绘制的**结构化框图**（通信原理框图等）：只给拓扑，坐标由本地布局器算。
     *
     * 与 [plots] 是两个独立通道：曲线图继续走 ExprEval + 绘图器，
     * 框图走 DiagramLayout + DiagramRenderer。两者最终都渲染成 PNG，
     * 并按 plots 在前、diagrams 在后的顺序共同占用 `[[FIGURE:n]]` 编号。
     */
    val diagrams: List<DiagramSpec> = emptyList(),
    /** Preserve failed slots so figure anchors never shift to a different image. */
    val plotSlots: List<PlotSpec?> = plots,
    val diagramSlots: List<DiagramSpec?> = diagrams,
    /**
     * 本轮服务商返回的原始思考内容（reasoning 通道）。
     *
     * 仅用于临时诊断；不能据此从思考内容提取正文，即使其中含有示例 JSON。**不参与持久化**。
     */
    val rawReasoning: String = "",
    /** 会话标题，用于历史列表，不显示在解答纸中。 */
    val conversationTitle: String? = null,
    /** 可独立展示的最终答案；缺字段或非字符串时为空，不从讲解中猜测。 */
    val finalAnswer: String = "",
)

/**
 * 修复 JSON 字符串里的 LaTeX 反斜杠（模型最常踩的坑）。
 *
 * 模型在 reply 正文里写公式时经常直接输出单反斜杠（`\frac`、`\alpha`、`\cdot`），
 * 而 JSON 只允许 `\" \\ \/ \b \f \n \r \t \uXXXX` 这几种转义：
 * - `\a`、`\c` 这类**非法序列会让整包解析失败**，界面只能把协议原文（连同
 *   `"plan_actions": []`）当回答显示出来；
 * - `\f`、`\n`、`\t` 虽然合法，却会把 `\frac` 悄悄吃成「换页符 + rac」，公式被改坏。
 *
 * 判据：LaTeX 命令总是「反斜杠 + 多个字母」，而 JSON 转义只跟单个字符。
 * 因此字符串内部「反斜杠 + b/f/n/r/t + 又一个 ASCII 字母」按 LaTeX 处理补成双反斜杠，
 * 其余非法字母同理；真正的换行 `\n`（后面跟中文或标点）不受影响。
 */
internal fun sanitizeJsonEscapes(raw: String): String {
    if ('\\' !in raw) return raw
    val out = StringBuilder(raw.length + 32)
    var inString = false
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '"') {
            inString = !inString
            out.append(c)
            i++
            continue
        }
        if (!inString || c != '\\') {
            out.append(c)
            i++
            continue
        }
        val next = raw.getOrNull(i + 1)
        when {
            next == null -> { out.append("\\\\"); i++ }
            // 已经是合法转义（\\" \\\\ \/）：原样保留
            next == '"' || next == '\\' || next == '/' -> { out.append(c).append(next); i += 2 }
            next == 'u' && i + 5 < raw.length &&
                raw.substring(i + 2, i + 6).all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } -> {
                out.append(c).append(next).append(raw, i + 2, i + 6)
                i += 6
            }
            // \n 的歧义最大，必须看后面的字母到底拼成什么：
            // 「正文换行 + 英文单词开头」（\nf(x)、\nuv、\nnetwork）里的 \n 是真正的换行，
            // 而 \nabla、\neq、\notin 才是 LaTeX 命令。
            // 早先的判据是「转义字母后面还跟着字母」就按 LaTeX 处理，结果把前者也误还原成
            // 字面反斜杠 n——换行被吃掉、文字连成一片，正文里直接冒出 \nf(x)、\nuv。
            next == 'n' ->
                // 从 n 本身开始取词（i 指向反斜杠），否则 \nabla 会被截成 abla
                if (isLatexNCommand(raw, i + 1)) {
                    out.append("\\\\"); i++
                } else {
                    out.append(c).append(next); i += 2
                }
            // 其余转义字母不用这么讲究：t/f/b/r 的真义（制表/换页/退格/回车）几乎不会出现在
            // 正文里，而 \text \frac \beta \rightarrow 是高频命令；\alpha \cdot \delta 之类
            // 在 JSON 里本就是非法转义，一律按 LaTeX 还原。
            else -> { out.append("\\\\"); i++ }
        }
    }
    return out.toString()
}

/**
 * `\n` 后面跟的字母是否拼成了一个已知的 n 开头 LaTeX 命令。
 *
 * 用精确匹配而不是前缀匹配：所有命令都是完整单词（`\nabla`、`\neq`、`\notin`），
 * 而换行后的英文单词（`network`、`next`）拼出来是别的东西，不会误判。
 */
private fun isLatexNCommand(raw: String, start: Int): Boolean {
    val word = StringBuilder()
    var i = start
    while (i < raw.length && (raw[i] in 'a'..'z' || raw[i] in 'A'..'Z')) {
        word.append(raw[i])
        i++
    }
    return word.isNotEmpty() && word.toString() in LATEX_N_COMMANDS
}

/**
 * `\n` 开头的常见 LaTeX 命令（不含纯换行语义）。
 *
 * **唯一一份**：完整响应的 [sanitizeJsonEscapes] 与流式
 * [IncrementalReplyDecoder] 共用它。两层如果各留一份表，一旦改动不同步，
 * 就会出现「流式显示的公式」与「最终正文里的公式」不一致的诡异现象。
 */
internal val LATEX_N_COMMANDS = setOf(
    "nabla", "ne", "neq", "neg", "not", "notin", "nu", "nmid", "natural",
    "newline", "nearrow", "nwarrow", "nrightarrow", "nleftarrow",
    "nRightarrow", "nLeftarrow", "nvdash", "nsubseteq", "nsupseteq",
    "ngtr", "nless", "nleq", "ngeq", "nparallel", "nonumber", "normalsize",
    "noindent", "nobreak",
)

/**
 * 解析模型返回文本。
 *
 * 容错策略（按顺序尝试）：
 * - 整条文本是合法 JSON → 正常解析；
 * - 整条不是 JSON → 提取消息中的 ```json 围栏块或首个 `{` 到末个 `}` 的子串再解析（模型偶尔在 JSON 前后加说明文字）；
 * - 仍是截断的 JSON（常见于长回答撞上输出上限）→ 抢救已完整的 reply 正文和已完整的图表，而不是全部丢弃；
 * - 单张图表不合法时跳过并记录警告，不影响正文与其余图表。
 */
object ReplyParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, normalizeMarkdown: Boolean = true): ParsedReply {
        val trimmed = raw.trim()
        val normalized = trimmed
            .removePrefix("```json")
            .removePrefix("```JSON")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        parseJsonObject(normalized)?.let { return fromRoot(it, trimmed, normalizeMarkdown) }

        // 模型偶尔在 JSON 外面包了说明文字或围栏：尝试任意位置提取 JSON 再解析。
        for (candidate in jsonCandidates(trimmed)) {
            val root = parseJsonObject(candidate) ?: continue
            // An embedded plot is an attachment to the surrounding Markdown, not a
            // replacement for that Markdown. Only a response envelope supplies reply.
            if ("reply" in root || "answer" in root || "conversation_title" in root) {
                return fromRoot(root, trimmed, normalizeMarkdown)
            }
        }

        // 截断的 JSON：抢救正文与已完整的图表，避免协议原文露在解答纸上。
        if (normalized.startsWith('{')) salvageTruncated(normalized, normalizeMarkdown)?.let { return it }

        val warnings = mutableListOf<String>()
        val embedded = extractEmbeddedFigures(trimmed, warnings)
        return ParsedReply(
            reply = embedded.cleanedReply.let { if (normalizeMarkdown) normalizeReplyMarkdown(it).trim() else it.trim() },
            warnings = warnings,
            plots = embedded.plots.filterNotNull(),
            plotSlots = embedded.plots,
            diagrams = embedded.diagrams.filterNotNull(),
            diagramSlots = embedded.diagrams,
        )
    }

    private fun parseJsonObject(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(sanitizeJsonEscapes(text)) }.getOrNull() as? JsonObject

    /** 从消息中提取可能的 JSON 候选：```json 围栏块、首个 `{` 到末个 `}` 的子串。 */
    private fun jsonCandidates(raw: String): List<String> {
        val candidates = mutableListOf<String>()
        Regex("```(?:json|JSON)?\\s*(\\{[\\s\\S]*)```").find(raw)?.let {
            candidates += it.groupValues[1].trim()
        }
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start in 0 until end) candidates += raw.substring(start, end + 1)
        return candidates
    }

    private fun fromRoot(root: JsonObject, trimmed: String, normalizeMarkdown: Boolean): ParsedReply {
        val warnings = mutableListOf<String>()
        val rawReply = (root["reply"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val finalAnswer = (root["answer"] as? JsonPrimitive)?.takeIf { it.isString }
            ?.contentOrNull.orEmpty().let { if (normalizeMarkdown) normalizeReplyMarkdown(it).trim() else it.trim() }
        val rootPlots = parsePlots(root, warnings)
        val embedded = extractEmbeddedFigures(rawReply, warnings)
        val plotSlots = (rootPlots + embedded.plots).take(MAX_PLOTS)
        val reply = embedded.cleanedReply.let { if (normalizeMarkdown) normalizeReplyMarkdown(it).trim() else it.trim() }
        val diagrams = (DiagramParser.parseSlots(root["diagrams"] as? JsonArray, warnings) + embedded.diagrams).take(4)

        return ParsedReply(
            // 仅有标题或绘图元数据时没有可展示的正文，不能把协议 JSON 放回解答纸。
            reply = reply.ifEmpty { finalAnswer.ifEmpty {
                if (plotSlots.isNotEmpty() || diagrams.isNotEmpty() || "conversation_title" in root || "reply" in root) "" else trimmed
            } },
            warnings = warnings,
            plots = plotSlots.filterNotNull(),
            diagrams = diagrams.filterNotNull(),
            plotSlots = plotSlots,
            diagramSlots = diagrams,
            conversationTitle = ConversationTitle.normalize(
                (root["conversation_title"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
            ),
            finalAnswer = finalAnswer,
        )
    }

    private const val MAX_PLOTS = 4
    private const val MAX_SERIES = 6
    private const val MAX_POINTS = 5000
    private const val MAX_MARKS = 12
    private val SERIES_STYLES = setOf("line", "dashed", "dotted", "dashdot", "marker", "line_marker")

    /**
     * 解析模型给出的绘图请求（顶层 `plots` 数组，或单个 `plot` 对象）。
     *
     * 只做结构校验与上限夹紧，**绝不执行模型给的字符串**——表达式最终交给白名单
     * 求值器 [ExprEval]；这里先试编译一次，把写坏的表达式挡在渲染之前。
     * 单张图不合法就丢掉它并记警告，不影响正文和其它图。
     */
    private fun parsePlots(root: JsonObject, warnings: MutableList<String>): List<PlotSpec?> {
        val array = root["plots"] as? JsonArray
        val single = root["plot"] as? JsonObject
        val items: List<JsonElement> = when {
            array != null -> array.take(MAX_PLOTS)
            single != null -> listOf(single)
            // 兼容模型直接返回单个 PlotSpec（title/x/y/series），而不是包在
            // {"plot": {...}} 或 {"plots": [...]} 里。
            root["series"] is JsonArray -> listOf(root)
            else -> return emptyList()
        }
        return items.map { element ->
            val obj = element as? JsonObject ?: run {
                warnings += "有一张图表不是对象，已忽略"
                return@map null
            }
            runCatching { parsePlot(obj, warnings) }
                .onFailure { warnings += "有一张图表没能生成：${it.message ?: "参数不合法"}" }
                .getOrNull()
        }
    }

    private data class EmbeddedFigures(
        val plots: List<PlotSpec?> = emptyList(),
        val diagrams: List<DiagramSpec?> = emptyList(),
        val cleanedReply: String,
    )

    /**
     * 从 reply 正文中识别绘图 JSON 围栏。普通 JSON 代码块不处理；只有通过
     * PlotSpec 的结构校验后才会从正文移走，避免误吞用户给出的代码示例。
     */
    private fun extractEmbeddedFigures(reply: String, warnings: MutableList<String>): EmbeddedFigures {
        if (reply.isBlank()) return EmbeddedFigures(cleanedReply = reply)
        var cleaned = reply
        val foundPlots = mutableListOf<PlotSpec?>()
        val foundDiagrams = mutableListOf<DiagramSpec?>()
        fun collect(root: JsonObject): Boolean {
            val recognized = root["plots"] is JsonArray || root["plot"] is JsonObject ||
                root["series"] is JsonArray || root["diagrams"] is JsonArray
            if (!recognized) return false
            foundPlots += parsePlots(root, warnings)
            foundDiagrams += DiagramParser.parseSlots(root["diagrams"] as? JsonArray, warnings)
            return true
        }
        // Kimi and other compatible services sometimes write `"plots":[...]`
        // in a JSON code fence, omitting the object braces. Wrap only recognized
        // figure members, validate the actual spec, and retain the surrounding prose.
        val fences = Regex("(?s)(```|~~~)([^\\r\\n]*)\\r?\\n(.*?)\\1").findAll(reply).toList()
        val removed = mutableListOf<IntRange>()
        for (match in fences) {
            val language = match.groupValues[2].trim()
            if (language.isNotEmpty() && !language.equals("json", ignoreCase = true)) continue
            val root = embeddedFigureRoot(match.groupValues[3]) ?: continue
            if (collect(root)) removed += match.range
        }
        for (range in removed.asReversed()) cleaned = cleaned.removeRange(range)

        // Unfenced member fragments are also supported. Do not inspect members
        // inside remaining code examples or inline-code spans.
        val protected = codeRanges(cleaned)
        val fragments = Regex("(?m)^[ \\t]*\"(plots|diagrams)\"\\s*:\\s*")
            .findAll(cleaned).toList()
        val fragmentRanges = mutableListOf<IntRange>()
        for (match in fragments) {
            if (protected.any { match.range.first in it }) continue
            val start = match.range.last + 1
            if (cleaned.getOrNull(start) != '[') continue
            val end = jsonContainerEnd(cleaned, start) ?: continue
            val root = embeddedFigureRoot(cleaned.substring(match.range.first, end)) ?: continue
            if (collect(root)) {
                val trailingComma = if (cleaned.getOrNull(end) == ',') 1 else 0
                fragmentRanges += match.range.first until end + trailingComma
            }
        }
        for (range in fragmentRanges.asReversed()) cleaned = cleaned.removeRange(range)

        // Complete standalone figure objects between prose paragraphs.
        val code = codeRanges(cleaned)
        var cursor = 0
        val objects = mutableListOf<IntRange>()
        while (cursor < cleaned.length) {
            val start = cleaned.indexOf('{', cursor)
            if (start < 0) break
            val blocked = code.firstOrNull { start in it }
            if (blocked != null) { cursor = blocked.last + 1; continue }
            val end = jsonContainerEnd(cleaned, start)
            if (end == null) { cursor = start + 1; continue }
            val root = parseJsonObject(cleaned.substring(start, end))
            if (root != null && collect(root)) objects += start until end
            cursor = end
        }
        for (range in objects.asReversed()) cleaned = cleaned.removeRange(range)
        return EmbeddedFigures(foundPlots.take(MAX_PLOTS), foundDiagrams.take(4), cleaned.trim())
    }

    private fun embeddedFigureRoot(text: String): JsonObject? {
        val trimmed = text.trim()
        parseJsonObject(trimmed)?.let { return it }
        if (!Regex("^\"(?:plots|diagrams)\"\\s*:").containsMatchIn(trimmed)) return null
        return parseJsonObject("{${trimmed.removeSuffix(",")}}")
    }

    private fun codeRanges(text: String): List<IntRange> =
        Regex("(?s)(```|~~~)[^\\r\\n]*\\r?\\n.*?\\1|`[^`\\r\\n]*`").findAll(text).map { it.range }.toList()

    /** End is exclusive; bracket matching ignores escaped quotes and brackets in labels. */
    private fun jsonContainerEnd(text: String, start: Int): Int? {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val char = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else when (char) {
                '"' -> inString = true
                '[', '{' -> stack.addLast(char)
                ']', '}' -> {
                    if (stack.removeLastOrNull() != if (char == ']') '[' else '{') return null
                    if (stack.isEmpty()) return i + 1
                }
            }
        }
        return null
    }

    private fun parsePlot(obj: JsonObject, warnings: MutableList<String>): PlotSpec {
        val seriesArray = obj["series"] as? JsonArray ?: throw IllegalArgumentException("缺少 series")
        val series = seriesArray.take(MAX_SERIES).mapIndexedNotNull { index, element ->
            parsePlotSeries(element as? JsonObject ?: return@mapIndexedNotNull null, index)
        }
        require(series.isNotEmpty()) { "series 为空" }
        return PlotSpec(
            title = (obj["title"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(80),
            x = parseAxis(obj["x"] as? JsonObject),
            y = parseAxis(obj["y"] as? JsonObject),
            series = series,
            legend = (obj["legend"] as? JsonPrimitive)?.booleanOrNull ?: (series.size > 1),
            markLines = (obj["markLines"] as? JsonArray)?.take(MAX_MARKS)
                ?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val x = finiteNumber(o, "x")
                    val y = finiteNumber(o, "y")
                    if (x == null && y == null) {
                        null
                    } else {
                        MarkLine(x, y, (o["label"] as? JsonPrimitive)?.contentOrNull?.take(16))
                    }
                }
                .orEmpty(),
            markAreas = (obj["markAreas"] as? JsonArray)?.take(MAX_MARKS)
                ?.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val x0 = finiteNumber(o, "x0") ?: return@mapNotNull null
                    val x1 = finiteNumber(o, "x1") ?: return@mapNotNull null
                    if (x1 <= x0) {
                        null
                    } else {
                        MarkArea(x0, x1, (o["label"] as? JsonPrimitive)?.contentOrNull?.take(16),
                            y0 = finiteNumber(o, "y0"), y1 = finiteNumber(o, "y1"),
                            colorIndex = colorIndex(o, 1), color = plotColor(o),
                            opacity = (finiteNumber(o, "opacity") ?: .12).coerceIn(.03, .6), pattern = fillPattern(o))
                    }
                }
                .orEmpty(),
            shades = (obj["shades"] as? JsonArray)?.take(MAX_MARKS)?.mapNotNull { element ->
                val region = element as? JsonObject ?: return@mapNotNull null
                runCatching { parseShade(region) }.onFailure { warnings += "有一处区域阴影未生成：${it.message}" }.getOrNull()
            }.orEmpty(),
        )
    }

    private fun finiteNumber(obj: JsonObject, key: String): Double? =
        (obj[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
    private fun colorIndex(obj: JsonObject, fallback: Int): Int = (finiteNumber(obj, "colorIndex")?.toInt() ?: fallback).coerceIn(0, 11)
    private fun plotColor(obj: JsonObject): String? = (obj["color"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.matches(Regex("#[0-9a-fA-F]{6}")) }
    private fun fillPattern(obj: JsonObject): String = (obj["pattern"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("solid", "hatched", "crosshatch") } ?: "solid"
    private fun parseShade(obj: JsonObject): ShadeRegion {
        val points = (obj["points"] as? JsonArray)?.take(MAX_POINTS)?.map { element ->
            val pair = element as? JsonArray ?: error("多边形顶点无效")
            require(pair.size >= 2) { "多边形顶点需要 x、y" }
            val x = (pair[0] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: error("顶点横坐标无效")
            val y = (pair[1] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() } ?: error("顶点纵坐标无效")
            x to y
        }
        if (points != null) require(points.size >= 3) { "多边形至少需要三个顶点" }
        val x0 = finiteNumber(obj, "x0")
        val x1 = finiteNumber(obj, "x1")
        if (points == null) require(x0 != null && x1 != null && x1 > x0) { "请给出有效的阴影横坐标区间" }
        fun boundary(key: String): String {
            val expression = (obj[key] as? JsonPrimitive)?.contentOrNull ?: "0"
            require(expression.length <= 240 && expression.none { it == ';' || it == '\n' || it == '\r' }) { "阴影边界表达式无效" }
            ExprEval.compile(expression)
            return expression
        }
        return ShadeRegion(x0, x1, if (points == null) boundary("upper") else "0", if (points == null) boundary("lower") else "0",
            points, colorIndex(obj, 0), plotColor(obj), (finiteNumber(obj, "opacity") ?: .18).coerceIn(.03, .6), fillPattern(obj),
            (obj["label"] as? JsonPrimitive)?.contentOrNull?.take(16))
    }

    private fun parsePlotSeries(obj: JsonObject, index: Int): Series? {
        val expr = (obj["expr"] as? JsonPrimitive)?.contentOrNull?.take(240)?.takeIf { it.isNotBlank() }
        val points = (obj["points"] as? JsonArray)?.take(MAX_POINTS)?.mapNotNull { el ->
            val pair = el as? JsonArray ?: return@mapNotNull null
            if (pair.size < 2) return@mapNotNull null
            val x = (pair[0] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val y = (pair[1] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            if (!x.isFinite() || !y.isFinite()) null else x to y
        }
        if (expr == null && points.isNullOrEmpty()) return null
        if (expr != null) {
            // 表达式里出现分号/换行一律拒绝；再试编译一次，写坏的别留到渲染时才发现
            require(expr.none { it == ';' || it == '\n' || it == '\r' }) { "表达式包含非法字符" }
            ExprEval.compile(expr)
        }
        return Series(
            label = (obj["label"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(40),
            expr = expr,
            points = points,
            style = (obj["style"] as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it in SERIES_STYLES } ?: "line",
            fill = (obj["fill"] as? JsonPrimitive)?.booleanOrNull ?: false,
            colorIndex = colorIndex(obj, index),
            opacity = (finiteNumber(obj, "opacity") ?: 1.0).coerceIn(0.05, 1.0),
            width = (finiteNumber(obj, "width") ?: 2.0).coerceIn(.75, 5.0).toFloat(),
            color = plotColor(obj),
            markerShape = (obj["markerShape"] as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("circle", "open_circle", "square", "diamond", "triangle", "cross") } ?: "circle",
            markerSize = (finiteNumber(obj, "markerSize") ?: 3.0).coerceIn(2.0, 8.0).toFloat(),
        )
    }

    private fun parseAxis(obj: JsonObject?): Axis {
        if (obj == null) return Axis()
        val min = (obj["min"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        val max = (obj["max"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        // 区间非法就退回自动范围，绝不把 min>=max 传进渲染器
        val validRange = min != null && max != null && max > min
        return Axis(
            label = (obj["label"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(24),
            unit = (obj["unit"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(16),
            min = if (validRange) min else null,
            max = if (validRange) max else null,
            grid = (obj["grid"] as? JsonPrimitive)?.booleanOrNull ?: true,
            ticks = (obj["ticks"] as? JsonArray)?.take(20)
                ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite) },
            tickLabels = (obj["tickLabels"] as? JsonObject)?.mapNotNull { (key, value) ->
                val tick = key.toDoubleOrNull() ?: return@mapNotNull null
                val text = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                // 上限 24：刻度文案本来就该短，但也要容得下 `$N_0(2\pi f_c)^2$` 这类
                // 带 LaTeX 定界符的写法（16 字符会把公式从中间截断，渲染出来是残缺的）
                tick to text.take(24)
            }?.toMap().orEmpty(),
            position = (obj["position"] as? JsonPrimitive)?.contentOrNull
                ?.lowercase()
                ?.takeIf { it in setOf("auto", "origin", "bottom") }
                ?: "auto",
        )
    }

    /**
     * 截断恢复：整包 JSON 没闭合时（典型原因是长回答撞上输出上限），
     * 尽力把已完整的 reply 与动作条目抢救出来，而不是只留正文丢掉按钮。
     *
     * plots 必须一起抢救：长回答被截断时模型往往**已经把 plots 写在前面**，
     * 只恢复正文会导致 `imagePaths` 为空，而正文里的 `[[FIGURE:n]]` 锚点仍在，
     * 渲染层拿不到图就把锚点当成普通文字显示出来（用户看到正文里裸着
     * `[[FIGURE:1]]`）。这是「图片不显示 + 锚点露出」的根因之一。
     */
    /**
     * 截断恢复：整包 JSON 没闭合时（典型原因是长回答撞上输出上限），
     * 尽力把已完整的 reply 与图表抢救出来。
     *
     * plots 必须一起抢救：长回答被截断时模型往往**已经把 plots 写在前面**，
     * 只恢复正文会导致图片为空，而正文里的 `[[FIGURE:n]]` 锚点仍在，
     * 渲染层拿不到图就把锚点当成普通文字显示出来。
     */
    private fun salvageTruncated(normalized: String, normalizeMarkdown: Boolean): ParsedReply? {
        val finalAnswer = closedAnswer(normalized, normalizeMarkdown)
        val rawReply = partialReply(normalized) ?: finalAnswer.takeIf { it.isNotBlank() } ?: return null
        val reply = if (normalizeMarkdown) normalizeReplyMarkdown(rawReply) else rawReply
        val warnings = mutableListOf(
            "模型返回的结构化协议未完整结束（可能被输出上限截断），已恢复正文与可解析的图表",
        )
        val embedded = extractEmbeddedFigures(reply, warnings)
        val plots = (salvagePlots(normalized, warnings) + embedded.plots).take(MAX_PLOTS)
        val diagrams = (DiagramParser.parseSlots(salvageFigureArray(normalized, "diagrams"), warnings) + embedded.diagrams).take(4)
        return ParsedReply(
            reply = embedded.cleanedReply,
            warnings = warnings,
            plots = plots.filterNotNull(),
            diagrams = diagrams.filterNotNull(),
            plotSlots = plots,
            diagramSlots = diagrams,
            conversationTitle = ConversationTitle.fromIncompleteResponse(normalized),
            finalAnswer = finalAnswer,
        )
    }

    private fun closedAnswer(raw: String, normalizeMarkdown: Boolean): String {
        val decoder = IncrementalReplyDecoder("answer")
        decoder.append(raw)
        if (!decoder.valueComplete) return ""
        return decoder.text.let { if (normalizeMarkdown) normalizeReplyMarkdown(it).trim() else it.trim() }
    }

    /**
     * 从截断文本里抢救已完整闭合的 plot 对象。
     *
     * `plots` 是数组，被截断时通常只有最后一项不完整；前面的项照样能用。
     * 复用 [salvageActionArray] 的逐字符扫描（跳过字符串字面量）拿到已闭合的 `{...}`，
     * 再走与正常路径相同的 [parsePlot] 校验，坏的那张丢掉并记警告。
     */
    private fun salvagePlots(raw: String, warnings: MutableList<String>): List<PlotSpec?> {
        val arrayStart = raw.indexOf("\"plots\"")
        if (arrayStart < 0) {
            // 也支持单个 `"plot": {...}` 的写法
            val single = salvageActionArray(raw, "plot") { element, _, _ ->
                (element as? JsonObject)?.let { runCatching { parsePlot(it, warnings) }.getOrNull() }
            }
            return single.take(MAX_PLOTS)
        }
        val array = salvageFigureArray(raw, "plots") ?: return emptyList()
        return parsePlots(JsonObject(mapOf("plots" to array)), warnings)
    }

    /**
     * 从截断文本里抢救已完整闭合的 diagram 对象。
     *
     * 与 [salvagePlots] 同一套扫描：被截断时通常只有最后一项不完整，
     * 前面的照样能用。坏的那张丢掉并记警告，不影响正文与其它图。
     */
    private fun salvageFigureArray(raw: String, key: String): JsonArray? {
        val keyIndex = raw.indexOf("\"$key\"")
        if (keyIndex < 0) return null
        val colon = raw.indexOf(':', keyIndex + key.length + 2)
        if (colon < 0) return null
        val open = (colon + 1 until raw.length).firstOrNull { !raw[it].isWhitespace() } ?: return null
        if (raw[open] != '[') return null
        val elements = mutableListOf<JsonElement>()
        var depth = 1
        var string = false
        var escaped = false
        var start = open + 1
        fun add(end: Int) {
            val text = raw.substring(start, end).trim()
            if (text.isNotEmpty()) elements += runCatching {
                json.parseToJsonElement(sanitizeJsonEscapes(text))
            }.getOrDefault(kotlinx.serialization.json.JsonNull)
        }
        for (i in open + 1 until raw.length) {
            val c = raw[i]
            if (string) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> string = false
                }
                continue
            }
            when (c) {
                '"' -> string = true
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth == 0) { add(i); return JsonArray(elements) }
                }
                ',' -> if (depth == 1) { add(i); start = i + 1 }
            }
        }
        if (!string && depth == 1) add(raw.length)
        return JsonArray(elements)
    }

    /**
     * 在截断的原始文本中定位 `"key": [` 数组，逐字符扫描（跳过字符串字面量），
     * 提取所有已完整闭合的 `{ ... }` 对象并解析；未闭合的尾巴直接忽略。
     */
    private fun <T> salvageActionArray(
        raw: String,
        key: String,
        parseOne: (JsonElement, Int, MutableList<String>) -> T?,
    ): List<T> {
        val keyIndex = raw.indexOf("\"$key\"")
        if (keyIndex < 0) return emptyList()
        val arrayStart = raw.indexOf('[', keyIndex)
        if (arrayStart < 0) return emptyList()

        val objects = mutableListOf<String>()
        var depth = 0
        var objStart = -1
        var inString = false
        var escaped = false
        for (i in arrayStart + 1 until raw.length) {
            val c = raw[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when {
                c == '"' -> inString = true
                c == '{' -> {
                    if (depth == 0) objStart = i
                    depth++
                }
                c == '}' -> {
                    depth--
                    if (depth == 0 && objStart >= 0) {
                        objects += raw.substring(objStart, i + 1)
                        objStart = -1
                    }
                    if (depth < 0) break
                }
            }
        }
        val warnings = mutableListOf<String>()
        val parsed = objects.mapIndexedNotNull { index, text ->
            val element = runCatching { json.parseToJsonElement(sanitizeJsonEscapes(text)) }.getOrNull()
                ?: return@mapIndexedNotNull null
            parseOne(element, index, warnings)
        }
        return parsed
    }

    /** Salvages the reply string when a model hits its output limit mid-JSON object. */
    private fun partialReply(raw: String): String? {
        val match = Regex("\\\"reply\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)").find(raw) ?: return null
        val encoded = "\"${match.groupValues[1]}\""
        return runCatching {
            json.parseToJsonElement(sanitizeJsonEscapes(encoded)).jsonPrimitive.contentOrNull
        }
            .getOrNull()
    }
}
