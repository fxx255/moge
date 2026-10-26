package com.moge.app.ui.markdown

import android.graphics.Path
import android.graphics.RectF
import android.text.Spanned
import android.widget.TextView
import com.moge.app.data.parse.LatexSelectionToken
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.roundToInt

internal data class FormulaSelectionPart(val start: Int, val end: Int, val bounds: RectF)

/** Markwon flattens formula newlines in its placeholder; offsets are only selection slots. */
internal fun formulaTokenOffsets(text: Spanned, span: BaselineLatexSpan): List<Pair<LatexSelectionToken, MarkdownSelection>> {
    val source = span.drawable.destination.trim()
    val start = text.getSpanStart(span)
    val length = text.getSpanEnd(span) - start
    if (source.isEmpty() || length <= 0) return emptyList()
    fun offset(index: Int) = start + (index.toFloat() * length / source.length).roundToInt()
    return span.selectionTokens.map { it to MarkdownSelection(offset(it.start), offset(it.end)) }
}

/** Actual TeX boxes in TextView-local coordinates, excluding the centered drawable's empty margins. */
internal fun formulaSelectionParts(view: TextView, span: BaselineLatexSpan): List<FormulaSelectionPart> {
    val text = view.text as? Spanned ?: return emptyList()
    val layout = view.layout ?: return emptyList()
    val result = span.drawable.result as? JLatexMathDrawable ?: return emptyList()
    val measured = span.selectionLayout(view.textSize) ?: return emptyList()
    if (measured.width <= 0 || measured.height <= 0) return emptyList()
    val start = text.getSpanStart(span)
    val line = layout.getLineForOffset(start)
    val bounds = span.drawable.bounds
    val scale = minOf(1f, bounds.width().toFloat() / result.intrinsicWidth.coerceAtLeast(1),
        bounds.height().toFloat() / result.intrinsicHeight.coerceAtLeast(1))
    val insets = result.icon().insets
    val contentWidth = result.intrinsicWidth - insets.left - insets.right
    val contentHeight = result.intrinsicHeight - insets.top - insets.bottom
    val x = view.totalPaddingLeft - view.scrollX + layout.getPrimaryHorizontal(start) +
        (bounds.width() - (result.intrinsicWidth * scale).roundToInt()) / 2 + insets.left * scale
    val y = view.totalPaddingTop - view.scrollY + layout.getLineBaseline(line) - span.baselineOffset(view.paint) +
        (bounds.height() - (result.intrinsicHeight * scale).roundToInt()) / 2 + insets.top * scale
    val offsets = formulaTokenOffsets(text, span).toMap()
    val parts = measured.items.mapNotNull { item ->
        val range = offsets[item.source] ?: return@mapNotNull null
        val box = item.bounds
        FormulaSelectionPart(range.start, range.end, RectF(
            x + box.left * contentWidth * scale / measured.width,
            y + box.top * contentHeight * scale / measured.height,
            x + box.right * contentWidth * scale / measured.width,
            y + box.bottom * contentHeight * scale / measured.height,
        ).apply {
            // Android's TeX glyphs extend slightly beyond the engine's nominal
            // ascent/depth (notably degree marks). Keep that ink in the highlight
            // and lens without restoring the display block's large empty margins.
            inset(0f, -view.textSize * .125f * scale)
        })
    }
    // Android glyph side bearings (notably italic letters and trailing scripts)
    // extend past TeX's advance boxes. Cover that ink at the two outer edges
    // without adding selection targets in the spaces between individual items.
    val gutter = view.textSize * .25f * scale
    parts.minByOrNull { it.bounds.left }?.bounds?.let { it.left -= gutter }
    parts.maxByOrNull { it.bounds.right }?.bounds?.let { it.right += gutter }
    return parts
}

internal fun formulaSelectionEndpoint(view: TextView, offset: Int, start: Boolean, wholeRow: Boolean = true): SelectionEndpoint? {
    val text = view.text as? Spanned ?: return null
    for (span in text.getSpans(offset, offset, BaselineLatexSpan::class.java)) {
        val parts = formulaSelectionParts(view, span)
        val part = if (start) parts.firstOrNull { offset < it.end }
            else parts.lastOrNull { offset > it.start }
        if (part == null) continue
        return SelectionEndpoint(if (start) part.bounds.left else part.bounds.right,
            if (wholeRow) parts.minOf { it.bounds.top } else part.bounds.top,
            if (wholeRow) parts.maxOf { it.bounds.bottom } else part.bounds.bottom)
    }
    return null
}

