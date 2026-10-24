package com.moge.app.data.parse

/**
 * 把过长的公式在顶层“安全断点”处拆成多个块级公式，避免右侧溢出被裁剪。
 * 优先在顶层运算符处分行，括号内不自动生成纵向表格，保证算式不会被误读为矩阵；
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
    var displayIndent = ""
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
            val punctuation = TRAILING_FORMULA_PUNCTUATION.find(rest.substring(close + 2))?.value.orEmpty()
            block.append('\n').append(rest.substring(0, close))
            out += splitFormulaBlocks(trimSegment(block.toString()) + punctuation.trimStart(), maxWidthPx, measure)
                .map { it.prependIndent(displayIndent) }
            block.setLength(0)
            inDisplay = false
            rest = rest.substring(close + 2 + punctuation.length)
            if (rest.isEmpty()) {
                index++
                continue
            }
            rest = displayIndent + rest
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
            displayIndent = formulaContinuationIndent(rest)
            block.append(body)
            inDisplay = true
        } else {
            out += wrapInlineLine(rest, maxWidthPx, measure)
        }
        index++
    }
    if (inDisplay && block.toString().isNotBlank()) {
        out += splitFormulaBlocks(trimSegment(block.toString()), maxWidthPx, measure)
            .map { it.prependIndent(displayIndent) }
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

/** Keep sentence punctuation attached when a formula becomes a display block. */
private val TRAILING_FORMULA_PUNCTUATION = Regex("""^[ \t]*[,;，；。.!?！？、]+""")

/** Promoted math and its trailing prose must remain inside the original list item. */
private fun formulaContinuationIndent(line: String): String {
    val leading = line.takeWhile { it == ' ' || it == '\t' }
    val marker = LIST_MARKER.find(line.substring(leading.length))?.value.orEmpty()
    return leading + " ".repeat(marker.length)
}

private val LIST_MARKER = Regex("""^(?:\d{1,9}[.)]|[-+*])[ \t]+""")

private fun splitFormulaBlocks(latex: String, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    if (latex.isEmpty()) return emptyList()
    // Blank source lines are ordinary TeX whitespace, but Markwon treats them
    // as the end of a math block and strands the name above its equation.
    val continuous = latex.replace(Regex("\n[ \t]*\n+"), "\n")
    return formulaBlockLines(splitFormula(trimFormulaRow(continuous), maxWidthPx, measure))
}

private fun formulaBlockLines(rows: List<String>): List<String> =
    rows.flatMap { listOf(DISPLAY_DELIMITER, it, DISPLAY_DELIMITER) }

private fun wrapInlineLine(line: String, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    if (!line.contains(DISPLAY_DELIMITER)) return listOf(line)
    val out = mutableListOf<String>()
    val text = StringBuilder()
    val indent = formulaContinuationIndent(line)
    var continuation = false
    fun prose() = if (continuation) indent + text.toString().trimStart() else text.toString()
    val delimiters = mathDelimiters(line)
    var i = 0
    while (i < line.length) {
        if (i in delimiters) {
            val close = delimiters.firstOrNull { it > i } ?: -1
            if (close < 0) {
                text.append(line, i, line.length)
                break
            }
            var latex = line.substring(i + 2, close)
            var end = close + 2
            val punctuation = TRAILING_FORMULA_PUNCTUATION.find(line.substring(end))?.value.orEmpty()
            if (measure(latex + punctuation.trimStart()) > maxWidthPx) {
                // Otherwise promoting the math leaves its comma/period as a
                // separate prose paragraph after the final display delimiter.
                latex += punctuation.trimStart()
                end += punctuation.length
                val rows = splitFormula(latex, maxWidthPx, measure)
                if (rows.size == 1 && measure(rows.single()) <= maxWidthPx) {
                    // A wrapped frame is still one inline object. Preserve its
                    // paragraph, punctuation and list rather than adding block gaps.
                    text.append(DISPLAY_DELIMITER).append(rows.single()).append(DISPLAY_DELIMITER)
                } else {
                    val before = prose()
                    val markerOnly = LIST_MARKER.matches(before.trimStart())
                    if (!markerOnly) before.trimEnd().takeIf { it.isNotBlank() }?.let { out += it }
                    out += formulaBlockLines(rows).mapIndexed { index, block ->
                        // An empty bullet cannot interrupt a paragraph; keep its
                        // first math delimiter beside the original marker.
                        if (markerOnly && index == 0) before + block else block.prependIndent(indent)
                    }
                    text.setLength(0)
                    continuation = true
                }
            } else {
                text.append(DISPLAY_DELIMITER).append(latex).append(DISPLAY_DELIMITER)
            }
            i = end
        } else {
            text.append(line[i])
            i++
        }
    }
    // Two trailing spaces (or a backslash) are an authored Markdown hard break.
    prose().takeIf { it.isNotBlank() }?.let { out += it }
    return out.ifEmpty { listOf(line) }
}

