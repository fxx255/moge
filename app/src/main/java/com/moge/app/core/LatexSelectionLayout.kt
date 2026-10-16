package com.moge.app.core

import android.graphics.Canvas
import android.graphics.RectF
import com.moge.app.data.parse.LatexSelectionToken
import com.moge.app.data.parse.latexSelectionTokens
import org.scilab.forge.jlatexmath.Atom
import org.scilab.forge.jlatexmath.Box
import org.scilab.forge.jlatexmath.MacroInfo
import org.scilab.forge.jlatexmath.TeXEnvironment
import org.scilab.forge.jlatexmath.TeXFormula
import org.scilab.forge.jlatexmath.TeXParser
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.awt.Graphics2D

internal data class LatexSelectionItem(val source: LatexSelectionToken, val bounds: RectF)
internal data class LatexSelectionLayout(val width: Int, val height: Int, val items: List<LatexSelectionItem>)

// A probe is built only when a formula is selected. It shares no mutable state
// with asynchronous answer rendering; each Atom retains its own collector.
private val selectionProbe = ThreadLocal<MutableMap<Int, RectF>>()

internal fun installLatexSelectionLayout() {
    MacroInfo.Commands["mogeselection"] = object : MacroInfo(2) {
        override fun invoke(parser: TeXParser, args: Array<String>): Atom {
            val content = SelectionContent(parser, args[2]).root
            val collector = selectionProbe.get() ?: return content
            return SelectionAtom(content, args[1].toIntOrNull() ?: -1, collector)
        }
    }
}

/** Measure with the same TeX engine, including real fraction/script heights and operator spacing. */
internal fun measureLatexSelection(source: String, textSize: Float): LatexSelectionLayout? = runCatching {
    val tokens = latexSelectionTokens(source)
    val collector = mutableMapOf<Int, RectF>()
    val annotated = tokens.mapIndexed { index, token ->
        "\\mogeselection{$index}{${source.substring(token.start, token.end)}}"
    }.joinToString("")
    selectionProbe.set(collector)
    val drawable = try {
        JLatexMathDrawable.builder(annotated).textSize(textSize).padding(0).build()
    } finally { selectionProbe.remove() }
    // Geometry comes from Box.draw, including the canvas transforms used for
    // scripts. A recording canvas is enough: no answer-sized bitmap is retained.
    drawable.draw(Canvas())
    LatexSelectionLayout(drawable.intrinsicWidth, drawable.intrinsicHeight,
        tokens.mapIndexedNotNull { index, token ->
            collector[index]?.takeIf { it.width() > 0f && it.height() > 0f }
                ?.let { LatexSelectionItem(token, it) }
        })
}.getOrNull()

private class SelectionContent(parser: TeXParser, source: String) : TeXFormula(parser, source, false)

private class SelectionAtom(
    private val content: Atom,
    private val index: Int,
    private val collector: MutableMap<Int, RectF>,
) : Atom() {
    init { type = content.type; type_limits = content.type_limits }
    override fun getLeftType(): Int = content.leftType
    override fun getRightType(): Int = content.rightType
    override fun createBox(env: TeXEnvironment): Box = SelectionBox(content.createBox(env), index, collector)
}

private class SelectionBox(
    private val content: Box,
    private val index: Int,
    private val collector: MutableMap<Int, RectF>,
) : Box() {
    init { width = content.width; height = content.height; depth = content.depth; shift = content.shift }
    override fun getLastFontId(): Int = content.lastFontId
    override fun draw(graphics: Graphics2D, x: Float, y: Float) {
        val bounds = RectF(x, y - height, x + width, y + depth)
        @Suppress("DEPRECATION")
        val matrix = android.graphics.Matrix().also { graphics.transform.canvas.getMatrix(it) }
        matrix.mapRect(bounds)
        collector[index] = bounds
        content.draw(graphics, x, y)
    }
}
