package com.moge.app.ui.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.moge.app.data.image.decodePhotoBitmap
import java.io.File
import kotlin.math.sqrt
import kotlin.math.roundToInt

internal data class ExportImageInfo(val width: Int, val height: Int)

internal fun exportImageInfo(path: String): ExportImageInfo? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val orientation = runCatching { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrDefault(1)
    if (orientation in 5..8) ExportImageInfo(bounds.outHeight, bounds.outWidth)
    else ExportImageInfo(bounds.outWidth, bounds.outHeight)
}.getOrNull()

/** Decode for the printed width; a portrait's height no longer unnecessarily shrinks its details. */
internal fun decodeExportImage(path: String): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
            val budget = (Runtime.getRuntime().maxMemory() / 16).coerceIn(2L * 1024 * 1024, 8L * 1024 * 1024)
            val scale = minOf(1.0, ExportLimits.CONTENT_WIDTH.toDouble() / info.size.width,
                sqrt(budget.toDouble() / (info.size.width.toLong() * info.size.height)))
            decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
    } else decodePhotoBitmap(path, 4096)
}.getOrNull()
