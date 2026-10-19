package com.moge.app.imaging.book

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import org.opencv.core.Mat
import kotlin.math.*

/** Valid photographed paper, excluding the white canvas introduced by deskew. */
internal object PaperBoundary {
    /** Extend the paper for background estimation only. Never edit the source ink. */
    fun extendBackground(image: Mat, boundary: List<Corner>?) {
        if (boundary == null) return
        val width = image.cols(); val height = image.rows(); val channels = image.channels()
        val xs = boundary.map { it.x.toDouble() * (width - 1) }
        val ys = boundary.map { it.y.toDouble() * (height - 1) }
        val cx = xs.average(); val cy = ys.average()
        val pixels = ByteArray(width * height * channels).also { image.get(0, 0, it) }
        fun extend(x: Int, y: Int) {
            var distance = Double.POSITIVE_INFINITY; var nearestX = cx; var nearestY = cy
            for (i in 0..3) {
                val j = (i + 1) % 4; val dx = xs[j] - xs[i]; val dy = ys[j] - ys[i]
                val t = (((x - xs[i]) * dx + (y - ys[i]) * dy) / max(1e-9, dx * dx + dy * dy)).coerceIn(0.0, 1.0)
                val xx = xs[i] + t * dx; val yy = ys[i] + t * dy
                val d = (xx - x).pow(2) + (yy - y).pow(2)
                if (d < distance) { distance = d; nearestX = xx; nearestY = yy }
            }
            // Step inward to avoid rasterized border coverage and reduced-image rounding.
            val inward = min(1.0, 2.0 / max(1e-9, hypot(cx - nearestX, cy - nearestY)))
            val sx = (nearestX + (cx - nearestX) * inward).roundToInt().coerceIn(0, width - 1)
            val sy = (nearestY + (cy - nearestY) * inward).roundToInt().coerceIn(0, height - 1)
            val from = (sy * width + sx) * channels; val to = (y * width + x) * channels
            for (c in 0 until channels) pixels[to + c] = pixels[from + c]
        }
        for (y in 0 until height) {
            var left = Double.POSITIVE_INFINITY; var right = Double.NEGATIVE_INFINITY
            for (i in 0..3) {
                val j = (i + 1) % 4
                if (abs(ys[j] - ys[i]) < 1e-9 || y < min(ys[i], ys[j]) || y > max(ys[i], ys[j])) continue
                val x = xs[i] + (y - ys[i]) * (xs[j] - xs[i]) / (ys[j] - ys[i])
                left = min(left, x); right = max(right, x)
            }
            if (!left.isFinite()) {
                for (x in 0 until width) extend(x, y)
            } else {
                val first = ceil(left).toInt().coerceIn(0, width)
                val last = floor(right).toInt().coerceIn(-1, width - 1)
                for (x in 0 until first) extend(x, y)
                for (x in max(first, last + 1) until width) extend(x, y)
            }
        }
        image.put(0, 0, pixels)
    }

    /** Fill only the generated canvas; keep the photographed page and its dimensions. */
    fun whitenCanvas(bitmap: Bitmap, boundary: List<Corner>?) {
        if (boundary == null) return
        val path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), Path.Direction.CW)
            moveTo(boundary[0].x * (bitmap.width - 1), boundary[0].y * (bitmap.height - 1))
            for (point in boundary.drop(1)) lineTo(point.x * (bitmap.width - 1), point.y * (bitmap.height - 1))
            close()
        }
        Canvas(bitmap).drawPath(path, Paint().apply { color = Color.WHITE })
    }
}
