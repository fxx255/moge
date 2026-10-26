package com.moge.app.ui.markdown

import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.Spanned
import com.moge.app.core.LatexSelectionLayout
import com.moge.app.core.measureLatexSelection
import com.moge.app.data.parse.latexSelectionTokens
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
    internal val selectionTokens by lazy { latexSelectionTokens(drawable.destination.trim()) }
    private var selectionLayout: LatexSelectionLayout? = null
    private var selectionSize = -1f

    internal fun selectionLayout(textSize: Float): LatexSelectionLayout? {
        if (selectionSize != textSize) {
            selectionLayout = measureLatexSelection(drawable.destination.trim(), textSize)
            selectionSize = textSize
        }
        return selectionLayout
    }

    internal fun baselineOffset(paint: Paint): Float {
        val height = drawable.bounds.height()
        val result = drawable.result as? JLatexMathDrawable
            ?: return height / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        return latexDrawableGeometry(result).baseline
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
