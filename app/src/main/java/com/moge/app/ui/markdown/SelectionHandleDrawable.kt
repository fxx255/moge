package com.moge.app.ui.markdown

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils

/** The square upper corner is the precise selection anchor; the round body sits outside the range. */
internal class SelectionHandleDrawable(color: Int, private val start: Boolean, density: Float) : Drawable() {
    private val size = (21f * density).toInt().coerceAtLeast(1)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = ColorUtils.setAlphaComponent(color, 255) }
    private val path = Path()
    private val shapeBounds = RectF()
    private val radii = FloatArray(8)

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun draw(canvas: Canvas) {
        val radius = minOf(bounds.width(), bounds.height()) / 2f
        radii.fill(radius)
        val corner = if (start) 2 else 0
        radii[corner] = 0f
        radii[corner + 1] = 0f
        path.reset()
        shapeBounds.set(bounds)
        path.addRoundRect(shapeBounds, radii, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
