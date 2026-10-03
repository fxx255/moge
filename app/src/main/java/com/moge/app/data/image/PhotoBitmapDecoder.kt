package com.moge.app.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlin.math.roundToInt

private const val MAX_PHOTO_EDGE = 4096
internal const val MAX_PHOTO_PIXELS = 4L * 1024 * 1024

/** Budget the decoded pixels, rather than trusting the compressed file size or the device brand. */
internal fun photoPixelBudget(): Long = (Runtime.getRuntime().maxMemory() / 64)
    .coerceIn(512L * 1024, MAX_PHOTO_PIXELS)

internal fun photoSampleSize(width: Int, height: Int, maxEdge: Int, maxPixels: Long): Int {
    require(width > 0 && height > 0 && maxEdge > 0 && maxPixels > 0)
    var sample = 1
    while (true) {
        val w = (width.toLong() + sample - 1) / sample
        val h = (height.toLong() + sample - 1) / sample
        if (w <= maxEdge && h <= maxEdge && w * h <= maxPixels) return sample
        check(sample < (1 shl 30)) { "图片尺寸过大" }
        sample *= 2
    }
}

private fun decodeOptions(width: Int, height: Int, maxEdge: Int) = BitmapFactory.Options().apply {
    inSampleSize = photoSampleSize(width, height, maxEdge.coerceIn(1, MAX_PHOTO_EDGE), photoPixelBudget())
    // Keep HDR/wide-gamut images out of F16/hardware buffers on vendor renderers.
    inPreferredConfig = Bitmap.Config.ARGB_8888
    inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
}

private data class PhotoInfo(val width: Int, val height: Int, val orientation: Int)

private fun photoInfo(path: String): PhotoInfo? {
    if (!File(path).let { it.isFile && it.length() > 0 }) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val orientation = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        .getOrDefault(1)
    return PhotoInfo(bounds.outWidth, bounds.outHeight, orientation)
}

private fun orientationMatrix(orientation: Int, quarterTurns: Int): Matrix = Matrix().apply {
    when (orientation) {
        2 -> setScale(-1f, 1f)
        3 -> setRotate(180f)
        4 -> setScale(1f, -1f)
        5 -> { setRotate(90f); postScale(-1f, 1f) }
        6 -> setRotate(90f)
        7 -> { setRotate(-90f); postScale(-1f, 1f) }
        8 -> setRotate(-90f)
    }
    postRotate(Math.floorMod(quarterTurns, 4) * 90f)
}

private fun selectedCrop(width: Int, height: Int, select: (Int, Int) -> Rect): Rect = select(width, height).also {
    require(it.left >= 0 && it.top >= 0 && it.right <= width && it.bottom <= height &&
        it.width() > 0 && it.height() > 0) { "无效的裁剪区域" }
}

/** Consumes an undisplayed bitmap; returned pixels remain owned by the caller. */
private fun orientOwned(source: Bitmap, orientation: Int, quarterTurns: Int): Bitmap {
    try {
        val matrix = orientationMatrix(orientation, quarterTurns)
        val result = if (matrix.isIdentity) source
            else Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, false)
        if (result !== source) source.recycle()
        return result
    } catch (error: Throwable) {
        source.recycle()
        throw error
    }
}

/** A bounded software bitmap for thumbnails, viewers, editing and model input. */
fun decodePhotoBitmap(path: String, maxEdge: Int = MAX_PHOTO_EDGE, quarterTurns: Int = 0): Bitmap? = runCatching {
    val info = photoInfo(path) ?: return@runCatching null
    val bitmap = BitmapFactory.decodeFile(path, decodeOptions(info.width, info.height, maxEdge))
        ?: return@runCatching null
    orientOwned(bitmap, info.orientation, quarterTurns)
}.onFailure { Log.w("MogePhoto", "Photo decoding failed", it) }.getOrNull()

/** Read only the selected source region, retaining small crops' detail without decoding the whole photo. */
@Suppress("DEPRECATION")
fun decodePhotoCrop(
    path: String, quarterTurns: Int,
    cropInOrientedPixels: (width: Int, height: Int) -> Rect,
): Bitmap? {
    val region = runCatching {
        val info = photoInfo(path) ?: return@runCatching null
        val matrix = orientationMatrix(info.orientation, quarterTurns)
        val frame = RectF(0f, 0f, info.width.toFloat(), info.height.toFloat())
        matrix.mapRect(frame)
        matrix.postTranslate(-frame.left, -frame.top)
        val selected = selectedCrop(frame.width().roundToInt(), frame.height().roundToInt(), cropInOrientedPixels)
        val inverse = Matrix()
        check(matrix.invert(inverse))
        val raw = RectF(selected)
        inverse.mapRect(raw)
        val left = raw.left.roundToInt().coerceIn(0, info.width - 1)
        val top = raw.top.roundToInt().coerceIn(0, info.height - 1)
        val rectangle = Rect(left, top, raw.right.roundToInt().coerceIn(left + 1, info.width),
            raw.bottom.roundToInt().coerceIn(top + 1, info.height))
        val decoder = BitmapRegionDecoder.newInstance(path, false) ?: return@runCatching null
        try {
            decoder.decodeRegion(rectangle, decodeOptions(rectangle.width(), rectangle.height(), MAX_PHOTO_EDGE))
                ?.let { orientOwned(it, info.orientation, quarterTurns) }
        } finally { decoder.recycle() }
    }.onFailure { Log.w("MogePhoto", "Region decoding unavailable; using bounded photo fallback", it) }.getOrNull()
    if (region != null) return region

    // Some vendor codecs cannot decode regions (e.g. certain HEIF implementations).
    val source = decodePhotoBitmap(path, quarterTurns = quarterTurns) ?: return null
    return runCatching {
        try {
            val selected = selectedCrop(source.width, source.height, cropInOrientedPixels)
            val cropped = Bitmap.createBitmap(source, selected.left, selected.top, selected.width(), selected.height())
            if (cropped !== source) source.recycle()
            cropped
        } catch (error: Throwable) {
            source.recycle()
            throw error
        }
    }.onFailure { Log.w("MogePhoto", "Photo crop failed", it) }.getOrNull()
}
