package com.moge.app.imaging.book

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Small background model, full-resolution ink. No recognition or generated strokes. */
internal object ColorDocumentEnhancer {
    private const val ANALYSIS_EDGE = 720
    private const val STRIPE_ROWS = 96
    // Byte illumination and corrected RGB keep the stripes compact. The 256 KiB
    // table covers each source-channel/light pair, avoiding per-pixel division.
    // Geometry and source dimensions remain unchanged; tone curves still follow.
    private val correctedChannels = IntArray(256 * 256) { index ->
        min(255f, (index ushr 8) * 255f / max(32, index and 255)).roundToInt()
    }

    private fun correctChannel(channel: Int, light: Byte): Int =
        correctedChannels[(channel shl 8) or (light.toInt() and 255)]

    fun apply(bitmap: Bitmap, boundary: List<Corner>? = null, book: Boolean = false): Bitmap {
        val scale = min(1.0, ANALYSIS_EDGE.toDouble() / max(bitmap.width, bitmap.height))
        val small = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()), true)
        val rgba = Mat(); val rgb = Mat(); val background = Mat(); val field = Mat(); val smooth = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(17.0, 17.0))
        var output: Bitmap? = null
        try {
            try { Utils.bitmapToMat(small, rgba) } finally { if (small !== bitmap) small.recycle() }
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB); rgba.release()
            // Generated white padding is not photographed paper. Extend real paper colors
            // in the small illumination model; restore white padding only after filtering.
            PaperBoundary.extendBackground(rgb, boundary)
            val bytes = ByteArray(rgb.rows() * rgb.cols() * 3).also { rgb.get(0, 0, it) }
            val histogram = IntArray(256)
            for (i in bytes.indices step 3) histogram[maxOf(bytes[i].toInt() and 255,
                bytes[i + 1].toInt() and 255, bytes[i + 2].toInt() and 255)]++
            var count = 0; var percentile = 0
            while (percentile < 255 && count < rgb.total() * .65) count += histogram[percentile++]
            // Dark boards/screens are intentional. Do not invert, bleach, or recolor them.
            if (percentile < 140) return bitmap.copy(Bitmap.Config.ARGB_8888, false)

            // Closing estimates the paper above small glyphs, unlike a blur that averages ink
            // into the paper. It is used only for illumination; never morph the actual text.
            Imgproc.morphologyEx(rgb, background, Imgproc.MORPH_CLOSE, kernel)
            background.get(0, 0, bytes)
            val values = FloatArray(rgb.rows() * rgb.cols() * 4)
            val whites = FloatArray(rgb.rows() * rgb.cols())
            val fallback = DoubleArray(3); var anchors = 0
            for (p in whites.indices) {
                val r = bytes[p * 3].toInt() and 255; val g = bytes[p * 3 + 1].toInt() and 255
                val b = bytes[p * 3 + 2].toInt() and 255
                val hi = maxOf(r, g, b).coerceAtLeast(40).toFloat(); val lo = minOf(r, g, b)
                whites[p] = hi
                // Exclude red/blue/green ink and colored panels from the paper's color cast.
                // Mild warm illumination remains an anchor; saturated yellow does not.
                val colored = (r - g > hi * .04 && r - g > (g - b) * .8) ||
                    b - r > hi * .04 || g - max(r, b) > hi * .04 || (hi - lo) / hi > .35
                if (!colored) {
                    values[p * 4] = r / hi; values[p * 4 + 1] = g / hi; values[p * 4 + 2] = b / hi
                    values[p * 4 + 3] = 1f
                    fallback[0] += r / hi; fallback[1] += g / hi; fallback[2] += b / hi; anchors++
                }
            }
            field.create(rgb.rows(), rgb.cols(), CvType.CV_32FC4); field.put(0, 0, values)
            Imgproc.GaussianBlur(field, smooth, Size(), max(rgb.rows(), rgb.cols()) / 22.0)
            smooth.get(0, 0, values)
            val white = Mat(rgb.rows(), rgb.cols(), CvType.CV_32F)
            try {
                white.put(0, 0, whites); Imgproc.GaussianBlur(white, white, Size(), 2.2)
                white.get(0, 0, whites)
            } finally { white.release() }
            for (p in whites.indices) {
                val weight = values[p * 4 + 3]
                for (c in 0..2) {
                    val cast = if (weight > .02f) values[p * 4 + c] / weight
                        else if (anchors > 0) (fallback[c] / anchors).toFloat() else 1f
                    values[p * 4 + c] = whites[p] * cast.coerceIn(.65f, 1f)
                }
                values[p * 4 + 3] = 255f
            }
            if (book) {
                WarmPaperShadowModel.fit(bytes)?.refine(bytes, values)
            }
            field.put(0, 0, values)
            if (book) {
                BookPageModel.refineGutter(rgb, background, field)
            }
            field.convertTo(field, CvType.CV_8U)
            rgb.release(); background.release(); smooth.release()
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            output = result
            render(bitmap, result, field)
            PaperBoundary.whitenCanvas(result, boundary)
            // All pixels have now been written with alpha 255. Mark the completed
            // image opaque so PNG can omit its redundant alpha channel.
            result.setHasAlpha(false)
            return result
        } catch (error: Throwable) { output?.recycle(); throw error }
        finally { listOf(rgba, rgb, background, field, smooth, kernel).forEach { it.release() } }
    }

    private fun render(input: Bitmap, output: Bitmap, field: Mat) {
        val width = input.width
        val radius = (max(input.width, input.height) / 900).coerceIn(1, 3)
        // Halo rows keep the nearby-ink mask continuous across stripe boundaries.
        val pixels = IntArray(width * (STRIPE_ROWS + radius * 2))
        val light = ByteArray(pixels.size * 4)
        val values = ByteArray(pixels.size); val nearbyInk = ByteArray(pixels.size)
        val transform = Mat(2, 3, CvType.CV_64F); val tile = Mat(); val valueMat = Mat(); val minimum = Mat()
        val supportKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
            Size((radius * 2 + 1).toDouble(), (radius * 2 + 1).toDouble()))
        val sx = field.cols().toDouble() / width; val sy = field.rows().toDouble() / input.height
        try {
            for (y in 0 until input.height step STRIPE_ROWS) {
                val rows = min(STRIPE_ROWS, input.height - y)
                val start = max(0, y - radius); val end = min(input.height, y + rows + radius); val count = end - start
                transform.put(0, 0, sx, 0.0, (sx - 1) / 2, 0.0, sy, (start + .5) * sy - .5)
                Imgproc.warpAffine(field, tile, transform, Size(width.toDouble(), count.toDouble()),
                    Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
                tile.get(0, 0, light)
                input.getPixels(pixels, 0, width, 0, start, width, count)
                for (p in 0 until width * count) {
                    val color = pixels[p]; val offset = p * 4
                    val r = correctChannel((color ushr 16) and 255, light[offset])
                    val g = correctChannel((color ushr 8) and 255, light[offset + 1])
                    val b = correctChannel(color and 255, light[offset + 2])
                    val hi = maxOf(r, g, b)
                    pixels[p] = (255 shl 24) or (r shl 16) or (g shl 8) or b
                    values[p] = hi.toByte()
                }
                valueMat.create(count, width, CvType.CV_8U)
                valueMat.put(0, 0, values, 0, width * count)
                // This minimum is a support mask only. Never filter, dilate or
                // erode the actual glyph pixels: their native edge tones survive.
                Imgproc.erode(valueMat, minimum, supportKernel); minimum.get(0, 0, nearbyInk)
                for (p in (y - start) * width until (y - start + rows) * width) {
                    val color = pixels[p]
                    val r = (color ushr 16) and 255; val g = (color ushr 8) and 255; val b = color and 255
                    val hi = maxOf(r, g, b); val lo = minOf(r, g, b)
                    // Remove tiny neutral-paper color fluctuations without flattening pink
                    // boxes or colored ink. Keep their hue and relative channel ordering.
                    val saturation = if (hi > 0) (hi - lo).toFloat() / hi else 0f
                    val chroma = ((saturation - .035f) / .065f).coerceIn(0f, 1f)
                    val brightness = values[p].toInt() and 255
                    val nearbyMinimum = nearbyInk[p].toInt() and 255
                    if (chroma == 0f) {
                        // The original formula reduces to a single gray value.
                        // Colored panels and ink retain the full channel calculation.
                        pixels[p] = DocumentInkTone.neutralArgb(brightness, nearbyMinimum)
                        continue
                    }
                    val v = DocumentInkTone.value(brightness, nearbyMinimum, chroma)
                    val gain = if (hi > 0) v / hi else 0f
                    val red = (v - (hi - r) * gain * chroma).roundToInt().coerceIn(0, 255)
                    val green = (v - (hi - g) * gain * chroma).roundToInt().coerceIn(0, 255)
                    val blue = (v - (hi - b) * gain * chroma).roundToInt().coerceIn(0, 255)
                    pixels[p] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
                }
                output.setPixels(pixels, (y - start) * width, width, 0, y, width, rows)
            }
        } finally { listOf(transform, tile, valueMat, minimum, supportKernel).forEach { it.release() } }
    }
}
