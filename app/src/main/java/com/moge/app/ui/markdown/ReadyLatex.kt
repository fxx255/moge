package com.moge.app.ui.markdown

import android.text.Spanned
import android.text.SpannableStringBuilder
import android.widget.TextView
import com.moge.app.R
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import ru.noties.jlatexmath.JLatexMathDrawable

/** Appending another paragraph must not rebuild the preceding formula drawables. */
internal fun reuseReadyLatex(text: Spanned, previous: Spanned): Spanned {
    val old = previous.getSpans(0, previous.length, JLatexAsyncDrawableSpan::class.java)
        .associateBy { previous.getSpanStart(it) }
    if (old.isEmpty()) return text
    return SpannableStringBuilder(text).apply {
        getSpans(0, length, JLatexAsyncDrawableSpan::class.java).forEach { span ->
            val start = getSpanStart(span)
            val end = getSpanEnd(span)
            val ready = old[start]?.takeIf {
                it.drawable.hasResult() && it.drawable.destination == span.drawable.destination &&
                    previous.getSpanEnd(it) == end
            } ?: return@forEach
            val flags = getSpanFlags(span)
            removeSpan(span)
            setSpan(ready, start, end, flags)
        }
    }
}

/** Give each completed formula its real dimensions before the first visible layout. */
internal fun readyLatex(view: TextView, text: Spanned, widthPx: Int, color: Int, source: String) {
    for (span in text.getSpans(0, text.length, JLatexAsyncDrawableSpan::class.java)) {
        val drawable = span.drawable
        if (drawable.hasResult()) continue
        val result = runCatching {
            JLatexMathDrawable.builder(drawable.destination).textSize(view.textSize).color(color)
                .align(MARKDOWN_FORMULA_ALIGNMENT).build()
        }.getOrElse { error ->
            appendRenderErrorLog(view.context, drawable.destination, error, originalMarkdown = source)
            LatexFallbackDrawable(color, view.textSize * 14f / MARKDOWN_TEXT_SIZE_SP, drawable.destination,
                error.javaClass.simpleName, formulaMaxWidthPx(view, widthPx),
                view.context.getString(R.string.ui_could_not_render_formula_1_s_07588, error.javaClass.simpleName))
        }
        result.setBounds(0, 0, result.intrinsicWidth.coerceAtLeast(1), result.intrinsicHeight.coerceAtLeast(1))
        drawable.initWithKnownDimensions(widthPx - view.totalPaddingLeft - view.totalPaddingRight, view.textSize)
        drawable.setResult(result)
    }
}
