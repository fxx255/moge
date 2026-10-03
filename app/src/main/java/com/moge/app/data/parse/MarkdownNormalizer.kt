package com.moge.app.data.parse

/**
 * Repairs the second layer of escaping occasionally added by compatible providers.
 *
 * A global `\\n` replacement is deliberately avoided: it would turn LaTeX commands
 * such as `\\nu` into a line break followed by `u`. Paragraph breaks and the common
 * Markdown marker cases are unambiguous and are enough to restore the rendered reply.
 */
internal fun normalizeReplyMarkdown(raw: String): String {
    var normalized = raw
    if (raw.contains("\\n") || raw.contains("\\r")) {
        normalized = normalized
            .replace("\\r\\n\\r\\n", "\n\n")
            .replace("\\n\\n", "\n\n")
            .replace("\\r\\n", "\n")
        normalized = normalized.replace(
            Regex("""\\n(?=\s*(?:#{1,6}\s|[-*+]\s|\d+[.)]\s|>\s|```))"""),
            "\n",
        )
        normalized = normalized.replace(
            Regex("""\\r\\n(?=\s*(?:#{1,6}\s|[-*+]\s|\d+[.)]\s|>\s|```))"""),
            "\n",
        )
        // Chinese prose is also a safe paragraph/list continuation; LaTeX commands are not.
        normalized = normalized.replace(Regex("""\\n(?=[\u3400-\u9fff])"""), "\n")
    }
    return normalizeTables(normalizeDuplicateLatexBackslashes(normalizeMathDelimiters(normalized)))
}

/**
 * Repairs a second escaping layer that some providers leave in the decoded reply.
 *
 * The JSON decoder correctly turns `\\\\int` on the wire into `\\int`.  A few
 * providers, however, put the already escaped LaTeX in the JSON value, so the
 * decoded Markdown still contains `\\\\int`.  In a formula JLatexMath reads the
 * first two slashes as a row break; the rest (`int`, `cos`, `theta`, ...) then
 * renders as unrelated italic letters or fails with a ParseException.  This is
 * the exact pattern behind the screenshot where one integral was split into a
 * stack of `int_0`, `theta`, `cdot` and `frac` placeholders.
 *
 * Only an *exactly two-slash* run followed immediately by a likely command (or
 * a spacing punctuation) is collapsed.  A real TeX row break is kept when it is
 * followed by whitespace, `&`, another slash, an optional `[6pt]` argument, or
 * a plain one-letter row expression.  Code fences and text outside `$$` blocks
 * are left untouched.
 */
private fun normalizeDuplicateLatexBackslashes(markdown: String): String {
    if ("\\\\" !in markdown) return markdown
    val out = StringBuilder(markdown.length)
    var inFence: String? = null
    var inMath = false
    var inlineCodeTicks = 0
    var index = 0
    var lineStart = true
    while (index < markdown.length) {
        val c = markdown[index]
        if (lineStart && !inMath) {
            val actualMarker = when {
                markdown.startsWith("```", index) -> "```"
                markdown.startsWith("~~~", index) -> "~~~"
                else -> null
            }
            if (actualMarker != null) {
                inFence = if (inFence == null) actualMarker else if (inFence == actualMarker) null else inFence
            }
        }
        if (c == '\n') {
            out.append(c)
            index++
            lineStart = true
            continue
        }
        lineStart = false
        if (inFence != null) {
            out.append(c)
            index++
            continue
        }
        if (c == '`') {
            var end = index + 1
            while (end < markdown.length && markdown[end] == '`') end++
            val count = end - index
            inlineCodeTicks = when {
                inlineCodeTicks == 0 -> count
                inlineCodeTicks == count -> 0
                else -> inlineCodeTicks
            }
            out.append(markdown, index, end)
            index = end
            continue
        }
        if (inlineCodeTicks == 0 && markdown.startsWith("$$", index)) {
            inMath = !inMath
            out.append("$$")
            index += 2
            continue
        }
        if (!inMath || c != '\\') {
            out.append(c)
            index++
            continue
        }
        var runEnd = index
        while (runEnd < markdown.length && markdown[runEnd] == '\\') runEnd++
        val runLength = runEnd - index
        if (runLength == 2 && isDuplicateLatexCommand(markdown, runEnd)) {
            out.append('\\')
        } else {
            repeat(runLength) { out.append('\\') }
        }
        index = runEnd
    }
    return out.toString()
}