/** ReplacementSpan hit testing is atomic in Android; use the measured mathematical items instead. */
internal fun formulaOffsetForPosition(view: TextView, x: Float, y: Float): Int? {
    val part = formulaPartForPosition(view, x, y) ?: return null
    return if (x < part.bounds.centerX()) part.start else part.end
}

internal fun formulaPartForPosition(view: TextView, x: Float, y: Float): FormulaSelectionPart? {
    val text = view.text as? Spanned ?: return null
    val layout = view.layout ?: return null
    val line = layout.getLineForVertical((y - view.totalPaddingTop + view.scrollY).toInt())
    val spans = text.getSpans(layout.getLineStart(line), layout.getLineEnd(line), BaselineLatexSpan::class.java)
    // Limit the override to the formula itself; neighboring prose keeps native bidi hit testing.
    val parts = spans.map { formulaSelectionParts(view, it) }.firstOrNull { items ->
        items.isNotEmpty() && x >= items.minOf { it.bounds.left } - view.textSize / 4 &&
            x <= items.maxOf { it.bounds.right } + view.textSize / 4
    } ?: return null
    return parts.minBy { kotlin.math.abs(x - it.bounds.centerX()) }
}

/** Shared by the answer and lens, so highlights and handles always use the same formula boxes. */
internal fun markdownSelectionPath(view: TextView, start: Int, end: Int, path: Path) {
    path.reset()
    val layout = view.layout ?: return
    val text = view.text as? Spanned
    val from = minOf(start, end).coerceIn(0, view.text.length)
    val to = maxOf(start, end).coerceIn(0, view.text.length)
    val formulas = text?.getSpans(from, to, BaselineLatexSpan::class.java).orEmpty()
        .filter { text!!.getSpanStart(it) < to && text.getSpanEnd(it) > from }.sortedBy { text!!.getSpanStart(it) }
    val rows = mutableMapOf<Int, SelectionHighlightRow>()
    fun appendText(a: Int, b: Int) {
        if (a >= b) return
        for (line in layout.getLineForOffset(a)..layout.getLineForOffset(b)) {
            val segment = Path()
            appendTextSelectionPath(view, maxOf(a, layout.getLineStart(line)),
                minOf(b, layout.getLineEnd(line)), segment)
            if (!segment.isEmpty) rows.getOrPut(line) { SelectionHighlightRow() }.text.addPath(segment)
        }
    }
    var cursor = from
    for (span in formulas) {
        val a = text!!.getSpanStart(span)
        val b = text.getSpanEnd(span)
        appendText(cursor, a)
        val row = formulaSelectionParts(view, span)
        val parts = row.filter { it.start < to && it.end > from }
        if (row.isEmpty()) appendText(maxOf(from, a), minOf(to, b))
        else if (parts.isNotEmpty()) {
            val bounds = RectF(parts.minOf { it.bounds.left }, row.minOf { it.bounds.top },
                parts.maxOf { it.bounds.right }, row.maxOf { it.bounds.bottom })
            rows.getOrPut(layout.getLineForOffset(a)) { SelectionHighlightRow() }.formula.union(bounds)
        }
        cursor = b
    }
    appendText(cursor, to)
    rows.forEach { (line, row) ->
        if (row.formula.isEmpty) path.addPath(row.text)
        else {
            // Inline math and its neighboring prose are one selected reading row.
            // Fill their internal spaces at a common height, while formula-only
            // rows retain their tight ink bounds and unpainted display margins.
            val bounds = RectF(row.formula)
            if (!row.text.isEmpty) bounds.union(RectF().also { row.text.computeBounds(it, true) })
            bounds.left = maxOf(bounds.left, view.totalPaddingLeft - view.scrollX + layout.getParagraphLeft(line).toFloat())
            bounds.right = minOf(bounds.right, view.totalPaddingLeft - view.scrollX + layout.getParagraphRight(line).toFloat())
            path.addRect(bounds, Path.Direction.CW)
        }
    }
}

private class SelectionHighlightRow {
    val text = Path()
    val formula = RectF()
}
