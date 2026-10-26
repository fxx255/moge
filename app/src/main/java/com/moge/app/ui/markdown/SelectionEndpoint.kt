package com.moge.app.ui.markdown

import android.graphics.Path
import android.graphics.RectF
import android.text.Spanned
import android.widget.TextView

/** TextView-local caret and row bounds, without the extra space between lines. */
internal data class SelectionEndpoint(val x: Float, val top: Float, val bottom: Float) {
    val centerY: Float get() = (top + bottom) / 2f
}

internal fun selectionEndpoint(view: TextView, offset: Int, start: Boolean): SelectionEndpoint? {
    val layout = view.layout ?: return null
    var caret = offset.coerceIn(0, view.text.length)
    formulaSelectionEndpoint(view, caret, start)?.let { return it }
    // Separator newlines are copied between paragraphs but have no painted row.
    // Anchor to the nearest selected content instead of an empty page-wide strip.
    if (view.hasSelection()) {
        val from = minOf(view.selectionStart, view.selectionEnd)
        val to = maxOf(view.selectionStart, view.selectionEnd)
        if (start) while (caret < to && view.text[caret].isWhitespace()) caret++
        else while (caret > from && view.text[caret - 1].isWhitespace()) caret--
    }
    formulaSelectionEndpoint(view, caret, start)?.let { return it }
    var line = layout.getLineForOffset(caret)
    // An exclusive end at the next row's first character still belongs to the
    // selected row. Keep both its handle and its magnified boundary on that row.
    val previousRow = !start && line > 0 && caret == layout.getLineStart(line)
    if (previousRow) line--
    val x = if (previousRow) {
        // An otherwise blank selected row paints to the paragraph edge.
        // getLineRight() is zero on an empty row and would stack both handles
        // on its left edge despite the full-width highlight.
        val blankRow = view.text.getOrNull(caret - 1) == '\n' &&
            view.text.subSequence(layout.getLineStart(line), caret).all { it.isWhitespace() }
        if (blankRow) {
            if (layout.getParagraphDirection(line) > 0) layout.width.toFloat() else 0f
        } else {
            if (layout.getParagraphDirection(line) > 0) layout.getLineRight(line) else layout.getLineLeft(line)
        }
    } else layout.getPrimaryHorizontal(caret)
    val top = layout.getLineTop(line).toFloat()
    val fullHeight = layout.getLineBottom(line) - top
    // StaticLayout adds spacing to every row except the last. The remaining
    // height includes tall formula/replacement spans, unlike textSize alone.
    val rowHeight = if (line < layout.lineCount - 1) {
        (fullHeight - view.lineSpacingExtra) / view.lineSpacingMultiplier
    } else fullHeight
    val originY = view.totalPaddingTop - view.scrollY
    return SelectionEndpoint(view.totalPaddingLeft - view.scrollX + x,
        originY + top, originY + top + rowHeight.coerceAtLeast(1f))
}

/** Mixed math/prose rows must fit the same selection band inside and outside the lens. */
internal fun magnifierSelectionEndpoint(view: TextView, offset: Int, start: Boolean): SelectionEndpoint? {
    val caret = selectionEndpoint(view, offset, start) ?: return null
    val layout = view.layout ?: return caret
    val text = view.text as? Spanned ?: return caret
    if (!view.hasSelection()) return caret
    val line = layout.getLineForVertical((caret.centerY - view.totalPaddingTop + view.scrollY).toInt())
    val lineStart = layout.getLineStart(line)
    val lineEnd = layout.getLineEnd(line)
    if (text.getSpans(lineStart, lineEnd, BaselineLatexSpan::class.java).isEmpty()) return caret
    val from = maxOf(minOf(view.selectionStart, view.selectionEnd), lineStart)
    val to = minOf(maxOf(view.selectionStart, view.selectionEnd), lineEnd)
    if (from >= to) return caret
    val path = Path()
    markdownSelectionPath(view, from, to, path)
    if (path.isEmpty) return caret
    val band = RectF().also { path.computeBounds(it, true) }
    // Keep the horizontal caret unchanged. A single math letter can have a
    // much shorter TeX box than its neighboring CJK characters and line spacing.
    return caret.copy(top = minOf(caret.top, band.top), bottom = maxOf(caret.bottom, band.bottom))
}