private fun isDuplicateLatexCommand(text: String, start: Int): Boolean {
    val next = text.getOrNull(start) ?: return false
    if (next in ",;:!") return true
    if (!next.isLetter()) return false
    var end = start
    while (text.getOrNull(end)?.isLetter() == true) end++
    val command = text.substring(start, end)
    // Keep this list intentionally broad: an unknown command is safer to leave
    // alone than to turn a genuine row break followed by a plain word into one.
    return command in DUPLICATE_LATEX_COMMANDS
}

private val DUPLICATE_LATEX_COMMANDS = setOf(
    "alpha", "beta", "cdot", "cdots", "cos", "delta", "dfrac", "dot", "ell",
    "equiv", "exists", "exp", "frac", "gamma", "ge", "geq", "in", "infty",
    "iint", "int", "lambda", "le", "leq", "lim", "ln", "log", "left", "mid",
    "mu", "nabla", "neq", "notin", "nu", "omega", "partial", "pi", "pm",
    "prod", "quad", "qquad", "rho", "right", "sigma", "sin", "sqrt", "sum",
    "tan", "tau", "theta", "times", "text", "mathrm", "mathbf", "mathbb",
    "mathcal", "operatorname", "begin", "end",
)

/**
 * 修复 Markdown 表格在 CommonMark 下「渲染不出来」的两种常见形态。
 *
 * 1. **缺少空行**：CommonMark 要求表格与上一段落之间必须有空行，否则整块表格
 *    会被当成普通段落，渲染成一行带竖线的文字。实测（Robolectric + 真实 Markwon）
 *    下表前无空行时 `TableSpan` 数量为 0，即表格完全没被识别——这正是用户截图里
 *    「表格只剩一行、竖线都没了」的成因。模型在「先写一句话、紧接着贴表格」时
 *    极高频地漏掉这个空行。
 * 2. **单元格里的竖线**：行内公式含绝对值/范数（`$$|f| \le B/2$$`）时，裸 `|`
 *    会被 TablePlugin 当成单元格分隔符，导致该行列数与其它行不一致，表格解析错乱
 *    甚至数据行被整行丢弃。这里把「已经处于公式内」的裸 `|` 转义成 `\|`。
 *
 * 只在识别为表格的行上动手，代码围栏内一律跳过。
 */
private fun normalizeTables(markdown: String): String {
    if ('|' !in markdown) return markdown
    val lines = markdown.split('\n')
    val out = ArrayList<String>(lines.size + 8)
    var fence: String? = null
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val trimmedStart = line.trimStart()
        val lineFence = when {
            trimmedStart.startsWith("```") -> "```"
            trimmedStart.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (lineFence != null) {
            fence = if (fence == null) lineFence else if (fence == lineFence) null else fence
            out += line
            index++
            continue
        }
        if (fence != null) {
            out += line
            index++
            continue
        }
        // 表格首行 = 含 | 且下一行是分隔行
        val next = lines.getOrNull(index + 1)
        if ('|' in line && next != null && isTableSeparatorLine(next)) {
            // ① 表格上方补空行（它前面若是非空、非表格内容）
            if (out.isNotEmpty() && out.last().isNotBlank()) out += ""
            out += escapeTableRowPipes(line)
            out += escapeTableRowPipes(next)
            index += 2
            // ② 数据行：一路吃到不再是表格行
            while (index < lines.size && '|' in lines[index] && lines[index].isNotBlank()) {
                out += escapeTableRowPipes(lines[index])
                index++
            }
            // ③ 表格下方补空行（后面若还有内容）
            if (index < lines.size && lines[index].isNotBlank()) out += ""
            continue
        }
        out += line
        index++
    }
    return out.joinToString("\n")
}

/** Markdown 表格的分隔行（`| --- | :--: |`）。与渲染层的判定保持一致。 */
private fun isTableSeparatorLine(line: String): Boolean {
    val trimmed = line.trim()
    if ('-' !in trimmed) return false
    val cells = trimmed.trim('|').split('|')
    return cells.isNotEmpty() && cells.all { cell ->
        val token = cell.trim()
        token.isNotEmpty() && token.all { it == '-' || it == ':' } && '-' in token
    }
}

/**
 * 把表格行中**位于公式内部**的裸 `|` 转义为 `\|`。
 *
 * 只处理 `$$...$$` 区间内的竖线：公式外的 `|` 是单元格分隔符，必须保留。
 * 表格引线（行首/行尾的 `|`）一定在公式外，天然不受影响。
 */
