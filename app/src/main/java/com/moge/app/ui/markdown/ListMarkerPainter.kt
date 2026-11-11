package com.moge.app.ui.markdown

import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import com.moge.app.data.prefs.ListMarkerSettings
import com.moge.app.data.prefs.ListMarkerStyle
import kotlin.math.ceil

/** One drawing implementation shared by list spans and the settings picker. */
internal class ListMarkerPainter(settings: ListMarkerSettings, textSizePx: Float) {
    private val config = settings.normalized()
    private val size = textSizePx * config.sizePercent / 100f
    private val radius = size * 0.26f
    private val stroke = (size * 0.11f * config.strokePercent / 100f).coerceAtLeast(0.5f)
    private val halfWidth = when (config.style) {
        ListMarkerStyle.ROUNDED_DIAMOND -> size * 0.30f
        ListMarkerStyle.ROUNDED_SQUARE -> radius * 0.86f
        ListMarkerStyle.THEME_DOT -> size * 0.16f
        ListMarkerStyle.CLASSIC_DOT -> size * 0.25f
        else -> radius
    } + if (config.style.hasStroke) stroke / 2 else 0f
    val minimumMarginPx = ceil(halfWidth * 2 + textSizePx * 0.12f).toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = stroke
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        style = if (config.style.hasStroke) Paint.Style.STROKE else Paint.Style.FILL
        if (config.style == ListMarkerStyle.ROUNDED_DIAMOND) pathEffect = CornerPathEffect(size * 0.075f)
    }
    private val path = Path().apply {
        when (config.style) {
            ListMarkerStyle.OCTAGON -> {
                // Axis-aligned, with four equal short axial edges and four equal diagonal edges.
                moveTo(-radius * 0.36f, -radius); lineTo(radius * 0.36f, -radius)
                lineTo(radius, -radius * 0.36f); lineTo(radius, radius * 0.36f)
                lineTo(radius * 0.36f, radius); lineTo(-radius * 0.36f, radius)
                lineTo(-radius, radius * 0.36f); lineTo(-radius, -radius * 0.36f)
                close()
            }
            ListMarkerStyle.ROUNDED_DIAMOND -> {
                val d = size * 0.30f
                moveTo(0f, -d); lineTo(d, 0f); lineTo(0f, d); lineTo(-d, 0f); close()
            }
            else -> Unit
        }
    }

    fun draw(canvas: Canvas, centerX: Float, centerY: Float, accentColor: Int, bodyColor: Int) {
        paint.color = if (config.style == ListMarkerStyle.CLASSIC_DOT) bodyColor else accentColor
        val saved = canvas.save()
        try {
            canvas.translate(centerX, centerY)
            when (config.style) {
                ListMarkerStyle.OCTAGON, ListMarkerStyle.ROUNDED_DIAMOND -> canvas.drawPath(path, paint)
                ListMarkerStyle.RING -> canvas.drawCircle(0f, 0f, radius, paint)
                ListMarkerStyle.DASH -> canvas.drawLine(-radius, 0f, radius, 0f, paint)
                ListMarkerStyle.ROUNDED_SQUARE -> {
                    val r = radius * 0.86f
                    canvas.drawRoundRect(-r, -r, r, r, size * 0.075f, size * 0.075f, paint)
                }
                ListMarkerStyle.THEME_DOT -> canvas.drawCircle(0f, 0f, size * 0.16f, paint)
                ListMarkerStyle.CLASSIC_DOT -> canvas.drawCircle(0f, 0f, size * 0.25f, paint)
            }
        } finally { canvas.restoreToCount(saved) }
    }
}
