package com.moge.app.ui.markdown

import android.widget.TextView

/** TextView-local caret and row bounds, without the extra space between lines. */
internal data class SelectionEndpoint(val x: Float, val top: Float, val bottom: Float) {
    val centerY: Float get() = (top + bottom) / 2f
}

internal fun selectionEndpoint(view: TextView, offset: Int, start: Boolean): SelectionEndpoint? {
    formulaSelectionEndpoint(view, offset, start)?.let { return it }
    val layout = view.layout ?: return null
    val caret = offset.coerceIn(0, view.text.length)
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