private fun escapeTableRowPipes(row: String): String {
    if (!row.contains("$$")) return row
    val out = StringBuilder(row.length + 8)
    var index = 0
    var inMath = false
    while (index < row.length) {
        if (row.startsWith("$$", index)) {
            inMath = !inMath
            out.append("$$")
            index += 2
            continue
        }
        val c = row[index]
        if (c == '|' && inMath && !row.isEscaped(index)) {
            out.append("\\|")
        } else {
            out.append(c)
        }
        index++
    }
    return out.toString()
}

/** Converts common model LaTeX delimiters to the double-dollar syntax used by Markwon. */
private fun normalizeMathDelimiters(markdown: String): String {
    var fence: String? = null
    var inMath = false
    return markdown.split('\n').joinToString("\n") { line ->
        val trimmedStart = line.trimStart()
        val lineFence = when {
            trimmedStart.startsWith("```") -> "```"
            trimmedStart.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (lineFence != null) {
            fence = if (fence == null) lineFence else if (fence == lineFence) null else fence
            line
        } else if (fence != null) {
            line
        } else {
            val trimmed = line.trim()
            val normalized = when (trimmed) {
                "\\[", "\\]" -> line.replace(trimmed, "${'$'}${'$'}")
                else -> normalizeInlineMathDelimiters(line)
            }
            val result = if (!inMath && looksLikeBareLatexContinuation(normalized)) {
                "$$\n${normalized.trim()}\n$$"
            } else normalized
            if (canonicalMathDelimiterCount(normalized) % 2 == 1) inMath = !inMath
            result
        }
    }
}

/** Count canonical delimiters with the same code/escape rules as inline normalization. */
private fun canonicalMathDelimiterCount(line: String): Int {
    var count = 0
    var ticks = 0
    var index = 0
    while (index < line.length) {
        if (line[index] == '`') {
            val run = line.runLength(index, '`')
            ticks = if (ticks == 0) run else if (ticks == run) 0 else ticks
            index += run
        } else if (ticks == 0 && line.startsWith("$$", index) && !line.isEscaped(index)) {
            count++
            index += 2
        } else index++
    }
    return count
}

/**
 * Recover a formula-only continuation such as =\arcsin\Bigl(...)\Bigr).
 * Require a known TeX command, single-letter variables and closed groups; prose,
 * paths, code, unfinished output and already delimited formulas fail closed.
 */
private fun looksLikeBareLatexContinuation(line: String): Boolean {
    val value = mathOutsideTextArguments(line.trim()) ?: return false
    if (!value.startsWith('=')) return false
    val groups = ArrayList<Char>()
    var commandSeen = false
    var index = 1
    while (index < value.length) {
        val c = value[index]
        when {
            c == '\\' -> {
                val start = ++index
                while (value.getOrNull(index)?.let { it in 'a'..'z' || it in 'A'..'Z' } == true) index++
                val command = value.substring(start, index)
                if (command.isEmpty()) {
                    if (value.getOrNull(index) !in listOf(',', ';', ':', '!', '{', '}', '|', ' ')) return false
                    index++
                } else {
                    if (command !in BARE_CONTINUATION_COMMANDS) return false
                    commandSeen = true
                }
            }
            c in 'a'..'z' || c in 'A'..'Z' -> {
                index++
                if (value.getOrNull(index)?.let { it in 'a'..'z' || it in 'A'..'Z' } == true) return false
            }
            c in "{([" -> { groups += c; index++ }
            c in "})]" -> {
                val opening = when (c) { '}' -> '{'; ')' -> '('; else -> '[' }
                if (groups.lastOrNull() != opening) return false
                groups.removeAt(groups.lastIndex)
                index++
            }
            c.isWhitespace() || c.isDigit() || c in "+-=<>^_.,|!" -> index++
            else -> return false
        }
    }
    return commandSeen && groups.isEmpty() && value.lastOrNull() !in listOf('^', '_', '\\', '+', '-', '=')
}

private val LATEX_TEXT_ARGUMENT_COMMANDS = setOf(
    "text", "textrm", "textsf", "texttt", "textnormal", "textbf", "textit", "mbox", "operatorname",
)
private val BARE_CONTINUATION_COMMANDS = DUPLICATE_LATEX_COMMANDS + LATEX_TEXT_ARGUMENT_COMMANDS + setOf(
    "arcsin", "arccos", "arctan", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr",
)

/** Inspect math tokens without rejecting Chinese inside a complete TeX text argument. */
private fun mathOutsideTextArguments(latex: String): String? {
    val out = StringBuilder(latex.length)
    var index = 0
    while (index < latex.length) {
        if (latex[index] != '\\') {
            out.append(latex[index++])
            continue
        }
        val start = index++
        val commandStart = index
        while (latex.getOrNull(index)?.let { it in 'a'..'z' || it in 'A'..'Z' } == true) index++
        if (index == commandStart && index < latex.length) index++
        out.append(latex, start, index)
        if (latex.substring(commandStart, index) !in LATEX_TEXT_ARGUMENT_COMMANDS) continue
        while (latex.getOrNull(index)?.isWhitespace() == true) out.append(latex[index++])
        if (latex.getOrNull(index) != '{') continue
        var depth = 1
        index++
        while (index < latex.length && depth > 0) {
            if (!latex.isEscaped(index)) {
                if (latex[index] == '{') depth++
                if (latex[index] == '}') depth--
            }
            index++
        }
        if (depth != 0) return null
        out.append("{}")
    }
    return out.toString()
}

private fun normalizeInlineMathDelimiters(line: String): String {
    val output = StringBuilder(line.length + 16)
    var index = 0
    var inlineCodeTicks = 0
    while (index < line.length) {
        if (line[index] == '`') {
            val tickCount = line.runLength(index, '`')
            inlineCodeTicks = when {
                inlineCodeTicks == 0 -> tickCount
                inlineCodeTicks == tickCount -> 0
                else -> inlineCodeTicks
            }
            output.append(line, index, index + tickCount)
            index += tickCount
            continue
        }
        if (inlineCodeTicks == 0 && !line.isEscaped(index)) {
            val opening = when {
                line.startsWith("\\(", index) -> "\\("
                line.startsWith("\\[", index) -> "\\["
                else -> null
            }
            if (opening != null) {
                val closing = if (opening == "\\(") "\\)" else "\\]"
                val closingIndex = line.indexOf(closing, index + opening.length)
                if (closingIndex >= 0) {
                    output.append("${'$'}${'$'}")
                    output.append(line, index + opening.length, closingIndex)
                    output.append("${'$'}${'$'}")
                    index = closingIndex + closing.length
                    continue
                }
            }
            if (line[index] == '$') {
                if (line.getOrNull(index + 1) == '$') {
                    output.append("${'$'}${'$'}")
                    index += 2
                    continue
                }
                val closingIndex = line.findClosingSingleDollar(index + 1)
                if (closingIndex >= 0) {
                    val body = line.substring(index + 1, closingIndex)
                    if (looksLikeInlineMath(body)) {
                        output.append("${'$'}${'$'}").append(body).append("${'$'}${'$'}")
                        index = closingIndex + 1
                        continue
                    }
                }
            }
        }
        output.append(line[index])
        index++
    }
    return output.toString()
}

private fun String.findClosingSingleDollar(startIndex: Int): Int {
    var index = startIndex
    while (index < length) {
        if (this[index] == '`') return -1
        if (this[index] == '$' && !isEscaped(index) && getOrNull(index - 1) != '$' && getOrNull(index + 1) != '$') {
            return index
        }
        index++
    }
    return -1
}

private fun String.isEscaped(index: Int): Boolean {
    var slashCount = 0
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') {
        slashCount++
        cursor--
    }
    return slashCount % 2 == 1
}

