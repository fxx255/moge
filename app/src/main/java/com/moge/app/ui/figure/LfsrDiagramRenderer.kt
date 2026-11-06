package com.moge.app.ui.figure

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.moge.app.domain.diagram.DiagramPoint
import com.moge.app.domain.diagram.LfsrLayout
import com.moge.app.domain.diagram.LfsrSpec
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Register outputs, XOR inputs and the external feedback loop occupy distinct lanes. */
internal object LfsrDiagramRenderer {
    fun render(title: String, sequences: List<LfsrSpec>, widthPx: Int, heightPx: Int, dark: Boolean): Bitmap {
        require(sequences.size in 1..4)
        val ink = if (dark) 0xFFDDE1E3.toInt() else 0xFF253341.toInt()
        val plans = sequences.map { spec ->
            LfsrLayout.layout(spec,
                widths = (0 until spec.stages).map { maxOf(108f, DiagramLabelText(spec.registerLabel(it), 25f, ink).width + 32f) },
                outputWidth = DiagramLabelText(spec.outputLabel, 24f, ink).width,
                stageOutputWidths = (0 until spec.stages).map { DiagramLabelText(spec.stageOutputLabel(it), 25f, ink).width })
        }
        val titleText = DiagramLabelText(title, 26f, ink)
        val width = maxOf(plans.zip(sequences).maxOf { (plan, spec) ->
            maxOf(plan.width,
                plan.registers.last().let { it.x + it.width / 2 + 58 } + DiagramLabelText(spec.clockLabel, 23f, ink).width + 28)
        }, titleText.width + 56f)
        val height = plans.sumOf { it.height.toDouble() }.toFloat() + (plans.size - 1) * 20f
        val scale = minOf(3f, widthPx / width, heightPx / height, sqrt(8_000_000f / (width * height)))
        require(scale > 0)
        val bitmap = Bitmap.createBitmap((width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(if (dark) DiagramRenderer.DARK_BACKGROUND else Color.WHITE); scale(scale, scale) }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 2.2f }
        val fill = Paint(stroke).apply { style = Paint.Style.FILL }
        fun line(points: List<DiagramPoint>, arrow: Boolean) {
            canvas.drawPath(Path().apply { moveTo(points.first().x, points.first().y); points.drop(1).forEach { lineTo(it.x, it.y) } }, stroke)
            if (arrow) {
                val end = points.last(); val previous = points[points.lastIndex - 1]
                val angle = atan2(end.y - previous.y, end.x - previous.x)
                canvas.drawPath(Path().apply {
                    moveTo(end.x, end.y)
                    lineTo(end.x - 10 * cos(angle - .42f), end.y - 10 * sin(angle - .42f))
                    lineTo(end.x - 10 * cos(angle + .42f), end.y - 10 * sin(angle + .42f)); close()
                }, fill)
            }
        }
        titleText.draw(canvas, 28f, 32f)
        sequences.zip(plans).forEach { (spec, plan) ->
            plan.wires.forEach { line(it.points, it.arrow) }
            plan.registers.forEach { box ->
                val top = plan.registerY - LfsrLayout.BOX_HEIGHT / 2
                canvas.drawRect(box.x, top, box.x + box.width, top + LfsrLayout.BOX_HEIGHT, stroke)
                DiagramLabelText(spec.registerLabel(box.index), 25f, ink).draw(canvas, box.x + box.width / 2, plan.registerY, true)
            }
            plan.stageOutputs.forEachIndexed { index, point ->
                DiagramLabelText(spec.stageOutputLabel(index), 25f, ink).draw(canvas, point.x, point.y, true)
            }
            plan.taps.forEach { canvas.drawCircle(it.x, it.y, 3.6f, fill) }
            plan.adders.forEach { p ->
                val r = LfsrLayout.XOR_RADIUS
                canvas.drawCircle(p.x, p.y, r, stroke)
                canvas.drawLine(p.x - r + 6, p.y, p.x + r - 6, p.y, stroke)
                canvas.drawLine(p.x, p.y - r + 6, p.x, p.y + r - 6, stroke)
            }
            if (spec.outputLabel.isNotEmpty()) {
                val text = DiagramLabelText(spec.outputLabel, 24f, ink)
                text.draw(canvas, plan.output.x - 14 - text.width, plan.output.y - 27)
            }
            plan.clockY?.let { clockY ->
                val centers = plan.registers.map { it.x + it.width / 2 }
                line(listOf(DiagramPoint(centers.first(), clockY), DiagramPoint(centers.last() + 48f, clockY)), false)
                centers.forEach { x ->
                    line(listOf(DiagramPoint(x, clockY), DiagramPoint(x, plan.registerY - LfsrLayout.BOX_HEIGHT / 2)), true)
                }
                DiagramLabelText(spec.clockLabel, 23f, ink).draw(canvas, centers.last() + 58, clockY)
            }
            canvas.translate(0f, plan.height + 20f)
        }
        return bitmap
    }
}
