package com.moge.app.core

import android.graphics.Canvas
import android.graphics.RectF
import com.moge.app.data.parse.LatexSelectionToken
import com.moge.app.data.parse.LatexSelectionNode
import com.moge.app.data.parse.latexSelectionStructure
import com.moge.app.data.parse.FormulaGroupKind
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
    var nextId = 0
    fun probe(node: LatexSelectionNode): SelectionProbeNode =
        SelectionProbeNode(node, nextId++, node.children.map { probe(it) })
    val nodes = latexSelectionStructure(source).map { probe(it) }
    val collector = mutableMapOf<Int, RectF>()
    fun annotated(node: SelectionProbeNode): String {
        val group = node.source.group
        val body = if (group == null) source.substring(node.source.range.start, node.source.range.end)
            else source.substring(node.source.range.start, group.bodyStart) +
                node.children.joinToString("") { annotated(it) } + source.substring(group.bodyEnd, node.source.range.end)
        return "\\mogeselection{${node.id}}{$body}"
    }
    selectionProbe.set(collector)
    val drawable = try {
        JLatexMathDrawable.builder(nodes.joinToString("") { annotated(it) }).textSize(textSize).padding(0).build()
    } finally { selectionProbe.remove() }
    // Geometry comes from Box.draw, including the canvas transforms used for
    // scripts. A recording canvas is enough: no answer-sized bitmap is retained.
    drawable.draw(Canvas())
    fun items(node: SelectionProbeNode): List<LatexSelectionItem> {
        val bounds = collector[node.id] ?: return emptyList()
        val group = node.source.group ?: return listOf(LatexSelectionItem(node.source.range, bounds))
        // A frame is decoration, not a selectable empty opener/closer. Its real
        // box still sizes the probe; only the visible math supplies grab targets.
        if (group.kind == FormulaGroupKind.Frame) return node.children.flatMap { items(it) }
        val inner = node.children.mapNotNull { collector[it.id] }
        if (inner.isEmpty()) return emptyList()
        // The engine sizes the brackets around the already annotated children.
        // Keep that real layout intact, then isolate the two delimiter gutters.
        return buildList {
            add(LatexSelectionItem(LatexSelectionToken(node.source.range.start, group.bodyStart),
                RectF(bounds.left, bounds.top, inner.minOf { it.left }, bounds.bottom)))
            node.children.forEach { addAll(items(it)) }
            add(LatexSelectionItem(LatexSelectionToken(group.bodyEnd, node.source.range.end),
                RectF(inner.maxOf { it.right }, bounds.top, bounds.right, bounds.bottom)))
        }
    }
    LatexSelectionLayout(drawable.intrinsicWidth, drawable.intrinsicHeight, nodes.flatMap { items(it) }
        .filter { it.bounds.width() > 0f && it.bounds.height() > 0f })
}.getOrNull()

private data class SelectionProbeNode(val source: LatexSelectionNode, val id: Int, val children: List<SelectionProbeNode>)

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