private fun String.runLength(startIndex: Int, char: Char): Int {
    var end = startIndex
    while (end < length && this[end] == char) end++
    return end - startIndex
}

private fun looksLikeInlineMath(raw: String): Boolean {
    val value = mathOutsideTextArguments(raw.trim()) ?: return false
    if (value.isBlank() || value.any { it in '\u3400'..'\u9fff' }) return false
    // 这里**不能**把纯数字排除掉。
    //
    // 原来是 `if (value.all { it.isDigit() || it == '.' || it == ',' }) return false`，
    // 于是 `$0$` / `$1$` 这种最简公式不会被提升成块级、独自留在行内 ——
    // 而同一段文本里其它公式（以及 `\(...\)` 写法）都会走提升路径。
    // 两条路径不一致，用户看到的就是「单独出现的 $0$ 渲染不出来」（v1.0.35 反馈）。
    // 纯数字在数学语境里本来就是公式（数值 0），没有理由区别对待。
    //
    // 会不会误判货币写法？不会：`$5 ... $10` 这种 body 里必然含空格或字母，
    // 仍然会被下面「数学符号 / 单变量」两条规则挡掉。
    if ('\\' in value) return true
    if (value.any { it in "=<>^_{}()[]+-*/|!≤≥≠≈∞∈∉⊂⊆⊃⊇∪∩∑∏∫√±×÷→⇒∂" }) return true
    if (value.all { it.isDigit() || it == '.' }) return true
    // Multi-letter symbols such as $QPSK$ or $abc$ are still explicitly
    // delimited mathematics. Leaving them untouched exposes the dollar signs
    // because the Markdown renderer only recognizes the normalized $$ form.
    if (Regex("""^[A-Za-z][A-Za-z0-9]*$""").matches(value)) return true
    if (Regex("""^[A-Za-z]_[A-Za-z0-9]+$""").matches(value)) return true
    return Regex("""^(?:sin|cos|tan|log|ln|lim|max|min)\s+[A-Za-z]$""").matches(value)
}

