package com.moge.app.data.parse

/**
 * 把过长的公式在顶层“安全断点”处拆成多个块级公式，避免右侧溢出被裁剪。
 * 断点只取「大括号深度 0 且不在 \begin{...} 环境内部」的位置，保证每段仍是可独立渲染的 LaTeX；
 * 整体就是一个 array/aligned 行式环境时，按行解包而不是硬切环境。
 */
internal fun wrapLongFormulas(
    markdown: String,
    maxWidthPx: Int,
    measure: (String) -> Int,
): String {
    if (maxWidthPx <= 0) return markdown
    val out = ArrayList<String>()
    var fence: String? = null
    var inDisplay = false
    val block = StringBuilder()
    val lines = markdown.split('\n')
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmed = line.trimStart()
        val lineFence = when {
            trimmed.startsWith("```") -> "```"
            trimmed.startsWith("~~~") -> "~~~"
            else -> null
        }
        // 表格整块原样放行。
        //
        // 表格行以 `|` 分隔单元格，而 wrapInlineLine 是按「整行宽度」决定是否把公式
        // 拆成独立块的：一行 `| 频率点 | $$P_{Y_c}$$ |` 只要公式量出来超过可用宽度，
        // 就会被拆成「| 频率点 |」+「$$」+「公式」+「$$」四行 —— 表格结构当场断裂，
        // Markwon 再也认不出这是表格，整块退化成普通文字（用户看到「表格显示不出来」）。
        // 单元格里的公式本来就该由表格自己横向滚动来容纳，不该按行宽拆。
        if (fence == null && isMarkdownTableRowLine(lines, index)) {
            out += line
            index++
            continue
        }
        if (lineFence != null || fence != null) {
            if (lineFence != null) {
                fence = if (fence == null) lineFence else if (fence == lineFence) null else fence
            }
            out += line
            index++
            continue
        }
        var rest = line
        if (inDisplay) {
            val close = mathDelimiters(rest).firstOrNull()
            if (close == null) {
                if (block.isNotEmpty()) block.append('\n')
                block.append(rest)
                index++
                continue
            }
            block.append('\n').append(rest.substring(0, close))
            out += splitFormulaBlocks(trimSegment(block.toString()), maxWidthPx, measure)
            block.setLength(0)
            inDisplay = false
            rest = rest.substring(close + 2)
            if (rest.isEmpty()) {
                index++
                continue
            }
        }
        // Same-line pairs remain inline. An unmatched final opener may follow prose.
        val delimiters = mathDelimiters(rest)
        if (delimiters.size % 2 == 1) {
            val open = delimiters.last()
            val prefix = rest.substring(0, open)
            val body = rest.substring(open + 2)
            // A damaged inline formula must not consume every later paragraph. Only recover
            // before unmistakable Chinese prose outside TeX groups; never change its math.
            val prose = if (prefix.isNotBlank()) topLevelProseStart(body) else -1
            if (prose >= 0) {
                out += wrapInlineLine(prefix + DISPLAY_DELIMITER + trimSegment(body.substring(0, prose)) +
                    DISPLAY_DELIMITER + " " + body.substring(prose), maxWidthPx, measure)
                index++
                continue
            }
            if (prefix.isNotBlank()) out += wrapInlineLine(prefix, maxWidthPx, measure)
            block.append(body)
            inDisplay = true
        } else {
            out += wrapInlineLine(rest, maxWidthPx, measure)
        }
        index++
    }
    if (inDisplay && block.toString().isNotBlank()) {
        out += splitFormulaBlocks(trimSegment(block.toString()), maxWidthPx, measure)
    }
    return out.joinToString("\n")
}

private fun topLevelProseStart(text: String): Int {
    var depth = 0
    var i = 0
    while (i < text.length) {
        if (text[i] == '\\') {
            i++
            if (text.getOrNull(i)?.isLetter() == true) {
                while (text.getOrNull(i)?.let { it in 'a'..'z' || it in 'A'..'Z' } == true) i++
            } else i++
            continue
        }
        when (text[i]) { '{' -> depth++; '}' -> depth-- }
        if (depth == 0 && text[i] in '\u3400'..'\u9fff') return i
        i++
    }
    return -1
}

