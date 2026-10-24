package com.moge.app.ui.markdown

import android.util.LruCache
import android.widget.TextView
import ru.noties.jlatexmath.JLatexMathDrawable

private data class FormulaWidthKey(val latex: String, val textSizePx: Float)

/** Widths contain no View/Drawable/context; revisiting a page can reuse expensive TeX measurements. */
private val formulaWidths = object : LruCache<FormulaWidthKey, Int>(256 * 1024) {
    override fun sizeOf(key: FormulaWidthKey, value: Int): Int = key.latex.length * 2 + 32
}

internal fun formulaWidthMeasurer(view: TextView): (String) -> Int = formulaWidthMeasurer(view.textSize)

internal fun formulaWidthMeasurer(textSizePx: Float): (String) -> Int = { latex ->
    val key = FormulaWidthKey(latex, textSizePx)
    formulaWidths.get(key) ?: runCatching {
        JLatexMathDrawable.builder(latex).textSize(textSizePx).build().intrinsicWidth.also {
            formulaWidths.put(key, it)
        }
    }.getOrDefault((latex.length * textSizePx * 0.62f).toInt())
}
