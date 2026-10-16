package com.moge.app.ui.markdown

import android.text.Spanned

internal data class MarkdownSelection(val start: Int, val end: Int)

/** Snap to complete math items rather than forcing every range to include the whole formula. */
internal fun snapFormulaSelection(text: CharSequence, start: Int, end: Int): MarkdownSelection {
    var from = minOf(start, end).coerceIn(0, text.length)
    var to = maxOf(start, end).coerceIn(0, text.length)
    if (from == to || text !is Spanned) return MarkdownSelection(from, to)
    for (span in text.getSpans(from, to, BaselineLatexSpan::class.java)) {
        val spanStart = text.getSpanStart(span)
        val spanEnd = text.getSpanEnd(span)
        // getSpans may return an adjacent span at a boundary. It is not selected.
        if (spanStart < to && spanEnd > from) {
            val tokens = formulaTokenOffsets(text, span).map { it.second }
            tokens.firstOrNull { from > it.start && from < it.end }?.let { from = it.start }
            tokens.firstOrNull { to > it.start && to < it.end }?.let { to = it.end }
        }
    }
    return MarkdownSelection(from, to)
}

/** Copy original LaTeX for the selected items, preserving full formulas verbatim. */
internal fun selectedMarkdownText(text: CharSequence, start: Int, end: Int): String {
    val selection = snapFormulaSelection(text, start, end)
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
            if (to <= cursor) continue
            if (cursor < from) append(text, cursor, from)
            // Markwon flattens newlines in the placeholder. Use the drawable's
            // actual source so matrices/aligned equations also copy correctly.
            val source = span.drawable.destination.trim()
            val selected = formulaTokenOffsets(text, span).filter { (_, range) ->
                range.start < selection.end && range.end > selection.start
            }
            if (selected.isNotEmpty()) append(source.substring(selected.first().first.start, selected.last().first.end).trim())
            cursor = minOf(to, selection.end)
        }
        append(text, cursor, selection.end)
    }
}