/** Dollar pairs outside code and escapes; opening and closing use the same rule. */
private fun mathDelimiters(line: String): List<Int> {
    val result = mutableListOf<Int>()
    var codeTicks = 0
    var i = 0
    while (i < line.length) {
        if (line[i] == '`') {
            var end = i + 1
            while (end < line.length && line[end] == '`') end++
            val count = end - i
            codeTicks = if (codeTicks == 0) count else if (codeTicks == count) 0 else codeTicks
            i = end
            continue
        }
        if (line[i] == '\\') { i += 2; continue }
        if (codeTicks == 0 && line.startsWith(DISPLAY_DELIMITER, i)) {
            result += i
            i += 2
        } else i++
    }
    return result
}

/**
 * 判断 [index] 行是否属于 Markdown 表格（表头、分隔行或数据行）。
 *
 * 与渲染层 `splitMarkdownTableBlocks` 的判定保持一致：表头行含 `|` 且下一行是分隔行；
 * 分隔行之后的连续含 `|` 行都算表格行。
 */
private fun isMarkdownTableRowLine(lines: List<String>, index: Int): Boolean {
    val line = lines[index]
    if ('|' !in line) return false
    // 表头 + 分隔行
    if (isTableSeparatorRow(line)) return true
    val next = lines.getOrNull(index + 1)
    if (next != null && isTableSeparatorRow(next)) return true
    // 数据行：往上找到最近的表头/分隔行，中间不能隔着空行或非表格行
    var cursor = index - 1
    while (cursor >= 0) {
        val prev = lines[cursor]
        if (prev.isBlank()) return false
        if ('|' !in prev) return false
        if (isTableSeparatorRow(prev)) return true
        cursor--
    }
    return false
}

/** Markdown 表格的分隔行：`| --- | :--: |`。 */
private fun isTableSeparatorRow(line: String): Boolean {
    val trimmed = line.trim()
    if ('-' !in trimmed) return false
    val cells = trimmed.trim('|').split('|')
    return cells.isNotEmpty() && cells.all { cell ->
        val token = cell.trim()
        token.isNotEmpty() && token.all { it == '-' || it == ':' } && '-' in token
    }
}

private const val DISPLAY_DELIMITER = "$$"

private fun splitFormulaBlocks(latex: String, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    if (latex.isEmpty()) return emptyList()
    return splitFormula(latex, maxWidthPx, measure).flatMap { listOf(DISPLAY_DELIMITER, it, DISPLAY_DELIMITER) }
}

private fun wrapInlineLine(line: String, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    if (!line.contains(DISPLAY_DELIMITER)) return listOf(line)
    val out = mutableListOf<String>()
    val text = StringBuilder()
    val delimiters = mathDelimiters(line)
    var i = 0
    while (i < line.length) {
        if (i in delimiters) {
            val close = delimiters.firstOrNull { it > i } ?: -1
            if (close < 0) {
                text.append(line, i, line.length)
                break
            }
            val latex = line.substring(i + 2, close)
            if (measure(latex) > maxWidthPx) {
                text.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
                out += splitFormulaBlocks(latex, maxWidthPx, measure)
                text.setLength(0)
            } else {
                text.append(DISPLAY_DELIMITER).append(latex).append(DISPLAY_DELIMITER)
            }
            i = close + 2
        } else {
            text.append(line[i])
            i++
        }
    }
    text.toString().trim().takeIf { it.isNotEmpty() }?.let { out += it }
    return out.ifEmpty { listOf(line) }
}

