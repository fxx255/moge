package com.moge.app.ui.markdown

/**
 * A piece of a streamed answer that is safe to hand to Markwon.
 *
 * The id is based on the answer generation and the source offset.  As more text
 * arrives, an already committed piece keeps the same id, so its AndroidView is
 * kept alive. The last receive buffer is [StreamingMarkdownSnapshot.tail];
 * [StreamingMarkdownSnapshot.visibleEnd] excludes its unfinished syntax. Closed
 * spans can be cached while safe prose continues to arrive after them.
 */
internal data class StreamingMarkdownBlock(
    val id: Long,
    val text: String,
)

internal data class StreamingMarkdownSnapshot(
    val blocks: List<StreamingMarkdownBlock>,
    val tail: String,
    val tailStart: Int = 0,
    val tailId: Long = 0,
    /** Absolute source offset through the last safe, closed formula or bold span in tail. */
    val renderedPrefixEnd: Int = 0,
    /** Unfinished syntax stays in the receive buffer, outside the painted tail. */
    val visibleEnd: Int = Int.MAX_VALUE,
    val waiting: Boolean = false,
)

/**
 * Incremental safety scanner for Markdown/LaTeX output.
 *
 * This is deliberately a boundary scanner rather than a Markdown parser.  It
 * only commits text when it is outside code/math and one of these boundaries
 * has been reached:
 *
 *  * a blank line (paragraph/list/table boundary), or
 *  * a closed formula or bold prefix inside the current paragraph.
 *
 * The scanner never sends an unterminated formula, code fence, or half table to
 * Markwon.  That prevents a stream update from replacing a valid drawable with
 * a temporary parse error. Completion flushes this same document tree. This is
 * only a display boundary; it does not rewrite or persist the received answer.
 */
internal class StreamingMarkdownTokenizer {

    private var previousContent = ""
    private var generation = 0L

    fun update(content: String, flush: Boolean = false): StreamingMarkdownSnapshot {
        // A reset/retry/continuation can replace the visible prefix.  Do not
        // reuse keys from the previous generation in that case: old Android
        // views may still have an asynchronous formula callback in flight.
        if (!content.startsWith(previousContent) && !content.startsWith(previousContent.trimEnd())) {
            generation++
        }
        previousContent = content

        if (content.isEmpty()) return StreamingMarkdownSnapshot(emptyList(), "", tailId = generation * GENERATION_KEY_STRIDE)
        val scan = scan(content).let { scanned ->
            val tableCuts = tableEndCuts(content, scanned.tablePipes).filter { it <= scanned.openSyntaxStart }
            scanned.copy(safeCuts = (scanned.safeCuts + tableCuts).distinct().sorted(),
                lastSafeCut = maxOf(scanned.lastSafeCut, tableCuts.maxOrNull() ?: 0))
        }
        val visibleEnd = if (flush) content.length else safeDisplayEnd(scan, content)
        var stableLimit = scan.lastSafeCut
        if (flush && !scan.inCodeFence && scan.mathDelimiter == MathDelimiter.NONE) {
            stableLimit = content.length
        }
        stableLimit = stableLimit.coerceIn(0, content.length)

        if (stableLimit == 0) {
            return StreamingMarkdownSnapshot(
                emptyList(), content,
                tailId = generation * GENERATION_KEY_STRIDE,
                renderedPrefixEnd = minOf(visibleEnd, safeRenderedPrefixEnd(scan, content, 0)),
                visibleEnd = visibleEnd,
                waiting = visibleEnd < content.length,
            )
        }

        val cuts = scan.safeCuts
            .asSequence()
            .filter { it in 1..stableLimit }
            .distinct()
            .sorted()
            .toList()
            .let { values ->
                // A forced flush may make the final paragraph complete without
                // having a newline/formula boundary in the source.
                if (flush && stableLimit == content.length && values.lastOrNull() != stableLimit) {
                    values + stableLimit
                } else values
            }

        val blocks = ArrayList<StreamingMarkdownBlock>(cuts.size)
        var start = 0
        cuts.forEach { cut ->
            val raw = content.substring(start, cut)
            // Blank lines are layout spacing, not useful Markwon views.  The
            // parent Column already supplies stable spacing between blocks.
            val text = raw.trim('\r', '\n')
            if (text.isNotBlank()) {
                blocks += StreamingMarkdownBlock(
                    id = generation * GENERATION_KEY_STRIDE + start.toLong(),
                    text = text,
                )
            }
            start = cut
        }

        val tail = content.substring(start)
        return StreamingMarkdownSnapshot(
            blocks, tail, start,
            tailId = generation * GENERATION_KEY_STRIDE + start.toLong(),
            renderedPrefixEnd = minOf(visibleEnd, safeRenderedPrefixEnd(scan, content, start)),
            visibleEnd = visibleEnd,
            waiting = visibleEnd < content.length,
        )
    }

