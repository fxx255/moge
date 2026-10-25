package com.moge.app.ui.markdown

import android.graphics.Path
import android.widget.TextView

/** Paint selected text rows, leaving paragraph decorations and empty separator rows untouched. */
internal fun appendTextSelectionPath(view: TextView, start: Int, end: Int, path: Path) {
    val layout = view.layout ?: return
    if (start >= end) return
    val native = Path()
    val clip = Path()
    val first = layout.getLineForOffset(start)
    val last = layout.getLineForOffset(end)
    for (line in first..last) {
        var from = maxOf(start, layout.getLineStart(line))
        var to = minOf(end, layout.getLineVisibleEnd(line))
        while (from < to && view.text[from].isWhitespace()) from++
        while (to > from && view.text[to - 1].isWhitespace()) to--
        if (from >= to) continue
        native.reset()
        layout.getSelectionPath(from, to, native)
        clip.reset()
        clip.addRect(layout.getParagraphLeft(line).toFloat(), layout.getLineTop(line).toFloat(),
            layout.getParagraphRight(line).toFloat(), layout.getLineBottom(line).toFloat(), Path.Direction.CW)
        native.op(clip, Path.Op.INTERSECT)
        native.offset(view.totalPaddingLeft - view.scrollX.toFloat(), view.totalPaddingTop - view.scrollY.toFloat())
        path.addPath(native)
    }
}
