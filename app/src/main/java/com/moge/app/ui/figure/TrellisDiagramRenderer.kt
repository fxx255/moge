package com.moge.app.ui.figure

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import com.moge.app.domain.coding.ConvolutionalTrellis
import com.moge.app.domain.coding.TrellisLayout
import com.moge.app.domain.coding.TrellisSpec
import com.moge.app.domain.diagram.DiagramSpec
import kotlin.math.sqrt

/** Output trellis, Hamming branch distances and Viterbi survivors from one coding specification. */
internal object TrellisDiagramRenderer {
    fun render(spec: DiagramSpec, widthPx: Int, heightPx: Int, dark: Boolean): Bitmap {
        val code = requireNotNull(spec.coding) { "缺少卷积码参数" }
        val options = spec.trellis ?: TrellisSpec()
        val result = ConvolutionalTrellis.build(code, options)
        val background = if (dark) DiagramRenderer.DARK_BACKGROUND else Color.WHITE
        val ink = if (dark) 0xFFDDE1E3.toInt() else 0xFF253341.toInt()
        val accent = if (dark) 0xFF82CFF0.toInt() else 0xFF087BA8.toInt()
        val plan = TrellisLayout.layout(code, options, result) { text, circled ->
            val textWidth = DiagramLabelText(text, if (circled) 20f else 22f, ink).width
            if (circled) maxOf(30f, textWidth + 12f).let { TrellisLayout.Size(it, it) }
            else TrellisLayout.Size(textWidth + 2f, 22f)
        }
        val title = spec.title.ifBlank { when (options.mode) {
            "distances" -> "卷积码距离图"
            "survivors" -> "卷积码幸存路径图"
            else -> "卷积码网格图"
        } }
        val legend = "实线：输入 0    虚线：输入 1" +
            if (result.highlightedPath.isNotEmpty()) "    蓝线：" + if (options.inputBits.isNotEmpty()) "指定输入路径" else "最小距离路径" else ""
        val footer = if (result.decodedBits != null) "译码信息位：${result.decodedBits}    总汉明距离：${result.bestDistance}"
            else "码率 1/${code.generators.size}    K=${code.memory + 1}    " + if (options.terminated) "末尾补 ${code.memory} 个 0" else "不限制终态"
        val receivedLabel = options.receivedBits.takeIf { it.isNotEmpty() }?.let { bits ->
            "接收：${bits.chunked(code.generators.size).joinToString(" ")}" +
                if (options.mode == "survivors") "    同距时按状态编号取一路" else ""
        }
        // Short trellises still need enough width for the legend and decoding details.
        val width = maxOf(plan.width, DiagramLabelText(title, 28f, ink).width + 64,
            DiagramLabelText(legend, 21f, ink).width + 64,
            DiagramLabelText(footer, 22f, ink).width + 64,
            receivedLabel?.let { DiagramLabelText(it, 21f, ink).width + 64 } ?: 0f)
        val scale = minOf(2.5f, widthPx / width, heightPx / plan.height, sqrt(8_000_000f / (width * plan.height)))
        require(scale > 0)
        val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1),
            (plan.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(background); scale(scale, scale) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeWidth = 2f; style = Paint.Style.STROKE }
        val dashed = Paint(stroke).apply { pathEffect = DashPathEffect(floatArrayOf(11f, 8f), 0f) }
        val highlight = Paint(stroke).apply { color = accent; strokeWidth = 5f }
        val dashedHighlight = Paint(highlight).apply { pathEffect = dashed.pathEffect }
        val highlightOutline = Paint(stroke).apply { color = highlight.color }
        val highlightedBranches = result.highlightedPath.toSet()
        val highlightedNodes = result.highlightedPath.flatMap { branch ->
            listOf(branch.time to branch.transition.from, branch.time + 1 to branch.transition.to)
        }.toSet()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background; style = Paint.Style.FILL }
        fun label(text: String, x: Float, y: Float, size: Float = 23f, centered: Boolean = false, color: Int = ink) =
            DiagramLabelText(text, size, color).draw(canvas, x, y, centered)
        fun circled(text: String, caption: TrellisLayout.Label, outline: Paint = stroke) {
            canvas.drawCircle(caption.center.x, caption.center.y, caption.width / 2 - 1, outline)
            label(text, caption.center.x, caption.center.y, 20f, true, color = outline.color)
        }
        label(title, 32f, 32f, 28f)
        label(legend, 32f, 78f, 21f)
        val axisX = plan.x0 - 26
        canvas.drawLine(axisX, 120f, axisX, plan.bottom, stroke)
        canvas.drawLine(axisX, plan.bottom, plan.width - 24, plan.bottom, stroke)
        // Minimal axis arrowheads, with time increasing left to right.
        canvas.drawLine(axisX, 120f, axisX - 5, 132f, stroke)
        canvas.drawLine(axisX, 120f, axisX + 5, 132f, stroke)
        canvas.drawLine(plan.width - 24, plan.bottom, plan.width - 36, plan.bottom - 5, stroke)
        canvas.drawLine(plan.width - 24, plan.bottom, plan.width - 36, plan.bottom + 5, stroke)
        for (state in 0 until (1 shl code.memory)) {
            val row = ConvolutionalTrellis.row(state, code.memory)
            val y = plan.stateYs[row]
            val name = ('a'.code + row).toChar().toString() + "=" + state.toString(2).padStart(code.memory, '0')
            val text = DiagramLabelText(name, 23f, ink)
            text.draw(canvas, axisX - 12 - text.width, y)
        }
        plan.edges.forEach { edge ->
            canvas.drawLine(edge.from.x, edge.from.y, edge.to.x, edge.to.y,
                if (edge.branch.transition.input == 0) stroke else dashed)
        }
        plan.edges.filter { it.branch in highlightedBranches }.forEach { edge ->
            canvas.drawLine(edge.from.x, edge.from.y, edge.to.x, edge.to.y,
                if (edge.branch.transition.input == 0) highlight else dashedHighlight)
        }
        plan.edges.forEach { edge -> edge.label?.let { caption ->
            val text = if (options.mode == "distances") edge.branch.distance.toString()
                else edge.branch.transition.output.joinToString("")
            val outline = if (edge.branch in highlightedBranches) highlightOutline else stroke
            if (options.mode == "distances") circled(text, caption, outline)
            else label(text, caption.center.x, caption.center.y, 22f, true, color = outline.color)
        } }
        val shownNodes = if (options.mode == "survivors") plan.nodeLabels.keys else plan.nodes.keys
        plan.nodes.filterKeys { it in shownNodes }.forEach { (key, point) ->
            canvas.drawCircle(point.x, point.y, 6.5f, fill)
            canvas.drawCircle(point.x, point.y, 6.5f, stroke)
            plan.nodeLabels[key]?.let { caption ->
                circled(result.metrics[key.first].getValue(key.second).toString(), caption,
                    if (key in highlightedNodes) highlightOutline else stroke)
            }
        }
        for (time in 0..options.steps) {
            val x = plan.x0 + time * plan.pitch
            canvas.drawLine(x, plan.bottom - 4, x, plan.bottom + 4, stroke)
            label(time.toString(), x, plan.bottom + 23, 22f, true)
        }
        label("l", plan.width - 30, plan.bottom + 23, 24f)
        label(footer, 32f, plan.bottom + 65, 22f)
        receivedLabel?.let { label(it, 32f, plan.bottom + 103, 21f) }
        return bitmap
    }
}