/** 优先在独立公式、顶层关系符和加减号处换行，最后才处理过长的括号组。 */
internal fun splitFormula(latex: String, maxWidthPx: Int, measure: (String) -> Int): List<String> {
    val value = trimSegment(latex)
    if (value.isEmpty()) return emptyList()
    if (measure(value) <= maxWidthPx) return listOf(value)
    wrapFormulaGroup(value, FormulaGroupKind.Frame, maxWidthPx, measure)?.let { return it }
    splitSetBuilderFormula(value, maxWidthPx, measure)?.let { return it }
    splitTrailingFormulaCondition(value, maxWidthPx, measure)?.let { return it }
    // 拆成独立显示块时消费掉行分隔符，不能把 \\ 留在上一段末尾或下一段开头。
    val rowBreaks = splitPoints(value, 0)
    if (rowBreaks.isNotEmpty()) {
        var start = 0
        val rows = rowBreaks.map { cut ->
            value.substring(start, cut).also { start = cut + 2 }
        } + value.substring(start)
        return rows.filter { it.isNotBlank() }.flatMap { splitFormula(trimFormulaRow(it), maxWidthPx, measure) }
    }
    // 整体是一个 array/aligned 这类「行式」环境且放不下：
    // 先按行解包成独立的块级公式（去掉环境包裹与对齐符 &），
    // 而不是在环境内部的 \\ 处硬切——那会产生带无配对 \begin/\end 的孤儿片段，必然解析失败。
    unwrapEnvironmentRows(value)?.let { rows ->
        return rows.flatMap { row ->
            if (measure(row) <= maxWidthPx) listOf(row) else splitFormula(row, maxWidthPx, measure)
        }
    }
    // An equation separator outranks wrapping inside function arguments. Otherwise
    // a nearly fitting M(x,y), N(x,y) pair can split N's tuple across display rows.
    val equations = greedySplit(value, 1, maxWidthPx, measure)
    if (equations.size > 1) {
        return equations.flatMap { splitFormula(it, maxWidthPx, measure) }
    }
    var pieces = equations
    for (priority in 2..3) {
        if (pieces.all { measure(it) <= maxWidthPx }) break
        pieces = pieces.flatMap { piece ->
            if (measure(piece) <= maxWidthPx) listOf(piece) else greedySplit(piece, priority, maxWidthPx, measure)
        }
    }
    return pieces.flatMap { piece ->
        if (measure(piece) <= maxWidthPx) listOf(piece)
        else wrapFormulaGroup(piece, FormulaGroupKind.Delimiter, maxWidthPx, measure) ?: listOf(piece)
    }
}

/**
 * A set's outer sized braces protect all its commas from ordinary wrapping.
 * Break between complete conditions, closing/reopening invisible delimiters so
 * each row remains valid TeX. Keep the name, coordinates and first condition
 * together when they fit, rather than stranding D_1 on a row of its own.
 */