/**
 * 把模型偶尔产出的、JLatexMath 不支持或包裹不规范的 LaTeX 环境，
 * 在渲染前再清洗一次，降低公式以源码形式露出的概率。
 *
 * 处理项：
 * 1. 删除 \tag{}、\tag*{}、\hspace{}、\vspace{} 等不支持的命令；
 * 2. 删除 [6pt] 等行距/间距标记；
 * 3. 把落单的 \begin{aligned}...\end{aligned} 块补 $$ 并转换成 array 环境。
 */
internal fun sanitizeReplyLatex(markdown: String): String {
    var text = removeUnsupportedLatexCommands(markdown)
    text = convertAlignedEnvironments(text)
    return text
}

/**
 * 给 `\frac` 的裸参数补上花括号：`\frac B2` → `\frac{B}{2}`。
 *
 * 标准 LaTeX 允许 `\frac B2`（两个单 token），但 **JLatexMath 不接受**，会抛
 * ParseException，于是整条公式退化成「⚠ 公式无法渲染」的灰色占位
 * （用户反馈的 `0,\qquad f_c-\frac B2<|f|<f_c+\frac B2` 整条失败就是此因）。
 * 只补花括号、不动其它结构；本来就是 `\frac{}{}` 的写法是幂等的。
 *
 * 注意正则要求反斜杠后**紧跟** `frac`，所以 `\dfrac` 不会被误伤。
 */
private val FRAC_BRACELESS =
    Regex("""\\frac\s*(\{[^{}]*\}|[A-Za-z0-9])\s*(\{[^{}]*\}|[A-Za-z0-9])""")

internal fun normalizeLatexFractions(latex: String): String =
    FRAC_BRACELESS.replace(latex) { m ->
        val num = m.groupValues[1].removeSurrounding("{", "}")
        val den = m.groupValues[2].removeSurrounding("{", "}")
        """\frac{$num}{$den}"""
    }

private fun removeUnsupportedLatexCommands(text: String): String {
    var result = text
    result = result.replace(Regex("""\\tag(\*)?\s*\{[^}]*\}"""), "")
    result = result.replace(Regex("""\\hspace\s*\{[^}]*\}"""), "")
    result = result.replace(Regex("""\\vspace\s*\{[^}]*\}"""), "")
    result = result.replace(Regex("""\\hfill|\\vfill"""), "")
    // 模型偶尔在 \\ 后输出 [6pt]、[12pt] 等间距，JLatexMath 会解析失败。
    result = result.replace(
        Regex("""\[\d+(?:\.\d+)?(?:pt|em|ex|mm|cm|in)\]""", RegexOption.IGNORE_CASE),
        "",
    )
    // 需要额外宏包、JLatexMath 里根本不存在的命令（已逐个实测确认）。
    // 按语义降级，而不是留着让整段公式渲染失败：
    result = result.replace(Regex("""\\cancel\s*\{([^{}]*)\}""")) { it.groupValues[1] } // cancel 包
    result = result.replace(Regex("""\\color\s*\{[^{}]*\}""")) { "" } // xcolor 的 \color
    result = result.replace(Regex("""\\intertext\s*\{([^{}]*)\}""")) {
        "\\\\ \\text{${it.groupValues[1]}}" // amsmath：降级成「换行 + 文本」
    }
    result = result.replace(Regex("""\\begin\{dcases\}""")) { "\\begin{cases}" } // mathtools
    result = result.replace(Regex("""\\end\{dcases\}""")) { "\\end{cases}" }
    result = normalizeUnsupportedDelimiters(result)
    // \frac 的裸参数补花括号（JLatexMath 不支持 \frac B2），放最后统一处理
    result = normalizeLatexFractions(result)
    return result
}

