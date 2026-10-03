package com.moge.app.ui.markdown

/**
 * A piece of a streamed answer that is safe to hand to Markwon.
 *
 * The id is based on the answer generation and the source offset.  As more text
 * arrives, an already committed piece keeps the same id, so its AndroidView is
 * kept alive and is not rendered again.  The last (unfinished) piece is kept in
 * [StreamingMarkdownSnapshot.tail].  A closed formula prefix inside that tail
 * is exposed separately so the UI can cache its parsed spans while leaving the
 * still-growing suffix as plain text.
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
    /** Absolute source offset through the last safe, closed formula in tail. */
    val mathPrefixEnd: Int = 0,
)

/**
 * Incremental safety scanner for Markdown/LaTeX output.
 *
 * This is deliberately a boundary scanner rather than a Markdown parser.  It
 * only commits text when it is outside code/math and one of these boundaries
 * has been reached:
 *
 *  * a blank line (paragraph/list/table boundary), or
 *  * a closed formula prefix inside the current paragraph.
 *
 * The scanner never sends an unterminated formula, code fence, or half table to
 * Markwon.  That prevents a stream update from replacing a valid drawable with
 * a temporary parse error.  The final completed answer still goes through the
 * normal full Markdown path, so this is only a rendering-time safety boundary;
 * it does not rewrite or persist the answer.
 */
internal class StreamingMarkdownTokenizer {

    private var previousContent = ""
    private var generation = 0L

    fun update(content: String, flush: Boolean = false): StreamingMarkdownSnapshot {
        // A reset/retry/continuation can replace the visible prefix.  Do not
        // reuse keys from the previous generation in that case: old Android
        // views may still have an asynchronous formula callback in flight.
        if (!content.startsWith(previousContent)) {
            generation++
        }
        previousContent = content

        if (content.isEmpty()) return StreamingMarkdownSnapshot(emptyList(), "", tailId = generation * GENERATION_KEY_STRIDE)
        val scan = scan(content)
        var stableLimit = scan.lastSafeCut
        if (flush && !scan.inCodeFence && scan.mathDelimiter == MathDelimiter.NONE) {
            stableLimit = content.length
        }
        stableLimit = stableLimit.coerceIn(0, content.length)

        if (stableLimit == 0) {
            return StreamingMarkdownSnapshot(
                emptyList(), content,
                tailId = generation * GENERATION_KEY_STRIDE,
                mathPrefixEnd = safeMathPrefixEnd(scan, content, 0),
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
            mathPrefixEnd = safeMathPrefixEnd(scan, content, start),
        )
    }

    private fun safeMathPrefixEnd(scan: ScanResult, content: String, tailStart: Int): Int {
        val tail = content.substring(tailStart)
        // A growing GFM table must stay plain until its blank-line boundary.
        // A closed formula before the table may remain rendered, but a formula
        // inside a partial table must not trigger a table reparse per token.
        val lines = tail.lineSequence().toList()
        var lineOffset = 0
        var tableStart = content.length
        lines.zipWithNext().forEach { (a, b) ->
            if (tableStart == content.length && '|' in a && b.trim().let {
                    it.contains('-') && it.all { ch -> ch == '|' || ch == '-' || ch == ':' || ch.isWhitespace() }
                }) tableStart = tailStart + lineOffset
            lineOffset += a.length + 1
        }
        // When a new formula is open, keep the complete text before its
        // opening delimiter in the stable prefix.  This includes the prose
        // after an earlier closed formula, so opening a second formula never
        // makes the first formula or the intervening text fall back to plain
        // rendering.  The opening delimiter and its unfinished body remain in
        // the plain suffix until the pair is closed.
        if (scan.mathDelimiter != MathDelimiter.NONE && scan.openMathStart >= tailStart) {
            return scan.openMathStart
        }
        return scan.closedMathEnds.lastOrNull { end ->
            end > tailStart && end <= tableStart && !isPipeLine(scan, content, end)
        } ?: tailStart
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
        val closedMathEnds: List<Int>,
        val openMathStart: Int,
        val tablePipes: List<Int>,
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
        val closedMathEnds = ArrayList<Int>()
        var openMathStart = -1
        val tablePipes = ArrayList<Int>()

        while (index < content.length) {
            if (lineStart && math == MathDelimiter.NONE) {
                val marker = fenceMarkerAt(content, index)
                if (marker != null) {
                    fence = if (fence == null) marker.token else if (fence == marker.token) null else fence
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

            // Math delimiters must be checked before ordinary backslashes and
            // dollars.  Escaped delimiters remain ordinary source text.
            when (math) {
                MathDelimiter.PAREN -> {
                    if (content.startsWith("\\)", index) && !content.isEscaped(index)) {
                        math = MathDelimiter.NONE
                        index += 2
                        closedMathEnds += index
                        openMathStart = -1
                        lineStart = false
                        continue
                    }
                }
                MathDelimiter.BRACKET -> {
                    if (content.startsWith("\\]", index) && !content.isEscaped(index)) {
                        math = MathDelimiter.NONE
                        index += 2
                        closedMathEnds += index
                        openMathStart = -1
                        lineStart = false
                        continue
                    }
                }
                MathDelimiter.DOUBLE_DOLLAR -> {
                    if (content.startsWith("$$", index) && !content.isEscaped(index)) {
                        math = MathDelimiter.NONE
                        index += 2
                        closedMathEnds += index
                        openMathStart = -1
                        lineStart = false
                        continue
                    }
                }
                MathDelimiter.SINGLE_DOLLAR -> {
                    if (content[index] == '$' && !content.isEscaped(index)) {
                        math = MathDelimiter.NONE
                        index++
                        closedMathEnds += index
                        openMathStart = -1
                        lineStart = false
                        continue
                    }
                }
                MathDelimiter.NONE -> Unit
            }

            if (math == MathDelimiter.NONE) {
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
                    lastSafe = index
                    cuts += index
                }
                lineStart = true
            } else {
                index++
                lineStart = false
            }
        }

        return ScanResult(cuts, lastSafe, fence != null, math, closedMathEnds, openMathStart, tablePipes)
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
    }
}