    private fun safeRenderedPrefixEnd(scan: ScanResult, content: String, tailStart: Int): Int {
        val tail = content.substring(tailStart)
        // An unfinished table stays hidden; formulas in its cells must not
        // advance the parsed prefix into a table whose widths can still change.
        val lines = tail.lineSequence().toList()
        var lineOffset = 0
        var tableStart = content.length
        lines.zipWithNext().forEach { (a, b) ->
            if (tableStart == content.length && '|' in a && isMarkdownTableSeparator(b)) tableStart = tailStart + lineOffset
            lineOffset += a.length + 1
        }
        val closed = scan.closedInlineEnds.lastOrNull { end ->
            end > tailStart && end <= tableStart && !isPipeLine(scan, content, end)
        }
        // Literal prose after the last closed span stays outside Markwon, which
        // otherwise trims its trailing space and rebuilds every preceding formula.
        // Heading/code styles own all their text; a simple list can cache its
        // closed inline prefix and retain its paragraph indentation.
        if ((BLOCK_STYLE.containsMatchIn(tail) || RULE_LINE.containsMatchIn(tail)) &&
            (closed == null || tail.contains('\n') || HEADING_STYLE.containsMatchIn(tail))) {
            return minOf(scan.openSyntaxStart, safeDisplayEnd(scan, content))
        }
        return closed ?: tailStart
    }

    private fun isPipeLine(scan: ScanResult, content: String, end: Int): Boolean {
        val start = content.lastIndexOf('\n', end - 1).let { if (it < 0) 0 else it + 1 }
        val lineEnd = content.indexOf('\n', end).let { if (it < 0) content.length else it }
        return scan.tablePipes.any { it >= start && it < lineEnd }
    }

    private data class ScanResult(
        val safeCuts: List<Int>,
        val lastSafeCut: Int,
        val inCodeFence: Boolean,
        val mathDelimiter: MathDelimiter,
        val closedInlineEnds: List<Int>,
        val openMathStart: Int,
        val tablePipes: List<Int>,
        val openSyntaxStart: Int,
    )

    private enum class MathDelimiter {
        NONE,
        SINGLE_DOLLAR,
        DOUBLE_DOLLAR,
        PAREN,
        BRACKET,
    }

