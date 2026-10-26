package com.moge.app.ui.markdown

import android.graphics.RectF
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.roundToInt

// Both Markwon's asynchronous loader and the stable streaming renderer must use
// this alignment. Inline drawables have intrinsic width, so their offset is zero.
internal const val MARKDOWN_FORMULA_ALIGNMENT = JLatexMathDrawable.ALIGN_CENTER

internal data class LatexDrawableGeometry(val scale: Float, val content: RectF, val baseline: Float)

/** The same rounded scale/translation used by JLatexMathDrawable.draw. */
internal fun latexDrawableGeometry(result: JLatexMathDrawable): LatexDrawableGeometry {
    val bounds = result.bounds
    val scale = minOf(1f, bounds.width().toFloat() / result.intrinsicWidth.coerceAtLeast(1),
        bounds.height().toFloat() / result.intrinsicHeight.coerceAtLeast(1))
    val x = (bounds.width() - (result.intrinsicWidth * scale).roundToInt()) / 2
    val y = (bounds.height() - (result.intrinsicHeight * scale).roundToInt()) / 2
    val insets = result.icon().insets
    return LatexDrawableGeometry(scale, RectF(
        x + insets.left * scale, y + insets.top * scale,
        x + (result.intrinsicWidth - insets.right) * scale,
        y + (result.intrinsicHeight - insets.bottom) * scale,
    ), y + (result.intrinsicHeight - result.icon().iconDepth) * scale)
}