private fun splitSetBuilderFormula(latex: String, maxWidthPx: Int, measure: (String) -> Int): List<String>? {
    val open = latex.indexOf("\\left\\{")
    val close = latex.lastIndexOf("\\right\\}")
    if (open < 0 || close <= open) return null
    val suffix = latex.substring(close + "\\right\\}".length)
    if (suffix.any { !it.isWhitespace() && it !in ".,;" }) return null
    val body = latex.substring(open + "\\left\\{".length, close)
    // A nested pair may contain its own commas. splitPoints ignores those,
    // just as it ignores tuple commas and subscripts such as x_{1,2}.
    val cuts = splitPoints(body, 1)
    if (cuts.isEmpty() || ':' !in body && "\\mid" !in body) return null
    val conditions = mutableListOf<String>()
    var start = 0
    for (cut in cuts) {
        conditions += trimSegment(body.substring(start, cut + 1))
        start = cut + 1
    }
    conditions += trimSegment(body.substring(start))
    if (conditions.any { it.isEmpty() }) return null
    val prefix = latex.substring(0, open)
    val rows = mutableListOf<String>()
    var current = prefix + "\\left\\{" + conditions.first()
    for ((index, condition) in conditions.drop(1).withIndex()) {
        val last = index == conditions.size - 2
        val closer = if (last) "\\right\\}$suffix" else "\\right."
        if (measure(current + "\\ " + condition + closer) <= maxWidthPx) {
            current += "\\ " + condition
        } else {
            rows += current + "\\right."
            current = "\\left." + condition
        }
    }
    rows += current + "\\right\\}$suffix"
    // Refine only conditions that still exceed the width. Each row now has
    // just one visible brace, leaving the other conditions their full width.
    return rows.takeIf { it.size > 1 }?.flatMap { row ->
        if (measure(row) <= maxWidthPx) listOf(row) else splitFormula(row, maxWidthPx, measure)
    }
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
    return cleaned.map(::trimFormulaRow).filter { it.isNotEmpty() }.ifEmpty { null }
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
        val token = mathDelimiterAt(body, i)
        if (token != null) {
            delimiterDepth += if (token.isOpening) 1 else -1
            current.append(body, i, token.end)
            i = token.end
            continue
        }
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
        // Commas finish the preceding expression. Include them in its width
        // budget before trying relation/sign breaks inside a long expression.
        // Relation and sign operators still begin the continuation row.
        val end = if (priority == 1 && latex[cut] in ",;，；") cut + 1 else cut
        if (end > start) segments += latex.substring(start, end)
        start = end
    }
    segments += latex.substring(start)
    val merged = mutableListOf<StringBuilder>()
    for (segment in segments) {
        if (segment.isBlank()) continue
        val last = merged.lastOrNull()
        if (last == null || measure(last.toString() + segment) > maxWidthPx) {
            merged += StringBuilder(trimFormulaRow(segment))
        } else {
            last.append(segment)
        }
    }
    val result = merged.map { trimFormulaRow(it.toString()) }.filter { it.isNotEmpty() }
    return result.ifEmpty { listOf(latex) }
}

/** Separator glue belongs between equations, not at the edge of a centered display row. */
internal fun trimFormulaRow(segment: String): String = trimSegment(segment)
    .replace(LEADING_FORMULA_SPACE, "")
    .replace(TRAILING_FORMULA_SPACE, "")

private const val FORMULA_SPACE = """(?:\s|\\(?:qquad\b|quad\b|enspace\b|thinspace\b|[,;:! ]))"""
private val LEADING_FORMULA_SPACE = Regex("^$FORMULA_SPACE+")
private val TRAILING_FORMULA_SPACE = Regex("$FORMULA_SPACE+$")

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
        val delimiter = mathDelimiterAt(latex, i)
        if (delimiter != null) {
            delimiterDepth += if (delimiter.isOpening) 1 else -1
            i = delimiter.end
            continue
        }
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
            if (priority == 1 && depth == 0 && envDepth == 0 && delimiterDepth == 0) {
                val space = EQUATION_SPACE.matchAt(latex, i)
                if (space != null) {
                    points += i
                    i = space.range.last + 1
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
                1 -> c in ",;，；"
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

private val EQUATION_SPACE = Regex("""\\(?:qquad|quad|enspace)\b""")

private val RELATION_COMMANDS = listOf(
    "\\Rightarrow", "\\Leftarrow", "\\rightarrow", "\\leftarrow",
    "\\subseteq", "\\supseteq", "\\subset", "\\supset",
    "\\geq", "\\leq", "\\neq", "\\ge", "\\le", "\\ne",
    "\\approx", "\\equiv", "\\to",
)
