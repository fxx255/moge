package com.moge.app.ui.markdown

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.utils.SpanUtils
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.ceil
import kotlin.math.floor

internal fun baselineAlignedLatex(markdown: Spanned, theme: MarkwonTheme): Spanned {
    val spans = markdown.getSpans(0, markdown.length, JLatexAsyncDrawableSpan::class.java)
    if (spans.isEmpty()) return markdown
    return SpannableStringBuilder(markdown).apply {
        for (span in spans) {
            val start = getSpanStart(span)
            val end = getSpanEnd(span)
            val flags = getSpanFlags(span)
            removeSpan(span)
            setSpan(BaselineLatexSpan(theme, span), start, end, flags)
        }
    }
}

internal class BaselineLatexSpan(
    theme: MarkwonTheme,
    private val original: JLatexAsyncDrawableSpan,
) : AsyncDrawableSpan(theme, original.drawable, ALIGN_BASELINE, false) {
    internal fun baselineOffset(paint: Paint): Float {
        val height = drawable.bounds.height()
        val result = drawable.result as? JLatexMathDrawable
            ?: return height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val scale = minOf(
            1f,
            drawable.bounds.width().toFloat() / result.intrinsicWidth.coerceAtLeast(1),
            height.toFloat() / result.intrinsicHeight.coerceAtLeast(1),
        )
        val scaledHeight = (result.intrinsicHeight * scale + 0.5f).toInt()
        return (height - scaledHeight) / 2 +
            (result.intrinsicHeight - result.icon().iconDepth) * scale
    }

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?,
    ): Int {
        if (!drawable.hasResult()) return original.getSize(paint, text, start, end, fm)
        val baseline = baselineOffset(paint)
        val font = paint.fontMetricsInt
        fm?.apply {
            ascent = minOf(font.ascent, floor(-baseline).toInt())
            descent = maxOf(font.descent, ceil(drawable.bounds.height() - baseline).toInt())
            top = minOf(font.top, ascent)
            bottom = maxOf(font.bottom, descent)
        }
        return drawable.bounds.width()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint,
    ) {
        drawable.initWithKnownDimensions(SpanUtils.width(canvas, text), paint.textSize)
        if (!drawable.hasResult()) {
            original.draw(canvas, text, start, end, x, top, y, bottom, paint)
            return
        }
        val saved = canvas.save()
        try {
            canvas.translate(x, y - baselineOffset(paint))
            drawable.draw(canvas)
        } finally {
            canvas.restoreToCount(saved)
        }
    }
}