    private fun scan(content: String): ScanResult {
        val cuts = ArrayList<Int>()
        var fence: String? = null
        var math = MathDelimiter.NONE
        var index = 0
        var lineStart = true
        var lastSafe = 0
        val closedInlineEnds = ArrayList<Int>()
        var openMathStart = -1
        val tablePipes = ArrayList<Int>()
        var codeTicks = 0
        val openMarks = mutableMapOf<String, Int>()
        var fenceStart = -1
        var inlineCodeStart = -1
        var linkStart = -1

        while (index < content.length) {
            if (lineStart && math == MathDelimiter.NONE && codeTicks == 0) {
                val ruleEnd = content.indexOf('\n', index)
                if (fence == null && ruleEnd >= 0 && RULE_LINE.matches(content.substring(index, ruleEnd))) {
                    closedInlineEnds += ruleEnd
                    index = ruleEnd + 1
                    lineStart = true
                    continue
                }
                val marker = fenceMarkerAt(content, index)
                if (marker != null) {
                    if (fence == null) fenceStart = index
                    fence = if (fence == null) marker.token else if (fence == marker.token) null else fence
                    if (fence == null) { fenceStart = -1; closedInlineEnds += marker.start + marker.token.length }
                    // `index` points at the beginning of the line.  A fence may
                    // be indented, so advancing only by the marker length would
                    // leave the scanner inside the leading spaces and make the
                    // rest of the line look like ordinary text.
                    index = marker.start + marker.token.length
                    lineStart = false
                    continue
                }
            }

            if (fence != null) {
                if (content[index] == '\n') {
                    index++
                    lineStart = true
                } else {
                    index++
                    lineStart = false
                }
                continue
            }

            // Inline code is literal, including its asterisks and dollars. A
            // different backtick run does not close it. Blank paragraphs reset
            // incomplete inline code, just as they do in CommonMark.
            if (math == MathDelimiter.NONE && content[index] == '`' && !content.isEscaped(index)) {
                var end = index + 1
                while (content.getOrNull(end) == '`') end++
                val count = end - index
                codeTicks = when (codeTicks) {
                    0 -> count
                    count -> 0
                    else -> codeTicks
                }
                if (codeTicks == 0) { inlineCodeStart = -1; closedInlineEnds += end }
                else if (inlineCodeStart < 0) inlineCodeStart = index
                index = end
                lineStart = false
                continue
            }
            if (codeTicks > 0 && content[index] != '\n') {
                index++
                lineStart = false
                continue
            }

            // Math delimiters must be checked before ordinary backslashes and
            // dollars.  Escaped delimiters remain ordinary source text.
            when (math) {
                MathDelimiter.PAREN -> {
                    if (content.startsWith("\\)", index) && !content.isEscaped(index)) {
                        math = MathDelimiter.NONE
                        index += 2
                        closedInlineEnds += index
                        openMathStart = -1
                        lineStart = false
                        continue
                    }
                }
                MathDelimiter.BRACKET -> {
                    if (content.startsWith("\\]", index) && !content.isEscaped(index)) {
                        math = MathDelimiter.NONE
                        index += 2
                        closedInlineEnds += index
                        openMathStart = -1
                        lineStart = false
                        continue
                    }
                }
                MathDelimiter.DOUBLE_DOLLAR -> {
                    if (content.startsWith("$$", index) && !content.isEscaped(index)) {
                        math = MathDelimiter.NONE
                        index += 2
                        closedInlineEnds += index
                        openMathStart = -1
                        lineStart = false
                        continue
                    }
                }
                MathDelimiter.SINGLE_DOLLAR -> {
                    if (content[index] == '$' && !content.isEscaped(index)) {
                        math = MathDelimiter.NONE
                        index++
                        closedInlineEnds += index
                        openMathStart = -1
                        lineStart = false
                        continue
                    }
                }
                MathDelimiter.NONE -> Unit
            }

            if (math == MathDelimiter.NONE && codeTicks == 0) {
                if (content[index] == '[' && !content.isEscaped(index) && linkStart < 0) linkStart = index
                if (linkStart >= 0) {
                    val figure = content.startsWith("[[", linkStart)
                    if (figure && content.startsWith("]]", index)) {
                        linkStart = -1
                    } else if (!figure && content[index] == ']' && content.getOrNull(index + 1) != '(' && index + 1 < content.length) {
                        linkStart = -1
                    } else if (!figure && content[index] == ')' && content.substring(linkStart, index).contains("](")) {
                        closedInlineEnds += index + 1
                        linkStart = -1
                    }
                }
                // This only finds a safe render boundary. Markwon still owns
                // emphasis parsing, nesting, punctuation and escaped markers.
                if (content[index] in "*_~" && !content.isEscaped(index)) {
                    val marker = content[index]
                    var end = index + 1
                    while (content.getOrNull(end) == marker) end++
                    val token = content.substring(index, end)
                    val intraword = marker == '_' && content.getOrNull(index - 1)?.isLetterOrDigit() == true &&
                        content.getOrNull(end)?.isLetterOrDigit() == true
                    val bullet = token == "*" && lineStart && content.getOrNull(end)?.isWhitespace() == true
                    if (!intraword && !bullet && (marker != '~' || token.length >= 2)) {
                        val opening = openMarks[token]
                        if (opening != null && index > opening + token.length && !content[index - 1].isWhitespace()) {
                            closedInlineEnds += end
                            openMarks.remove(token)
                        } else if (content.getOrNull(end)?.isWhitespace() != true) {
                            openMarks[token] = index
                        }
                    }
                    index = end
                    lineStart = false
                    continue
                }
                if (content[index] == '|' && !content.isEscaped(index)) tablePipes += index
                if (content.startsWith("\\(", index) && !content.isEscaped(index)) {
                    math = MathDelimiter.PAREN
                    openMathStart = index
                    index += 2
                    lineStart = false
                    continue
                }
                if (content.startsWith("\\[", index) && !content.isEscaped(index)) {
                    math = MathDelimiter.BRACKET
                    openMathStart = index
                    index += 2
                    lineStart = false
                    continue
                }
                if (content.startsWith("$$", index) && !content.isEscaped(index)) {
                    math = MathDelimiter.DOUBLE_DOLLAR
                    openMathStart = index
                    index += 2
                    lineStart = false
                    continue
                }
                if (content[index] == '$' && !content.isEscaped(index)) {
                    math = MathDelimiter.SINGLE_DOLLAR
                    openMathStart = index
                    index++
                    lineStart = false
                    continue
                }
            }

            if (content[index] == '\n') {
                index++
                // A blank line is a safe Markdown block boundary.  Do not
                // commit a line while a formula is open or inside a fence.
                if (math == MathDelimiter.NONE && content.getOrNull(index) == '\n') {
                    // Consume the second newline as part of the boundary.  If
                    // it were left in the tail, every newly committed block
                    // would make the plain-text suffix start with an extra
                    // visible line break.
                    while (content.getOrNull(index) == '\n') index++
                    codeTicks = 0
                    openMarks.clear()
                    inlineCodeStart = -1
                    linkStart = -1
                    lastSafe = index
                    cuts += index
                }
                lineStart = true
            } else {
                index++
                lineStart = false
            }
        }

        val open = (listOf(openMathStart, fenceStart, inlineCodeStart, linkStart) + openMarks.values)
            .filter { it >= 0 }.minOrNull() ?: content.length
        return ScanResult(cuts, lastSafe, fence != null, math, closedInlineEnds, openMathStart, tablePipes, open)
    }

