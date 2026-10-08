package com.moge.app.ui.markdown

import android.text.Spanned

internal data class MarkdownSelection(val start: Int, val end: Int)

/** A drawable formula is one visible unit, even though its placeholder contains many characters. */
internal fun completeFormulaSelection(text: CharSequence, start: Int, end: Int): MarkdownSelection {
    var from = minOf(start, end).coerceIn(0, text.length)
    var to = maxOf(start, end).coerceIn(0, text.length)
    if (from == to || text !is Spanned) return MarkdownSelection(from, to)
    for (span in text.getSpans(from, to, BaselineLatexSpan::class.java)) {
        val spanStart = text.getSpanStart(span)
        val spanEnd = text.getSpanEnd(span)
        // getSpans may return an adjacent span at a boundary. It is not selected.
        if (spanStart < to && spanEnd > from) {
            from = minOf(from, spanStart)
            to = maxOf(to, spanEnd)
        }
    }
    return MarkdownSelection(from, to)
}

/** Copy displayed text, replacing selected formula placeholders with their complete LaTeX. */
internal fun selectedMarkdownText(text: CharSequence, start: Int, end: Int): String {
    val selection = completeFormulaSelection(text, start, end)
    if (text !is Spanned || selection.start == selection.end) {
        return text.subSequence(selection.start, selection.end).toString()
    }
    val formulas = text.getSpans(selection.start, selection.end, BaselineLatexSpan::class.java)
        .filter { text.getSpanStart(it) < selection.end && text.getSpanEnd(it) > selection.start }
        .sortedBy { text.getSpanStart(it) }
    return buildString {
        var cursor = selection.start
        for (span in formulas) {
            val from = text.getSpanStart(span)
            val to = text.getSpanEnd(span)
            if (from < cursor) continue
            append(text, cursor, from)
            // Markwon flattens newlines in the placeholder. Use the drawable's
            // actual source so matrices/aligned equations also copy correctly.
            append(span.drawable.destination.trim())
            cursor = to
        }
        append(text, cursor, selection.end)
    }
}