/** 优先级依次为：对齐换行 → 逗号分号 → 关系符 → 加减号。 */
internal fun splitFormula(latex: String, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    val value = trimSegment(latex)
    if (value.isEmpty()) return emptyList()
    if (measure(value) <= maxWidthPx) return listOf(value)
    // 拆成独立显示块时消费掉行分隔符，不能把 \\ 留在上一段末尾或下一段开头。
    val rowBreaks = splitPoints(value, 0)
    if (rowBreaks.isNotEmpty()) {
        var start = 0
        val rows = rowBreaks.map { cut ->
            value.substring(start, cut).also { start = cut + 2 }
        } + value.substring(start)
        return rows.filter { it.isNotBlank() }.flatMap { splitFormula(trimSegment(it), maxWidthPx, measure) }
    }
    // 整体是一个 array/aligned 这类「行式」环境且放不下：
    // 先按行解包成独立的块级公式（去掉环境包裹与对齐符 &），
    // 而不是在环境内部的 \\ 处硬切——那会产生带无配对 \begin/\end 的孤儿片段，必然解析失败。
    unwrapEnvironmentRows(value)?.let { rows ->
        return rows.flatMap { row ->
            if (measure(row) <= maxWidthPx) listOf(row) else splitFormula(row, maxWidthPx, measure)
        }
    }
    var pieces = listOf(value)
    for (priority in 1..3) {
        if (pieces.all { measure(it) <= maxWidthPx }) break
        pieces = pieces.flatMap { piece ->
            if (measure(piece) <= maxWidthPx) listOf(piece) else greedySplit(piece, priority, maxWidthPx, measure)
        }
    }
    return pieces
}

/**
 * 把 `\begin{env} ...\end{env}`（env ∈ [UNWRAPPABLE_ENVS]）解包成若干行独立公式。
 *
 * 仅当满足以下全部条件才解包，否则返回 null（交给环境感知的通用拆分兜底）：
 * - 环境是「行式」的对齐/堆叠环境（array/aligned 等；cases、各类 matrix 是真正的
 *   表格/矩阵，行内 `&` 是分隔符不是对齐符，拆开语义就错了）；
 * - 体内没有嵌套环境、没有 \hline；
 * - 每行至多 1 个 `&`（对齐符才可安全删除；>1 说明是表格，拆开会改变含义）。
 *
 * 已知代价：真正的两列表格（`a & b \\ c & d`）若超宽会被拍平成 `a b`。
 * 权衡是：走到这里的都是「整块已放不下」的公式，原样保留只会渲染成源码占位框。
 */
internal fun unwrapEnvironmentRows(latex: String): List<String>? {
    val match = ENVIRONMENT_LINE_RE.find(latex) ?: return null
    val env = match.groupValues[1]
    if (env !in UNWRAPPABLE_ENVS) return null
    val rawBody = trimSegment(match.groupValues[2])
    val body = if (env == "array") stripLeadingGroup(rawBody) else rawBody
    if (body.isEmpty() || body.contains("\\begin{") || body.contains("\\hline")) return null
    val (rows, skippedRowBreak) = splitTopLevelRows(body)
    // 有被跳过的 \\（\left[ 之类跨行配对括号内部）：按行解包会留下带孤立括号和残留 \\ 的坏行，
    // 此时整块保留——原环境是合法 LaTeX，交给渲染器整体处理。
    if (skippedRowBreak) return null
    if (rows.size < 2) return null
    if (rows.any { it.count { c -> c == '&' } > 1 }) return null
    val cleaned = rows.map { trimSegment(it.replace("&", " ")).replace(Regex(" {2,}"), " ") }.filter { it.isNotEmpty() }
    return cleaned.map(::trimSegment).filter { it.isNotEmpty() }.ifEmpty { null }
}