/**
 * 降级 JLatexMath 不支持的左右成对定界符命令。
 *
 * ⚠️ **实测结论（[JLatexMathDelimiterSupportTest] 里的探测表，改前务必先读）**：
 * JLatexMath 对大小写变体的支持**极不对称**——
 *
 * | 写法 | 结果 |
 * |---|---|
 * | `\lvert … \rvert` | ❌ ParseException |
 * | `\lVert … \rVert` | ✅ |
 * | `\left\lvert … \right\rvert` | ❌ ParseException |
 * | `\|…\|` / `\left\|…\right\|` | ✅ |
 * | `|…|` / `\left|…\right|` | ✅ |
 * | `\lbrace`/`\langle`/`\lceil`/`\lfloor` | ✅ |
 *
 * 即：**双竖线能用、单竖线不能用**（只差一个字母大小写）。用户截图里
 * `\lvert y_L(T)\rvert` 整条渲染失败就是此因。
 *
 * 所以必须**分开降级**，绝不能把两类混成一个规则：
 * - `\lvert`/`\rvert` → `|`（绝对值/模，单竖线语义）
 * - `\lVert`/`\rVert` → `\|`（范数，双竖线语义）
 *
 * 若把 `\lVert` 也换成单竖线，会**悄悄改变数学含义**（把范数写成绝对值），
 * 比渲染失败更糟——渲染失败用户看得见，语义变错看不见。
 *
 * `\left`/`\right` 前缀要**一起换**（`\left\lvert` → `\left|`）：只换掉后半段会
 * 留下 `\left|`，虽然恰好也合法，但分两步走容易在「`\left` 后面不是 `\lvert`」
 * 这类形态上误伤，整体匹配更稳。
 */
private fun normalizeUnsupportedDelimiters(latex: String): String {
    var result = latex
    // ⚠️ 替换串一律用 **lambda 形式** `{ "…" }`，不要用字符串重载：
    // `Regex.replace(input, "\\|")` 走的是 Java `Matcher.replaceAll` 语义，
    // 会把替换串里的 `\` 当转义符再解析一次 ⇒ `\|` 变成字面 `|`，
    // 反斜杠被静默吃掉（范数 `\lVert x \rVert` 会退化成 `| x |`，语义从
    // 「范数」悄悄变成「绝对值」——渲染成功但含义错了，比渲染失败更糟）。
    // lambda 的返回值是字面量，不经过转义解析，所见即所得。
    // 双竖线（范数）
    result = result.replace(Regex("""\\left\s*\\lVert""")) { "\\left\\|" }
    result = result.replace(Regex("""\\right\s*\\rVert""")) { "\\right\\|" }
    result = result.replace(Regex("""\\lVert""")) { "\\|" }
    result = result.replace(Regex("""\\rVert""")) { "\\|" }
    // 单竖线（绝对值/模）——这个是真正的 FAIL 项，必须降级
    result = result.replace(Regex("""\\left\s*\\lvert""")) { "\\left|" }
    result = result.replace(Regex("""\\right\s*\\rvert""")) { "\\right|" }
    result = result.replace(Regex("""\\lvert""")) { "|" }
    result = result.replace(Regex("""\\rvert""")) { "|" }
    return result
}

private val KNOWN_MATH_ENVIRONMENTS = setOf(
    "aligned", "aligned*", "alignedat", "align", "align*",
    "gather", "gather*", "equation", "equation*", "eqnarray", "eqnarray*",
    "cases", "matrix", "pmatrix", "bmatrix", "vmatrix", "Vmatrix", "Bmatrix",
    "array", "split", "multline",
)

private val KNOWN_MATH_ENV_PATTERN = Regex(
    """\\begin\{(${KNOWN_MATH_ENVIRONMENTS.joinToString("|") { Regex.escape(it) }})\}""",
)

/**
 * 遍历 Markdown（跳过代码块），把 \begin{aligned} 等独立数学环境补齐 $$ 包裹，
 * 并把 aligned 转换成 JLatexMath 更友好的 array 环境。
 */