    private fun safeDisplayEnd(scan: ScanResult, content: String): Int {
        var end = scan.openSyntaxStart
        // A table is measured only when all rows have arrived. A possible header
        // stays hidden until its next line confirms ordinary prose or a separator.
        for (pipe in scan.tablePipes) {
            if (pipe < scan.lastSafeCut) continue
            val start = content.lastIndexOf('\n', pipe).let { if (it < 0) 0 else it + 1 }
            val newline = content.indexOf('\n', pipe)
            if (newline < 0) { end = minOf(end, start); break }
            val nextEnd = content.indexOf('\n', newline + 1).let { if (it < 0) content.length else it }
            val next = content.substring(newline + 1, nextEnd).trim()
            if (next.isEmpty() || next.all { it == '|' || it == '-' || it == ':' || it.isWhitespace() }) {
                end = minOf(end, start); break
            }
        }
        // A split escape/marker may change meaning when its very next token arrives.
        if (content.endsWith('\\') && !content.isEscaped(content.lastIndex)) end = minOf(end, content.lastIndex)
        val lineStart = content.lastIndexOf('\n') + 1
        val line = content.substring(lineStart)
        if (UNFINISHED_MARKER.matches(line)) end = minOf(end, lineStart)
        return end.coerceIn(scan.lastSafeCut, content.length)
    }

    private fun tableEndCuts(content: String, pipes: List<Int>): List<Int> {
        if (pipes.isEmpty()) return emptyList()
        val lines = content.split('\n')
        val starts = IntArray(lines.size)
        for (line in 1 until lines.size) starts[line] = starts[line - 1] + lines[line - 1].length + 1
        val pipeStarts = pipes.map { content.lastIndexOf('\n', it).let { at -> if (at < 0) 0 else at + 1 } }.toSet()
        val cuts = mutableListOf<Int>()
        var line = 0
        while (line + 1 < lines.size) {
            if (starts[line] !in pipeStarts || !isMarkdownTableSeparator(lines[line + 1])) { line++; continue }
            var next = line + 2
            while (next < lines.size && starts[next] in pipeStarts) next++
            // A completed non-table line confirms the final row even without a
            // blank paragraph. Its partial prefix could still grow another pipe.
            if (next < lines.lastIndex) cuts += starts[next]
            line = maxOf(next, line + 1)
        }
        return cuts
    }

    private data class FenceMarker(val start: Int, val token: String)

    private fun fenceMarkerAt(content: String, start: Int): FenceMarker? {
        var index = start
        while (index < content.length && content[index] == ' ') index++
        return when {
            content.startsWith("```", index) -> FenceMarker(index, "```")
            content.startsWith("~~~", index) -> FenceMarker(index, "~~~")
            else -> null
        }
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

    private companion object {
        // Keep enough room for a long answer while still producing deterministic
        // keys.  This is a key namespace only; it is never persisted.
        const val GENERATION_KEY_STRIDE = 1_000_000_000L
        private val BLOCK_STYLE = Regex("(?m)^[ \\t]*(?:#{1,6}[ \\t]|>[ \\t]?|[-+*][ \\t]|[0-9]+[.)][ \\t]|`{3,}|~{3,})")
        private val HEADING_STYLE = Regex("^[ \\t]*(?:#{1,6}[ \\t]|>[ \\t]?)")
        private val UNFINISHED_MARKER = Regex("[ \\t]*(?:#{1,6}|[-+*>]|(?:[-*_][ \\t]*)+|[0-9]+[.)]?)[ \\t]*")
        private val RULE_LINE = Regex("(?m)^[ \\t]*(?:(?:\\*[ \\t]*){3,}|(?:-[ \\t]*){3,}|(?:_[ \\t]*){3,})$")
    }
}
