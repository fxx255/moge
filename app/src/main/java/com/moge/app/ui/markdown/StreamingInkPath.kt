package com.moge.app.ui.markdown

import android.graphics.Path
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import kotlin.math.ceil

/** Same reveal geometry regardless of answer length; only the two boundary lines need shaping. */
internal fun streamingInkPath(layout: Layout, text: CharSequence, visible: Float, path: Path, segment: Path) {
    val offset = visible.toInt().coerceIn(0, text.length)
    var start = offset
    var end = minOf(offset + 1, text.length)
    var fraction = visible - offset
    // A formula is one drawable, even when it contains several selection tokens.
    (text as? Spanned)?.getSpans(offset, end, BaselineLatexSpan::class.java)?.firstOrNull()?.let { span ->
        start = text.getSpanStart(span)
        end = text.getSpanEnd(span)
        fraction = ((visible - start) / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
    }
    if (start > 0 && start < text.length && Character.isLowSurrogate(text[start])) start--
    val line = layout.getLineForOffset(start)
    if (line < 2) {
        layout.getSelectionPath(0, start, path)
    } else {
        // Layout fills every intermediate selection line across its whole width. Merge
        // those rectangles, retaining native bidi/alignment/line-spacing at both edges.
        layout.getSelectionPath(0, layout.getLineStart(1), path)
        path.addRect(0f, layout.getLineTop(1).toFloat(), layout.width.toFloat(),
            layout.getLineTop(line).toFloat(), Path.Direction.CW)
        layout.getSelectionPath(layout.getLineStart(line - 1), start, segment)
        path.addPath(segment)
    }
    if (end > start) {
        val x = layout.getPrimaryHorizontal(start)
        val target = layout.getPrimaryHorizontal(end)
        val right = x + (target - x) * fraction
        path.addRect(minOf(x, right), layout.getLineTop(line).toFloat(),
            maxOf(x, right), layout.getLineBottom(line).toFloat(), Path.Direction.CW)
    }
    if (text !is Spanned || visible <= 0f) return
    val revealedEnd = ceil(visible.toDouble()).toInt().coerceAtMost(text.length)
    val lastLine = layout.getLineForOffset((revealedEnd - 1).coerceAtLeast(0))
    // Intermediate lines already include their entire margin. Only boundary lines
    // can need extra space for list markers or quote rules, including wrapped items.
    addRevealedMargin(layout, text, 0, revealedEnd, path)
    if (lastLine > 0) addRevealedMargin(layout, text, lastLine, revealedEnd, path)
}

private fun addRevealedMargin(layout: Layout, text: Spanned, line: Int, revealedEnd: Int, path: Path) {
    val start = layout.getLineStart(line)
    val end = minOf(revealedEnd, layout.getLineEnd(line))
    if (start >= end || text.getSpans(start, end, LeadingMarginSpan::class.java).isEmpty()) return
    val x = layout.getPrimaryHorizontal(start)
    val ltr = layout.getParagraphDirection(line) > 0
    val left = if (ltr) 0f else x
    val right = if (ltr) x else layout.width.toFloat()
    if (left < right) path.addRect(left, layout.getLineTop(line).toFloat(), right,
        layout.getLineBottom(line).toFloat(), Path.Direction.CW)
}
