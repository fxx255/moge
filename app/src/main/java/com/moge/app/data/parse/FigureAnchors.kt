package com.moge.app.data.parse

/** A protocol anchor in Markdown prose; [range] excludes surrounding whitespace. */
data class FigureAnchor(
    val range: IntRange,
    val numberText: String,
    val isStandalone: Boolean,
) {
    val number: Int? get() = numberText.toIntOrNull()
}

private val FIGURE_ANCHOR = Regex("""\[\[[ \t]*FIGURE[ \t]*:[ \t]*(\d+)[ \t]*\]\]""", RegexOption.IGNORE_CASE)
private val CODE_FENCE = Regex("""^[ \t]*((?:>[ \t]*)*)([-+*][ \t]+|\d+[.)][ \t]+)?(`{3,}|~{3,})(.*)$""")
private val BACKTICKS = Regex("`+")
private val BLANK_LINE = Regex("""\r?\n[ \t]*\r?\n""")

/** Shared recognition for rendering, continuation numbering and model history. */
fun findFigureAnchors(text: String): List<FigureAnchor> {
    if (!text.contains("[[")) return emptyList()
    val code = markdownCodeRanges(text)
    var codeIndex = 0
    return FIGURE_ANCHOR.findAll(text).mapNotNull { match ->
        while (codeIndex < code.size && code[codeIndex].last < match.range.first) codeIndex++
        if (text.isMarkdownEscaped(match.range.first) ||
            (codeIndex < code.size && code[codeIndex].first <= match.range.last)) {
            null
        } else {
            val lineStart = text.lastIndexOf('\n', match.range.first - 1) + 1
            val lineEnd = text.indexOf('\n', match.range.last + 1).let { if (it < 0) text.length else it }
            FigureAnchor(
                range = match.range,
                numberText = match.groupValues[1],
                isStandalone = text.substring(lineStart, match.range.first).isBlank() &&
                    text.substring(match.range.last + 1, lineEnd).isBlank(),
            )
        }
    }.toList()
}

/** Rewrite only real anchors, preserving all prose, punctuation and code verbatim. */
fun replaceFigureAnchors(text: String, replacement: (FigureAnchor) -> String): String {
    val anchors = findFigureAnchors(text)
    if (anchors.isEmpty()) return text
    return buildString {
        var cursor = 0
        anchors.forEach { anchor ->
            append(text, cursor, anchor.range.first)
            append(replacement(anchor))
            cursor = anchor.range.last + 1
        }
        append(text, cursor, text.length)
    }
}

private fun markdownCodeRanges(text: String): List<IntRange> {
    val fences = mutableListOf<IntRange>()
    var fenceStart = -1
    var fenceMarker = ' '
    var fenceLength = 0
    var fenceQuoteDepth = 0
    var lineStart = 0
    while (lineStart < text.length) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it + 1 }
        val line = text.substring(lineStart, lineEnd).trimEnd('\r', '\n')
        val marker = CODE_FENCE.matchEntire(line)
        if (marker != null) {
            val quoteDepth = marker.groupValues[1].count { it == '>' }
            val listMarker = marker.groupValues[2]
            val run = marker.groupValues[3]
            val suffix = marker.groupValues[4]
            if (fenceStart < 0 && (run[0] != '`' || '`' !in suffix)) {
                fenceStart = lineStart
                fenceMarker = run[0]
                fenceLength = run.length
                fenceQuoteDepth = quoteDepth
            } else if (fenceStart >= 0 && run[0] == fenceMarker &&
                run.length >= fenceLength && suffix.isBlank() &&
                quoteDepth == fenceQuoteDepth && listMarker.isEmpty()) {
                fences += fenceStart until lineEnd
                fenceStart = -1
            }
        }
        lineStart = lineEnd
    }
    // A streaming/truncated code fence remains code through the end of the body.
    if (fenceStart >= 0) fences += fenceStart until text.length

    val ranges = mutableListOf<IntRange>()
    var cursor = 0
    (fences + listOf(text.length until text.length)).forEach { fence ->
        addInlineCodeRanges(text, cursor, fence.first, ranges)
        if (!fence.isEmpty()) ranges += fence
        cursor = fence.last + 1
    }
    return ranges
}

private fun addInlineCodeRanges(text: String, start: Int, end: Int, ranges: MutableList<IntRange>) {
    if (start >= end) return
    val runs = BACKTICKS.findAll(text, start).takeWhile { it.range.first < end }.toList()
    val nextRun = IntArray(runs.size) { -1 }
    val nextByLength = mutableMapOf<Int, Int>()
    for (index in runs.indices.reversed()) {
        val length = runs[index].value.length
        nextRun[index] = nextByLength[length] ?: -1
        nextByLength[length] = index
    }
    var index = 0
    while (index < runs.size) {
        val opening = runs[index]
        val closingIndex = nextRun[index]
        if (!text.isMarkdownEscaped(opening.range.first) && closingIndex >= 0 &&
            !BLANK_LINE.containsMatchIn(text.substring(opening.range.last + 1, runs[closingIndex].range.first))) {
            ranges += opening.range.first..runs[closingIndex].range.last
            index = closingIndex + 1
        } else {
            // Unmatched backticks are ordinary Markdown text, not a code span.
            index++
        }
    }
}

private fun String.isMarkdownEscaped(index: Int): Boolean {
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') cursor--
    return (index - cursor - 1) % 2 == 1
}
