package com.moge.app.ui.markdown

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Path
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.animation.LinearInterpolator
import android.widget.TextView

/** Reveals already laid out ink; animation never feeds partial TeX back to the parser. */
internal class StreamingInkReveal {
    var enabled = false
    private var previous = ""
    private var visible = 0f
    private var animator: ValueAnimator? = null
    private val path = Path()

    fun update(view: TextView, catchingUp: Boolean) {
        val text = view.text.toString()
        if (text == previous) {
            if (!enabled) finish(view)
            return
        }
        val common = previous.commonPrefixWith(text).length
        visible = minOf(visible, common.toFloat())
        previous = text
        animator?.cancel()
        if (!enabled) { finish(view); return }
        val from = visible
        animator = ValueAnimator.ofFloat(from, text.length.toFloat()).apply {
            duration = ((text.length - from) * 10).toLong().coerceIn(70, if (catchingUp) 160 else 280)
            interpolator = LinearInterpolator()
            addUpdateListener { visible = it.animatedValue as Float; view.invalidate() }
            start()
        }
    }

    fun finish(view: TextView) {
        animator?.cancel()
        animator = null
        visible = view.text.length.toFloat()
        view.invalidate()
    }

    fun clip(canvas: Canvas, view: TextView) {
        val layout = view.layout ?: return
        if (!enabled || visible >= view.text.length) return
        val offset = visible.toInt().coerceIn(0, view.text.length)
        var start = offset
        var end = minOf(offset + 1, view.text.length)
        var fraction = visible - offset
        // Replacement spans represent complete formulas, often with several
        // selection tokens. Reveal across their final bounds at a steady pace.
        (view.text as? Spanned)?.let { text ->
            text.getSpans(offset, end, BaselineLatexSpan::class.java).firstOrNull()?.let { span ->
                start = text.getSpanStart(span)
                end = text.getSpanEnd(span)
                fraction = ((visible - start) / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
            }
        }
        // Do not expose a half surrogate pair (emoji and supplementary CJK).
        if (start > 0 && start < view.text.length && Character.isLowSurrogate(view.text[start])) start--
        path.reset()
        layout.getSelectionPath(0, start, path)
        if (end > start) {
            val line = layout.getLineForOffset(start)
            val x = layout.getPrimaryHorizontal(start)
            val target = layout.getPrimaryHorizontal(end)
            val right = x + (target - x) * fraction
            path.addRect(minOf(x, right), layout.getLineTop(line).toFloat(),
                maxOf(x, right), layout.getLineBottom(line).toFloat(), Path.Direction.CW)
        }
        // Selection paths cover glyphs, but list numbers/bullets and quote rules
        // are drawn in paragraph margins. Reveal them with their first character.
        val text = view.text as? Spanned
        if (text != null && visible > 0f) {
            val revealedEnd = kotlin.math.ceil(visible.toDouble()).toInt().coerceAtMost(text.length)
            val lastLine = layout.getLineForOffset((revealedEnd - 1).coerceAtLeast(0))
            for (line in 0..lastLine) {
                val lineStart = layout.getLineStart(line)
                val lineEnd = minOf(revealedEnd, layout.getLineEnd(line))
                if (lineStart >= lineEnd || text.getSpans(lineStart, lineEnd, LeadingMarginSpan::class.java).isEmpty()) continue
                val textStart = layout.getPrimaryHorizontal(lineStart)
                val left = if (layout.getParagraphDirection(line) > 0) 0f else textStart
                val right = if (layout.getParagraphDirection(line) > 0) textStart else layout.width.toFloat()
                if (left < right) path.addRect(left, layout.getLineTop(line).toFloat(), right,
                    layout.getLineBottom(line).toFloat(), Path.Direction.CW)
            }
        }
        path.offset((view.totalPaddingLeft - view.scrollX).toFloat(), (view.totalPaddingTop - view.scrollY).toFloat())
        canvas.clipPath(path)
    }
}
