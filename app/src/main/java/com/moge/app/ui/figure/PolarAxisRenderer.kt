package com.moge.app.ui.figure

import android.graphics.Canvas
import android.graphics.Paint
import com.moge.app.domain.plot.PolarAxes
import com.moge.app.domain.plot.niceTicks
import com.moge.app.domain.plot.polarAngleLabel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal data class PolarViewport(val centerX: Float, val centerY: Float, val radiusPx: Float, val radiusMax: Double)
internal data class PolarAxisLabel(val text: String, val centerX: Float, val baseline: Float, val radial: Boolean)

/** Draw only the coordinate system; curves and collision-aware labels use the shared renderer. */
internal fun drawPolarAxes(
    canvas: Canvas,
    axes: PolarAxes,
    viewport: PolarViewport,
    textSize: Float,
    axisWidth: Float,
    theme: PlotBitmapRenderer.Theme,
    formatRadius: (Double) -> String,
    labelWidth: (String) -> Float,
    label: (PolarAxisLabel) -> Unit,
) {
    val (cx, cy, radius, maxRadius) = viewport
    val ticks = (axes.radialTicks ?: niceTicks(0.0, maxRadius, 4)).filter { it > 0 && it <= maxRadius }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = axisWidth * .5f
        color = theme.grid
    }
    if (axes.grid) {
        for (r in ticks) canvas.drawCircle(cx, cy, (radius * r / maxRadius).toFloat(), paint)
        for (degrees in 0 until 360 step axes.angleStepDegrees) {
            val angle = degrees * PI / 180
            canvas.drawLine(cx, cy, cx + radius * cos(angle).toFloat(), cy - radius * sin(angle).toFloat(), paint)
        }
    }
    paint.color = theme.axis
    canvas.drawCircle(cx, cy, radius, paint)
    paint.strokeWidth = axisWidth
    canvas.drawLine(cx, cy, cx + radius, cy, paint)
    val arrow = textSize * .3f
    canvas.drawLine(cx + radius, cy, cx + radius - arrow, cy - arrow * .5f, paint)
    canvas.drawLine(cx + radius, cy, cx + radius - arrow, cy + arrow * .5f, paint)

    for (degrees in 0 until 360 step axes.angleStepDegrees) {
        val angle = degrees * PI / 180
        val text = polarAngleLabel(degrees)
        // The whole glyph box must sit outside the circle, including diagonal labels.
        val distance = radius + textSize * .6f + labelWidth(text) / 2 * abs(cos(angle)).toFloat() +
            textSize / 2 * abs(sin(angle)).toFloat()
        label(PolarAxisLabel(text, cx + distance * cos(angle).toFloat(),
            cy - distance * sin(angle).toFloat() + textSize * .34f, radial = false))
    }
    for (r in ticks) label(PolarAxisLabel(formatRadius(r), cx + (radius * r / maxRadius).toFloat(),
        cy + textSize * 1.2f, radial = true))
    label(PolarAxisLabel("O", cx - textSize * .5f, cy + textSize * 1.2f, radial = true))
}
