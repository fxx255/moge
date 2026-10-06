package com.moge.app.ui.figure

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.moge.app.domain.diagram.*
import ru.noties.jlatexmath.JLatexMathDrawable
import kotlin.math.atan2
import kotlin.math.sqrt

/** Textbook-style local diagrams. The stored image keeps its natural aspect ratio. */
object DiagramRenderer {
    /** 黑板卡片色，与解答纸夜间底色一致。 */
    const val DARK_BACKGROUND = 0xFF24332D.toInt()
    private data class Palette(val background: Int, val ink: Int, val muted: Int)
    private val LIGHT = Palette(Color.WHITE, 0xFF202B38.toInt(), 0xFF536274.toInt())
    private val DARK = Palette(DARK_BACKGROUND, 0xFFC9C9CE.toInt(), 0xFF8E8E93.toInt())
    private const val LABEL_CANVAS_PADDING = 28f

    fun render(spec: DiagramSpec, widthPx: Int = 4096, heightPx: Int = 4096,
               dark: Boolean = false): Bitmap =
        if (spec.profile.isCoding()) CodingDiagramRenderer.render(spec, widthPx, heightPx, dark)
        else renderLayout(DiagramLayout.layout(spec), widthPx, heightPx, dark)

    fun renderLayout(layout: DiagramLayoutResult, widthPx: Int, heightPx: Int,
                     dark: Boolean = false): Bitmap {
        val palette = if (dark) DARK else LIGHT
        val scale = minOf(3f, widthPx / layout.width, heightPx / layout.height,
            sqrt(8_000_000f / (layout.width * layout.height)))
        require(scale > 0)
        val bitmap = Bitmap.createBitmap((layout.width * scale).toInt().coerceAtLeast(1),
            (layout.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(palette.background)
        canvas.scale(scale, scale)
        drawText(canvas, DiagramText.layout(layout.title, DiagramTextRole.TITLE, 600f),
            28f, 28f, DiagramTextRole.TITLE, palette = palette)
        layout.edges.forEach { drawEdge(canvas, it, palette) }
        drawFeedbackCrossingGaps(canvas, layout.edges, palette)
        layout.nodes.forEach { drawNode(canvas, it, layout.edges, palette) }
        layout.annotations.forEach { annotation ->
            val text = DiagramText.layout(annotation.text, DiagramTextRole.LABEL, 100f)
            drawText(canvas, text, annotation.centerX - text.width / 2f,
                annotation.centerY - text.height / 2f, DiagramTextRole.LABEL, palette = palette)
        }
        // Draw arrowheads after node fills/strokes.  Otherwise the node body
        // hides the head at a top/bottom port, making the direction look
        // missing or reversed even though the route itself is correct.
        layout.edges.forEach {
            if (shouldDrawArrowHead(it, layout.nodes)) drawArrowHead(canvas, it, palette)
        }
        // Labels are laid on clear sections of their routed polyline.  Keep a
        // small occupancy list so adjacent branch labels cannot paint over
        // one another or over a node.  The list uses logical (pre-scale) px.
        val occupiedLabels = annotationOccupancy(layout)
        layout.edges.forEach {
            drawEdgeLabel(canvas, it, layout.nodes, occupiedLabels, layout.width, layout.height, palette)
        }
        return bitmap
    }

    /** Reserve marker/sign space before choosing edge-label locations. */
    private fun annotationOccupancy(layout: DiagramLayoutResult): MutableList<RectF> {
        val occupied = mutableListOf<RectF>()
        layout.annotations.forEach { annotation ->
            val text = DiagramText.layout(annotation.text, DiagramTextRole.LABEL, 100f)
            occupied += RectF(annotation.centerX - text.width / 2f - 4f,
                annotation.centerY - text.height / 2f - 4f, annotation.centerX + text.width / 2f + 4f,
                annotation.centerY + text.height / 2f + 4f)
        }
        layout.nodes.filter { it.node.renderShape() == DiagramNodeShape.JUNCTION }.forEach { box ->
            markerLabelRect(box)?.let { occupied += it }
        }
        layout.nodes.filter { it.node.renderShape() == DiagramNodeShape.SUM }.forEach { box ->
            occupied += RectF(box.centerX - 46f, box.y - 30f, box.centerX - 16f, box.y + 2f)
            occupied += RectF(box.centerX - 46f, box.y + box.height + 2f,
                box.centerX - 16f, box.y + box.height + 38f)
        }
        return occupied
    }

    private enum class MarkerSide { ABOVE, BELOW, RIGHT }

    /** Match the textbook placement: A/F beside vertical wires, D below its rail. */
    private fun markerSide(box: NodeBox): MarkerSide {
        val role = box.node.role.orEmpty().trim().lowercase()
        val id = box.node.id.trim().lowercase()
        val label = box.node.label.trim().lowercase()
        if (role.contains("probe") && !role.startsWith("test_")) return MarkerSide.ABOVE
        return when {
            role == "test_a" || id == "test_a" || id == "a" || label == "a" -> MarkerSide.RIGHT
            role == "test_d" || id == "test_d" || id == "d" || label == "d" -> MarkerSide.BELOW
            role == "test_f" || id == "test_f" || id == "f" || label == "f" -> MarkerSide.RIGHT
            else -> MarkerSide.ABOVE
        }
    }

    private fun markerLabelOrigin(box: NodeBox, marker: DiagramTextBlock): Pair<Float, Float> =
        when (markerSide(box)) {
            MarkerSide.ABOVE -> box.centerX - marker.width / 2f to box.y - marker.height - 16f
            MarkerSide.BELOW -> box.centerX - marker.width / 2f to box.y + box.height + 10f
            MarkerSide.RIGHT -> box.x + box.width + 12f to box.centerY - marker.height / 2f
        }

    private fun markerLabelRect(box: NodeBox): RectF? {
        val marker = DiagramLayout.label(box.node)
        if (marker.lines.isEmpty()) return null
        val (x, top) = markerLabelOrigin(box, marker)
        return RectF(x - 4f, top - 3f, x + marker.width + 4f, top + marker.height + 3f)
    }

    private fun drawNode(canvas: Canvas, box: NodeBox, edges: List<EdgeRoute>, palette: Palette) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.background }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.ink; style = Paint.Style.STROKE; strokeWidth = 1.9f
        }
        val rect = RectF(box.x, box.y, box.x + box.width, box.y + box.height)
        when (box.node.renderShape()) {
            DiagramNodeShape.MIXER, DiagramNodeShape.SUM -> {
                canvas.drawOval(rect, fill)
                canvas.drawOval(rect, stroke)
                if (box.node.renderShape() == DiagramNodeShape.SUM) {
                    val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.ink; style = Paint.Style.STROKE; strokeWidth = 1.6f
                    }
                    canvas.drawLine(box.centerX, box.y + 8f, box.centerX, box.y + box.height - 8f, cross)
                    canvas.drawLine(box.x + 8f, box.centerY, box.x + box.width - 8f, box.centerY, cross)
                    drawSumInputSigns(canvas, box, edges, palette)
                } else {
                    val glyph = box.node.glyph ?: "×"
                    // The circle is intentionally a little larger than the
                    // surrounding text blocks.  Scale the operator glyph with
                    // it so × remains unmistakable in compact previews.
                    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = palette.ink
                        textSize = (box.width * 0.68f).coerceIn(40f, 48f)
                        textAlign = Paint.Align.CENTER
                    }
                    canvas.drawText(glyph, box.centerX, box.centerY - (text.ascent() + text.descent()) / 2, text)
                }
            }
            DiagramNodeShape.JUNCTION -> {
                canvas.drawCircle(box.centerX, box.centerY, box.width / 2,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.ink })
                val marker = DiagramLayout.label(box.node)
                if (marker.lines.isNotEmpty()) {
                    val (x, top) = markerLabelOrigin(box, marker)
                    drawText(canvas, marker, x, top, DiagramTextRole.LABEL,
                        centered = true, palette = palette)
                }
            }
            DiagramNodeShape.SAMPLER -> {
                val left = box.x + 13f
                val right = box.x + box.width - 13f
                canvas.drawLine(box.x, box.centerY, left - 4f, box.centerY, stroke)
                canvas.drawLine(right + 4f, box.centerY, box.x + box.width, box.centerY, stroke)
                canvas.drawCircle(left, box.centerY, 4f, stroke)
                canvas.drawCircle(right, box.centerY, 4f, stroke)
                canvas.drawLine(left + 3f, box.centerY - 2f, right - 5f, box.centerY - 19f, stroke)
                val main = DiagramLayout.label(box.node)
                val sub = DiagramLayout.subLabel(box.node)
                drawText(canvas, main, box.centerX - main.width / 2f, box.y,
                    DiagramTextRole.LABEL, centered = true, palette = palette)
                drawText(canvas, sub, box.centerX - sub.width / 2f, box.centerY + 14f,
                    DiagramTextRole.SUB_LABEL, centered = true, palette = palette)
            }
            else -> {
                if (box.node.renderShape() in setOf(DiagramNodeShape.BLOCK, DiagramNodeShape.BUS)) {
                    canvas.drawRoundRect(rect, 4f, 4f, fill)
                    canvas.drawRoundRect(rect, 4f, 4f, stroke)
                }
                val main = DiagramLayout.label(box.node)
                val sub = DiagramLayout.subLabel(box.node)
                val gap = if (sub.height > 0) 4f else 0f
                val top = box.centerY - (main.height + sub.height + gap) / 2
                drawText(canvas, main, box.centerX - main.width / 2, top, DiagramTextRole.LABEL,
                    centered = true, palette = palette)
                drawText(canvas, sub, box.centerX - sub.width / 2, top + main.height + gap,
                    DiagramTextRole.SUB_LABEL, centered = true, palette = palette)
            }
        }
    }

    private fun drawSumInputSigns(canvas: Canvas, box: NodeBox, edges: List<EdgeRoute>, palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.ink; textSize = 24f; textAlign = Paint.Align.CENTER }
        edges.filter { it.edge.to == box.node.id }.forEach { route ->
            val sign = route.edge.polarity ?: when (route.endPort) {
                DiagramPort.TOP -> "+"
                DiagramPort.BOTTOM -> "−"
                else -> null
            } ?: return@forEach
            val (x, y) = when (route.endPort) {
                // Keep the signs outside the circle, beside the incoming
                // wires, as in the textbook.  They are offset laterally so
                // neither sign sits on top of the vertical wire.
                DiagramPort.TOP -> box.centerX - 30f to box.y - 8f
                DiagramPort.BOTTOM -> box.centerX - 30f to box.y + box.height + 28f
                DiagramPort.LEFT -> box.x - 20f to box.centerY - 6f
                DiagramPort.RIGHT -> box.x + box.width + 20f to box.centerY - 6f
                DiagramPort.AUTO -> return@forEach
            }
            canvas.drawText(sign, x, y, paint)
        }
    }

    private fun drawEdge(canvas: Canvas, route: EdgeRoute, palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (route.edge.dashed) palette.muted else palette.ink
            style = Paint.Style.STROKE; strokeWidth = 1.6f
            if (route.edge.dashed) pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
        val path = Path().apply {
        route.points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
        canvas.drawPath(path, paint)
    }

    /** A dashed feedback wire passing a solid control feed is not a junction. */
    private fun drawFeedbackCrossingGaps(canvas: Canvas, edges: List<EdgeRoute>, palette: Palette) {
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.background }
        val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.ink; style = Paint.Style.STROKE; strokeWidth = 1.6f
        }
        val bridge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            // The feedback edge is normally dashed. Keep the bridge itself
            // continuous so it reads as one connected wire over the crossing.
            color = palette.muted; style = Paint.Style.STROKE; strokeWidth = 1.6f
        }
        edges.filter { it.edge.dashed }.forEach { dashed ->
            dashed.points.zipWithNext().forEach { (a, b) ->
                edges.filterNot { it.edge.dashed }.forEach { route ->
                    route.points.zipWithNext().forEach { (c, d) ->
                        val dashHorizontal = a.y == b.y
                        val solidHorizontal = c.y == d.y
                        if (dashHorizontal == solidHorizontal) return@forEach
                        val x = if (dashHorizontal) c.x else a.x
                        val y = if (dashHorizontal) a.y else c.y
                        val h1 = if (dashHorizontal) a.x else c.x
                        val h2 = if (dashHorizontal) b.x else d.x
                        val v1 = if (dashHorizontal) c.y else a.y
                        val v2 = if (dashHorizontal) d.y else b.y
                        if (x <= minOf(h1, h2) + 6f || x >= maxOf(h1, h2) - 6f ||
                            y <= minOf(v1, v2) + 6f || y >= maxOf(v1, v2) - 6f) return@forEach
                        canvas.drawCircle(x, y, 5f, background)
                        if (solidHorizontal) canvas.drawLine(x - 6f, y, x + 6f, y, solid)
                        else canvas.drawLine(x, y - 6f, x, y + 6f, solid)
                        val bridgeBox = RectF(x - 5f, y - 5f, x + 5f, y + 5f)
                        // Keep crossings rectilinear too: a small square
                        // bridge reads more like a textbook wire than a curved
                        // arc, especially on the right side of a narrow phone preview.
                        canvas.drawRect(bridgeBox, bridge)
                    }
                }
            }
        }
    }

    private fun drawArrowHead(canvas: Canvas, route: EdgeRoute, palette: Palette) {
        val previous = route.points.asReversed()
            .drop(1)
            .firstOrNull { it != route.end }
            ?: route.start
        val angle = Math.toDegrees(atan2((route.end.y - previous.y).toDouble(),
            (route.end.x - previous.x).toDouble())).toFloat()
        canvas.save()
        canvas.translate(route.end.x, route.end.y)
        canvas.rotate(angle)
        canvas.drawPath(Path().apply {
            moveTo(0f, 0f); lineTo(-11f, -5f); lineTo(-11f, 5f); close()
        }, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (route.edge.dashed) palette.muted else palette.ink
        })
        canvas.restore()
    }

    private fun shouldDrawArrowHead(route: EdgeRoute, nodes: List<NodeBox>): Boolean {
        val target = nodes.firstOrNull { it.node.id == route.edge.to }?.node ?: return true
        val role = target.role.orEmpty().trim().lowercase().replace('-', '_').replace(' ', '_')
        val id = target.id.trim().lowercase()
        val label = target.label.trim().lowercase()
        // A–G (apart from the input point A) are passive probes on a wire;
        // putting an arrowhead on their dot makes the signal look as though
        // it stops there and creates the double-arrow seen around F.
        val passiveProbe = role in setOf("test_b", "test_c", "test_d", "test_e", "test_f", "test_g") ||
            id in setOf("test_b", "test_c", "test_d", "test_e", "test_f", "test_g") ||
            (target.renderShape() == DiagramNodeShape.JUNCTION && label in setOf("b", "c", "d", "e", "f", "g"))
        return !passiveProbe && target.renderShape() != DiagramNodeShape.JUNCTION
    }

    private fun normalizedLabel(value: String): String = value.trim().lowercase()
        .replace(" ", "").replace("−", "-")

    private fun segmentLength(segment: Pair<DiagramPoint, DiagramPoint>): Float =
        kotlin.math.abs(segment.first.x - segment.second.x) +
            kotlin.math.abs(segment.first.y - segment.second.y)

    private fun longestSegment(route: EdgeRoute, vertical: Boolean): Pair<DiagramPoint, DiagramPoint>? =
        route.points.zipWithNext()
            .filter { (a, b) ->
                if (vertical) kotlin.math.abs(a.y - b.y) > kotlin.math.abs(a.x - b.x)
                else kotlin.math.abs(a.x - b.x) >= kotlin.math.abs(a.y - b.y)
            }
            .maxByOrNull(::segmentLength)

    private fun drawEdgeLabel(
        canvas: Canvas,
        route: EdgeRoute,
        nodes: List<NodeBox>,
        occupied: MutableList<RectF>,
        canvasWidth: Float,
        canvasHeight: Float,
        palette: Palette,
    ) {
        val label = route.edge.label?.trim()?.takeIf { it.isNotBlank() }
            ?.takeUnless { isDuplicateMarker(it, route, nodes) }
            ?: return
        val key = normalizedLabel(label)
        val segment = when {
            // The cosine label belongs above the horizontal carrier branch;
            // the sine label belongs beside the vertical phase-shift branch.
            key.contains("cos") ->
                longestSegment(route, vertical = false) ?: longestSegment(route, vertical = true)
            key.contains("sin") ->
                longestSegment(route, vertical = true) ?: longestSegment(route, vertical = false)
            key.contains("上支路") || key.contains("下支路") ||
                key.contains("upper") || key.contains("lower") ->
                longestSegment(route, vertical = false) ?: longestSegment(route, vertical = true)
            else -> longestSegment(route, vertical = false) ?: longestSegment(route, vertical = true)
        } ?: return
        val (a, b) = segment
        val text = DiagramText.layout(label, DiagramTextRole.EDGE_LABEL, 140f)
        val horizontal = kotlin.math.abs(a.x - b.x) >= kotlin.math.abs(a.y - b.y)
        val midX = (a.x + b.x) / 2f
        val midY = (a.y + b.y) / 2f
        val candidates = when {
            // A point label on the split is printed beside the junction.
            key == "a" && nodes.any { it.node.id == route.edge.to && it.node.renderShape() == DiagramNodeShape.JUNCTION } -> {
                val target = nodes.firstOrNull { it.node.id == route.edge.to }
                if (target == null) emptyList()
                else listOf(RectF(target.x + target.width + 12f,
                    target.centerY - text.height / 2f,
                    target.x + target.width + 12f + text.width,
                    target.centerY + text.height / 2f))
            }
            // F belongs below the summing circle, beside the lower feed.
            key == "f" -> {
                val target = nodes.firstOrNull { it.node.id == route.edge.to }
                if (target == null) emptyList()
                else listOf(RectF(target.centerX + 12f,
                    target.y + target.height + 8f,
                    target.centerX + 12f + text.width,
                    target.y + target.height + 8f + text.height))
            }
            horizontal -> {
                val belowFirst = key == "d" || key.contains("下支路") || key.contains("lower")
                val above = RectF(midX - text.width / 2f, midY - text.height - 9f,
                    midX + text.width / 2f, midY - 9f)
                val below = RectF(midX - text.width / 2f, midY + 9f,
                    midX + text.width / 2f, midY + 9f + text.height)
                if (belowFirst) listOf(below, above) else listOf(above, below)
            }
            else -> {
                // Vertical annotations sit to the right of the feed by
                // default, matching the cos/sin placement in the reference.
                listOf(
                    RectF(midX + 12f, midY - text.height / 2f,
                        midX + 12f + text.width, midY + text.height / 2f),
                    RectF(midX - 12f - text.width, midY - text.height / 2f,
                        midX - 12f, midY + text.height / 2f),
                )
            }
        }
        fun clamped(rect: RectF): RectF {
            val dx = when {
                rect.left < LABEL_CANVAS_PADDING -> LABEL_CANVAS_PADDING - rect.left
                rect.right > canvasWidth - LABEL_CANVAS_PADDING ->
                    canvasWidth - LABEL_CANVAS_PADDING - rect.right
                else -> 0f
            }
            val dy = when {
                rect.top < LABEL_CANVAS_PADDING -> LABEL_CANVAS_PADDING - rect.top
                rect.bottom > canvasHeight - LABEL_CANVAS_PADDING ->
                    canvasHeight - LABEL_CANVAS_PADDING - rect.bottom
                else -> 0f
            }
            return RectF(rect.left + dx, rect.top + dy, rect.right + dx, rect.bottom + dy)
        }
        fun intersects(a: RectF, b: RectF): Boolean =
            a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
        fun occupiedByNode(rect: RectF): Boolean = nodes.any { box ->
            intersects(rect, RectF(box.x - 4f, box.y - 4f,
                box.x + box.width + 4f, box.y + box.height + 4f))
        }
        val chosen = candidates.asSequence()
            .map(::clamped)
            .firstOrNull { rect -> !occupiedByNode(rect) && occupied.none { intersects(rect, it) } }
            ?: return
        occupied += RectF(chosen)
        val x = chosen.left
        val y = chosen.top
        // The label is offset from the wire and from all occupied annotations,
        // so it can be drawn directly without masking or visually cutting the
        // underlying line.
        drawText(canvas, text, x, y, DiagramTextRole.EDGE_LABEL,
            centered = true, palette = palette)
    }

    /**
     * Test-point letters and output names are sometimes emitted twice: once
     * as a node label and once as the edge label.  Keep the semantic node
     * annotation and suppress only the duplicate short marker; signal labels
     * such as carrier formulas remain visible.
     */
    private fun isDuplicateMarker(label: String, route: EdgeRoute, nodes: List<NodeBox>): Boolean {
        val marker = label.trim().replace("−", "-")
        val isTestMarker = marker.length == 1 && marker[0] in 'A'..'G'
        val source = nodes.firstOrNull { it.node.id == route.edge.from }?.node
        val target = nodes.firstOrNull { it.node.id == route.edge.to }?.node
        fun normalized(value: String?): String = value.orEmpty()
            .trim()
            .replace(Regex("\\s+"), "")
            .replace("−", "-")
        val edgeText = normalized(marker)
        val matchesNode = listOf(source, target).any { node ->
            val text = normalized(node?.label)
            text == edgeText || (isTestMarker && text.contains(edgeText))
        }
        return matchesNode || (isTestMarker && listOf(source, target).any { it?.role?.equals("test_${marker.lowercase()}") == true })
    }

    private fun drawText(canvas: Canvas, block: DiagramTextBlock, x: Float, top: Float,
                         role: DiagramTextRole, centered: Boolean = false,
                         palette: Palette) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (role == DiagramTextRole.SUB_LABEL) palette.muted else palette.ink
            textSize = role.fontSizePx
        }
        var y = top
        for (line in block.lines) {
            var cursor = x + if (centered) (block.width - line.width) / 2 else 0f
            for (run in line.runs) {
                // Layout measures formulas without knowing the image theme.
                // Rebuild only the few dark-theme glyphs with the plot's
                // light text color; otherwise their default black disappears.
                val drawable = if (palette === DARK && run.formula != null) {
                    runCatching {
                        JLatexMathDrawable.builder(run.text.removeSurrounding("$"))
                            .textSize(role.fontSizePx).color(palette.ink).build()
                    }.getOrNull() ?: run.formula
                } else run.formula
                if (drawable != null) {
                    canvas.save()
                    canvas.translate(cursor, y + (line.height - run.height) / 2)
                    drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
                    drawable.draw(canvas)
                    canvas.restore()
                } else {
                    val baseline = y + (line.height - run.height) / 2 - paint.fontMetrics.ascent
                    canvas.drawText(run.text, cursor, baseline, paint)
                }
                cursor += run.width
            }
            y += line.height + 4f
        }
    }
}