/** 匹配「整段就是一个数学环境」：\begin{x}…\end{x}，允许跨行。 */
private val ENVIRONMENT_LINE_RE = Regex(
    """^\\begin\{([A-Za-z*]+)\}(.*)\\end\{\1\}$""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)

/** 行式环境：行是独立公式，`&` 只是对齐符，解包后仍各自成立。 */
private val UNWRAPPABLE_ENVS = setOf(
    "array", "aligned", "aligned*", "align", "align*",
    "gather", "gather*", "split", "multline",
)

/** 去掉 array 的列定义前缀（如 {cc}），aligned 等没有前缀则原样返回。 */
private fun stripLeadingGroup(body: String): String {
    if (!body.startsWith("{")) return body
    var depth = 0
    for (index in body.indices) {
        when (body[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return trimSegment(body.substring(index + 1))
            }
        }
    }
    return body
}

/** 在「括号深度 0」（与 splitPoints 同一套 ()[]{} 规则）的 `\\` 处把环境体切成行；深度 > 0 处的 `\\` 跳过并上报。 */
private fun splitTopLevelRows(body: String): Pair<List<String>, Boolean> {
    val rows = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    var delimiterDepth = 0
    var skippedRowBreak = false
    var i = 0
    while (i < body.length) {
        if (body.startsWith("\\\\", i)) {
            if (depth == 0 && delimiterDepth == 0) {
                rows += current.toString()
                current.setLength(0)
            } else {
                skippedRowBreak = true
                current.append("\\\\")
            }
            i += 2
            continue
        }
        if (body[i] == '\\') {
            val commandEnd = sizedDelimiterEnd(body, i)
            if (commandEnd != null) {
                if (body.startsWith("\\left", i)) delimiterDepth++ else delimiterDepth--
                current.append(body, i, commandEnd)
                i = commandEnd
                continue
            }
            // 控制符必须整体处理，转义花括号并非分组边界。
            val start = i++
            if (body.getOrNull(i)?.isLetter() == true) {
                while (body.getOrNull(i)?.isLetter() == true) i++
            } else if (i < body.length) i++
            current.append(body, start, i)
            continue
        }
        when (body[i]) {
            '{', '(', '[' -> depth++
            '}', ')', ']' -> depth--
        }
        current.append(body[i])
        i++
    }
    rows += current.toString()
    return Pair(
        rows.map { trimSegment(it) }.filter { it.isNotEmpty() },
        skippedRowBreak,
    )
}

private fun greedySplit(latex: String, priority: Int, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    val cuts = splitPoints(latex, priority)
    if (cuts.isEmpty()) return listOf(latex)
    val segments = mutableListOf<String>()
    var start = 0
    for (cut in cuts) {
        if (cut > start) segments += latex.substring(start, cut)
        start = cut
    }
    segments += latex.substring(start)
    val merged = mutableListOf<StringBuilder>()
    for (segment in segments) {
        if (segment.isBlank()) continue
        val last = merged.lastOrNull()
        if (last == null || measure(last.toString() + segment) > maxWidthPx) {
            merged += StringBuilder(segment)
        } else {
            last.append(segment)
        }
    }
    val result = merged.map { trimSegment(it.toString()) }.filter { it.isNotEmpty() }
    return result.ifEmpty { listOf(latex) }
}

/**
 * 裁掉首尾空白，但**绝不在尾部留下孤立反斜杠**。
 *
 * `\ `（反斜杠 + 空格）是一个完整的 LaTeX 间距命令：模型在 `\Rightarrow` 前后
 * 高频写成 `\ \Rightarrow\ `。拆分点在 `\Rightarrow` 上，于是上一段以 `\ ` 收尾。
 * 若用 `String.trim()`，尾部的空格被裁掉、反斜杠却留下来 ⇒ 段尾变成孤立 `\`。
 *
 * JLatexMath 单独喂这个字符串时可能容忍 EOF 前的孤立反斜杠，但 Markwon 的
 * `JLatexMathBlockParser` 每行都会补一个 LF（`addLine`），块源码尾部实际是
 * `…\<LF>` —— 反斜杠后面跟着一个真实换行，会被当成**控制序列的开始**而报错。
 * 这正是「单条公式 build 通过、走真实 Markwon 却渲染失败」的成因。
 *
 * 因此这里要么把 `\ ` 整体保留（合法间距命令），要么连反斜杠一起去掉，
 * **绝不留下半个命令**。
 */
internal fun trimSegment(segment: String): String {
    var start = 0
    var end = segment.length
    while (start < end && segment[start].isWhitespace()) start++
    while (end > start && segment[end - 1].isWhitespace()) {
        // 只有奇数个连续反斜杠才会转义空白；偶数个是完整的行分隔符。
        var slashStart = end - 1
        while (slashStart > start && segment[slashStart - 1] == '\\') slashStart--
        if ((end - 1 - slashStart) % 2 == 1) end--
        end--
    }
    return segment.substring(start, end)
}

private fun splitPoints(latex: String, priority: Int): List<Int> {
    val points = mutableListOf<Int>()
    var depth = 0
    // \begin{...}...\end{...} 环境内部绝不能切：切开的片段带着无配对的环境标记，必然解析失败。
    var envDepth = 0
    // `\\left`/`\\right` 定界符可能跨越等号、逗号或换行；这些位置不能成为拆分点，
    // 否则会生成 `\\left|...` 与 `...\\right|` 两个无法独立解析的公式片段。
    var delimiterDepth = 0
    var i = 0
    while (i < latex.length) {
        val c = latex[i]
        if (c == '\\') {
            if (latex.startsWith("\\begin{", i)) {
                envDepth++
                i += "\\begin{".length
                while (i < latex.length && latex[i] != '}') i++
                i++
                continue
            }
            if (latex.startsWith("\\end{", i)) {
                envDepth--
                i += "\\end{".length
                while (i < latex.length && latex[i] != '}') i++
                i++
                continue
            }
            val commandEnd = sizedDelimiterEnd(latex, i)
            if (commandEnd != null) {
                if (latex.startsWith("\\left", i)) delimiterDepth++ else delimiterDepth--
                i = commandEnd
                continue
            }
            if (priority == 0 && envDepth == 0 && delimiterDepth == 0 && latex.startsWith("\\\\", i)) {
                if (depth == 0) points += i
                i += 2
                continue
            }
            if (priority == 2 && envDepth == 0) {
                val command = RELATION_COMMANDS.firstOrNull {
                    latex.startsWith(it, i) && latex.getOrNull(i + it.length)?.isLetter() != true
                }
                if (command != null) {
                    if (depth == 0 && delimiterDepth == 0) points += i
                    i += command.length
                    continue
                }
            }
            // 符号命令（\, \; \! \- \{ \} 等）必须与反斜杠作为一个整体跳过：
            // 否则 \, 里的逗号会被当成优先级 1 断点，切出以孤立 \ 结尾的非法片段；
            // \{ \} 里的花括号还会污染深度计数。
            i++
            if (i < latex.length && latex[i].isLetter()) {
                while (i < latex.length && latex[i].isLetter()) i++
            } else if (i < latex.length) {
                i++
            }
            continue
        }
        when (c) {
            '{', '(', '[' -> depth++
            '}', ')', ']' -> depth--
        }
        if (depth == 0 && envDepth == 0 && delimiterDepth == 0) {
            val isPoint = when (priority) {
                1 -> c == ',' || c == ';'
                2 -> c == '=' || c == '<' || c == '>'
                3 -> c == '+' || c == '-'
                else -> false
            }
            if (isPoint) points += i
        }
        i++
    }
    return points
}

/** Consume both the sizing command and its delimiter, including invisible or mixed delimiters. */
private fun sizedDelimiterEnd(latex: String, start: Int): Int? {
    val command = listOf("\\left", "\\right").firstOrNull {
        latex.startsWith(it, start) && latex.getOrNull(start + it.length)?.isLetter() != true
    } ?: return null
    var end = start + command.length
    while (latex.getOrNull(end)?.isWhitespace() == true) end++
    if (latex.getOrNull(end) == '\\') {
        end++
        if (latex.getOrNull(end)?.isLetter() == true) {
            while (latex.getOrNull(end)?.isLetter() == true) end++
        } else if (end < latex.length) end++
    } else if (end < latex.length) end++
    return end
}

private val RELATION_COMMANDS = listOf(
    "\\Rightarrow", "\\Leftarrow", "\\rightarrow", "\\leftarrow",
    "\\subseteq", "\\supseteq", "\\subset", "\\supset",
    "\\geq", "\\leq", "\\neq", "\\ge", "\\le", "\\ne",
    "\\approx", "\\equiv", "\\to",
)
