package com.moge.app.ui.figure

import android.graphics.Canvas
import android.graphics.Paint
import com.moge.app.domain.diagram.DiagramText
import com.moge.app.domain.diagram.DiagramTextRole

/** Measured mixed prose/math for the fixed coding templates, anchored by its visual center. */
internal class DiagramLabelText(text: String, size: Float, color: Int) {
    internal val block = DiagramText.layout(text, DiagramTextRole.LABEL, Float.POSITIVE_INFINITY,
        fontSizePx = size, formulaColor = color)
    val width = block.width
    private val height = (block.height - 4f).coerceAtLeast(0f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
    }

    fun draw(canvas: Canvas, x: Float, centerY: Float, centered: Boolean = false) {
        var top = centerY - height / 2f
        for (line in block.lines) {
            var cursor = x - if (centered) line.width / 2f else 0f
            for (run in line.runs) {
                val runTop = top + (line.height - run.height) / 2f
                val formula = run.formula
                if (formula != null) {
                    val saved = canvas.save()
                    canvas.translate(cursor, runTop)
                    formula.setBounds(0, 0, formula.intrinsicWidth, formula.intrinsicHeight)
                    formula.draw(canvas)
                    canvas.restoreToCount(saved)
                } else {
                    canvas.drawText(run.text, cursor, runTop - paint.fontMetrics.ascent, paint)
                }
                cursor += run.width
            }
            top += line.height + 4f
        }
    }
}