private fun convertAlignedEnvironments(markdown: String): String {
    val lines = markdown.split('\n')
    val out = ArrayList<String>(lines.size + 8)
    var i = 0
    var fence: String? = null
    while (i < lines.size) {
        val line = lines[i]
        val trimmedStart = line.trimStart()
        val lineFence = when {
            trimmedStart.startsWith("```") -> "```"
            trimmedStart.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (lineFence != null) {
            fence = if (fence == null) lineFence else if (fence == lineFence) null else fence
            out += line
            i++
            continue
        }
        if (fence != null) {
            out += line
            i++
            continue
        }
        val envMatch = KNOWN_MATH_ENV_PATTERN.find(line)
        if (envMatch != null) {
            val envName = envMatch.groupValues[1]
            val beginRe = Regex("""\\begin\{${Regex.escape(envName)}\}""")
            val endRe = Regex("""\\end\{${Regex.escape(envName)}\}""")
            // 先数起始行自身：模型最常把 cases/matrix 等写成「\begin{...}...\end{...}」单行，
            // 之前只往后续行找闭合，单行环境永远匹配不到 → 不补 $$ → 整段落成 Markdown 纯文本
            // （表现为 \\ 被 Markdown 吃成 \ 、& 原样露出）。
            var depth = beginRe.findAll(line).count() - endRe.findAll(line).count()
            if (depth == 0) {
                // 单行环境已在本行闭合。行内任一侧已带 $$ 的（已在公式里）原样放行，
                // 交给 wrapLongFormulas 按宽度拆分；其余整行补 $$ 包裹。
                // 还要统计环境之前的整份文档：多行显示块经常是「$$ 单独一行 + 环境单独一行」，
                // 此时当前行两侧都没有 $$，但环境仍已处于打开的显示块内。
                val alreadyInMath =
                    countDisplayDelimiters(lines, 0, i) % 2 == 1 ||
                        line.substring(0, envMatch.range.first).contains("$$") ||
                        line.substring(envMatch.range.last + 1).contains("$$")
                if (alreadyInMath) {
                    out += line
                } else {
                    out += "$$" + line.trim() + "$$"
                }
                i++
                continue
            }
            val startIdx = i
            var j = i + 1
            while (j < lines.size && depth > 0) {
                val l = lines[j]
                depth += beginRe.findAll(l).count()
                depth -= endRe.findAll(l).count()
                if (depth == 0) break
                j++
            }
            if (j < lines.size && depth == 0) {
                val endIdx = j
                // 这个环境是否**已经处在某个未闭合的 $$ 显示块内部**？
                //
                // 只判断「上一行是不是以 $$ 开头」是不够的 —— 模型很常把公式写成
                //     $$
                //     S_{Y_c}(f) = S_{Y_s}(f) =      ← 等号收尾，environment 在下一行
                //     \begin{cases}
                //     ...
                //     \end{cases}
                //     $$
                // 此时上一行既不以 $$ 开头、也不以 $$ 结尾，于是被判为「未包裹」，
                // 我们再补一对 $$ 塞进去 ⇒ **块内凭空多出一对定界符**，
                // 显示块被从中间劈开：`=` 之前的内容渲染成公式，`\begin{cases}` 起始的
                // 后半段掉出数学上下文、以 LaTeX 源码原样显示
                // （用户截图正是「`S_{Y_c}(f) = S_{Y_s}(f) =` 是公式，下面 cases 是源码」）。
                //
                // 正确判据是**数 $$ 的收支**：从文档开头累计到环境起始行之前，
                // 若已出现**奇数**个 $$，说明显示块已经打开、环境本就在其中，绝不能补。
                val insideOpenDisplay =
                    countDisplayDelimiters(lines, 0, startIdx) % 2 == 1
                // 兼容旧判据：上一行以 $$ 开头（含「$$ 内容」）且环境之后紧跟收尾 $$ 的写法
                val legacyWrapped = startIdx > 0 && lines[startIdx - 1].trimStart().startsWith("$$") &&
                    endIdx + 1 < lines.size && lines[endIdx + 1].trimEnd().endsWith("$$")
                val alreadyWrapped = insideOpenDisplay || legacyWrapped
                val block = (startIdx..endIdx).joinToString("\n") { lines[it] }
                val transformed = transformEnvironment(block, envName)
                if (!alreadyWrapped) {
                    out += "$$"
                    out += transformed
                    out += "$$"
                } else {
                    out += transformed
                }
                i = endIdx + 1
                continue
            }
        }
        out += line
        i++
    }
    return out.joinToString("\n")
}

/**
 * 统计 [from, until) 行范围内出现的 `$$` 定界符个数。
 *
 * 用于判断某个位置是否已经处在未闭合的显示块内部（奇数 = 已打开）。
 * 只数成对出现的 `$$`；行内的 `$...$`（单美元）不计入，因为它不改变显示块状态。
 * 代码块内部的 `$$` 同样不计入 —— 那里的内容是字面量，不参与数学块配对。
 */
private fun countDisplayDelimiters(lines: List<String>, from: Int, until: Int): Int {
    var count = 0
    var fence: String? = null
    for (index in from until minOf(until, lines.size)) {
        val line = lines[index]
        val trimmedStart = line.trimStart()
        val lineFence = when {
            trimmedStart.startsWith("```") -> "```"
            trimmedStart.startsWith("~~~") -> "~~~"
            else -> null
        }
        if (lineFence != null) {
            fence = if (fence == null) lineFence else if (fence == lineFence) null else fence
            continue
        }
        if (fence != null) continue
        var i = 0
        while (i < line.length - 1) {
            if (line[i] == '$' && line[i + 1] == '$') {
                count++
                i += 2
            } else {
                i++
            }
        }
    }
    return count
}

/**
 * 把 aligned 环境改写成 JLatexMath 更友好的 array 环境。
 *
 * 只要出现「拿不准」的结构（嵌套环境、表格线、行数或列数过多、大括号 / \left \right 不配对），
 * 就原样返回交给渲染器处理：宁可显示源码，也不要生成一个解析必然失败、进而拖垮整段渲染的 array。
 *
 * aligned 各行的 `&` 数量可以不一样，但 array 要求所有行列数一致；
 * 这里先算出最大 `&` 数，再给每行补齐到统一列数。
 */
private fun transformEnvironment(block: String, envName: String): String {
    // equation 是 JLatexMath 唯一不认识的常见环境（实测报 "Unknown environment: equation"；
    // align / gather / multline / eqnarray / split / aligned 都能渲染）。
    // 它只表示「独立成行 + 编号」，剥掉标签对公式内容毫无影响，外层 $$ 由调用方补。
    // 之前 KNOWN_MATH_ENVIRONMENTS 把它列进白名单却没人转换它，于是每次都原样送进渲染器、
    // 每次都抛异常 → 表现为「同一处公式反复修也渲染不出来」。
    if (envName == "equation" || envName == "equation*") {
        return block
            .replace(Regex("""\\begin\{equation\*?\}"""), "")
            .replace(Regex("""\\end\{equation\*?\}"""), "")
            .trim()
    }
    if (envName != "aligned" && envName != "aligned*") return block
    val begin = "\\begin{$envName}"
    val end = "\\end{$envName}"
    val trimmed = block.trim()
    if (!trimmed.startsWith(begin) || !trimmed.endsWith(end)) return block
    val inner = trimmed.removePrefix(begin).removeSuffix(end).trim()
    if (inner.isEmpty()) return block
    if (inner.contains("\\begin{") || inner.contains("\\hline")) return block
    // 大括号 / \left \right 不配对就别转：array 解析会爆，转回去还能让 JLatexMath 自己拼。
    if (inner.count { it == '{' } != inner.count { it == '}' }) return block
    if (Regex("""\\left\b""").findAll(inner).count() != Regex("""\\right\b""").findAll(inner).count()) return block
    val rows = inner.split(Regex("""\\\\""")).map { it.trim() }.filter { it.isNotEmpty() }
    if (rows.isEmpty() || rows.size > MAX_ARRAY_ROWS) return block
    val maxAmpersands = rows.maxOf { row -> row.count { it == '&' } }
    val columns = maxAmpersands + 1
    if (columns > MAX_ARRAY_COLUMNS) return block
    val spec = "c".repeat(columns)
    // 把每行的 `&` 数补齐到 maxAmpersands，避免 array 列数不一致
    val padded = rows.map { row ->
        val missing = maxAmpersands - row.count { it == '&' }
        if (missing <= 0) row else row.trimEnd() + (1..missing).joinToString("") { " &" }
    }
    val body = padded.joinToString(" \\\\ ")
        .replace(Regex("""\s*&\s*"""), " & ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return "\\begin{array}{$spec} $body \\end{array}"
}

private const val MAX_ARRAY_ROWS = 12
private const val MAX_ARRAY_COLUMNS = 6
