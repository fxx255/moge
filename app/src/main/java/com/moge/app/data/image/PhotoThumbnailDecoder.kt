package com.moge.app.data.image

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.min
import kotlin.math.roundToInt

/** Decode the visible center crop at display resolution, retaining detail in tall/wide photos. */
internal fun decodePhotoThumbnail(path: String, width: Int, height: Int): Bitmap? {
    require(width > 0 && height > 0)
    // Decode up to twice the requested edge: power-of-two sampling must not
    // reduce the visible region below the number of pixels it occupies on screen.
    val limit = (maxOf(width, height).toLong() * 2).coerceAtMost(4096).toInt()
    val cropped = decodePhotoCrop(path, quarterTurns = 0, maxEdge = limit) { sourceWidth, sourceHeight ->
        val scale = min(sourceWidth.toDouble() / width, sourceHeight.toDouble() / height)
        val cropWidth = (width * scale).roundToInt().coerceIn(1, sourceWidth)
        val cropHeight = (height * scale).roundToInt().coerceIn(1, sourceHeight)
        val left = (sourceWidth - cropWidth) / 2
        val top = (sourceHeight - cropHeight) / 2
        Rect(left, top, left + cropWidth, top + cropHeight)
    } ?: return null
    return try {
        val scale = minOf(1f, width.toFloat() / cropped.width, height.toFloat() / cropped.height)
        if (scale >= 1f) cropped
        else Bitmap.createScaledBitmap(cropped, (cropped.width * scale).roundToInt().coerceAtLeast(1),
            (cropped.height * scale).roundToInt().coerceAtLeast(1), true).also { if (it !== cropped) cropped.recycle() }
    } catch (error: Throwable) {
        cropped.recycle()
        android.util.Log.w("MogePhoto", "Thumbnail scaling failed", error)
        null
    }
}
