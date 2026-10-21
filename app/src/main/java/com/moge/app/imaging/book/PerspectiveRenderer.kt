package com.moge.app.imaging.book

import android.graphics.Bitmap
import android.graphics.Canvas
import com.moge.app.data.image.requirePhotoEditingCapacity
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Combines perspective, table flattening or text alignment; samples the original once. */
internal object PerspectiveRenderer {
    data class Page(val bitmap: Bitmap, val boundary: List<Corner>?)

    fun render(bitmap: Bitmap, corners: List<Corner>, rectifyTable: Boolean = true, grayscale: Boolean = false): Bitmap =
        renderPage(bitmap, corners, rectifyTable, grayscale).bitmap

    fun renderPage(bitmap: Bitmap, corners: List<Corner>, rectifyTable: Boolean = true, grayscale: Boolean = false, rectifyBook: Boolean = false): Page {
        require(bitmap.width >= 2 && bitmap.height >= 2 && Geometry.isValid(corners)) { "请调整四个角，避免交叉或区域过小" }
        val w = bitmap.width - 1; val h = bitmap.height - 1
        val top = Geometry.distance(corners[0], corners[1], w, h)
        val bottom = Geometry.distance(corners[3], corners[2], w, h)
        val left = Geometry.distance(corners[0], corners[3], w, h)
        val right = Geometry.distance(corners[1], corners[2], w, h)
        // Opposing-edge geometric means reduce the wide/short bias of independently taking maxima.
        // Exact metric page proportions require camera calibration or a known paper-size constraint.
        val aspect = sqrt(top * bottom / (left * right)).coerceIn(.15, 6.0)
        var width = max(max(top, bottom), max(left, right) * aspect) + 1
        var height = (width - 1) / aspect + 1
        width = max(2.0, width.roundToInt().toDouble())
        height = max(2.0, height.roundToInt().toDouble())
        var outW = width.toInt(); var outH = height.toInt()
        val from = MatOfPoint2f(*corners.map { Point(it.x.toDouble() * w, it.y.toDouble() * h) }.toTypedArray())
        val to = MatOfPoint2f(Point(0.0, 0.0), Point(width - 1, 0.0), Point(width - 1, height - 1), Point(0.0, height - 1))
        val transform = Imgproc.getPerspectiveTransform(from, to)
        val inverse = Mat(); val source = Mat()
        var result: Bitmap? = null
        try {
            Utils.bitmapToMat(bitmap, source)
            var angle = 0.0
            val grid: PageWarp? = if (rectifyTable) {
                val reduced = Mat(); val rect = Mat()
                try {
                    val ratio = min(1.0, 1400.0 / max(bitmap.width, bitmap.height))
                    Imgproc.resize(source, reduced, Size(), ratio, ratio, Imgproc.INTER_AREA)
                    val smallFrom = MatOfPoint2f(*corners.map { Point(it.x * (reduced.cols() - 1.0), it.y * (reduced.rows() - 1.0)) }.toTypedArray())
                    val analysisScale = min(1.0, 1400.0 / max(width, height))
                    val analysisW = max(32, (width * analysisScale).roundToInt())
                    val analysisH = max(32, (height * analysisScale).roundToInt())
                    val smallTo = MatOfPoint2f(Point(0.0, 0.0), Point(analysisW - 1.0, 0.0), Point(analysisW - 1.0, analysisH - 1.0), Point(0.0, analysisH - 1.0))
                    val smallTransform = Imgproc.getPerspectiveTransform(smallFrom, smallTo)
                    try {
                        Imgproc.warpPerspective(reduced, rect, smallTransform, Size(analysisW.toDouble(), analysisH.toDouble()), Imgproc.INTER_LINEAR)
                        val table = TableRectifier.analyze(rect)
                        val book = if (table == null && rectifyBook) BookDewarper.analyze(rect)?.warp else null
                        if (table == null && book == null) angle = TextDeskewer.estimate(rect)
                        table ?: book
                    } finally { smallFrom.release(); smallTo.release(); smallTransform.release() }
                } finally { reduced.release(); rect.release() }
            } else null
            // Legacy gray enhancement can still sample one channel. Color purification
            // retains RGB all the way through geometry and illumination correction.
            if (grayscale) Imgproc.cvtColor(source, source, Imgproc.COLOR_RGBA2GRAY)
            var boundary: Array<Point>? = null
            if (angle != 0.0) {
                val rotation = Imgproc.getRotationMatrix2D(Point((width - 1) / 2, (height - 1) / 2), angle, 1.0)
                try {
                    val m = DoubleArray(6).also { rotation.get(0, 0, it) }
                    val cosine = abs(m[0]); val sine = abs(m[1])
                    outW = ceil((width - 1) * cosine + (height - 1) * sine + 1).toInt()
                    outH = ceil((height - 1) * cosine + (width - 1) * sine + 1).toInt()
                    m[2] += (outW - width) / 2; m[5] += (outH - height) / 2
                    boundary = arrayOf(Point(0.0, 0.0), Point(width - 1, 0.0), Point(width - 1, height - 1), Point(0.0, height - 1)).map {
                        Point(m[0] * it.x + m[1] * it.y + m[2], m[3] * it.x + m[4] * it.y + m[5])
                    }.toTypedArray()
                    val old = DoubleArray(9).also { transform.get(0, 0, it) }
                    val combined = DoubleArray(9)
                    for (c in 0..2) {
                        combined[c] = m[0] * old[c] + m[1] * old[3 + c] + m[2] * old[6 + c]
                        combined[3 + c] = m[3] * old[c] + m[4] * old[3 + c] + m[5] * old[6 + c]
                        combined[6 + c] = old[6 + c]
                    }
                    transform.put(0, 0, *combined)
                } finally { rotation.release() }
            }
            Core.invert(transform, inverse)
            // Source bitmap and native geometry already exist; budget only the
            // additional destination here, rather than counting those twice.
            requirePhotoEditingCapacity(outW, outH, bytesPerPixel = 4)
            if (grid == null && angle == 0.0 && !grayscale && corners == Geometry.fullFrame &&
                outW == bitmap.width && outH == bitmap.height) {
                // Full-frame photos with no supported correction need no resampling.
                // Return an owned copy: callers may recycle their source immediately.
                return Page(bitmap.copy(Bitmap.Config.ARGB_8888, false), null)
            }
            val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            result = output
            if (grid == null) renderPerspective(source, output, inverse, boundary)
            else renderMesh(source, output, inverse, grid)
            return Page(output, boundary?.map { Corner((it.x / (outW - 1)).toFloat(), (it.y / (outH - 1)).toFloat()) })
        } catch (error: Throwable) { result?.recycle(); throw error }
        finally { listOf(from, to, transform, inverse, source).forEach { it.release() } }
    }

    private const val STRIPE_ROWS = 128

    /** Ordinary pages need only a homography, with no dense Kotlin coordinate loop. */
    private fun renderPerspective(source: Mat, output: Bitmap, inverse: Mat, boundary: Array<Point>?) {
        val coefficients = DoubleArray(9).also { inverse.get(0, 0, it) }
        val stripeTransform = Mat(3, 3, CvType.CV_64F); val tile = Mat(); val mask = Mat()
        val painter = StripePainter(output)
        try {
            for (startY in 0 until output.height step STRIPE_ROWS) {
                val count = min(STRIPE_ROWS, output.height - startY)
                val shifted = coefficients.copyOf()
                for (row in 0..2) shifted[row * 3 + 2] += coefficients[row * 3 + 1] * startY
                stripeTransform.put(0, 0, *shifted)
                Imgproc.warpPerspective(source, tile, stripeTransform, Size(output.width.toDouble(), count.toDouble()),
                    Imgproc.INTER_CUBIC or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
                if (boundary != null) {
                    mask.create(count, output.width, CvType.CV_8U); mask.setTo(Scalar(0.0))
                    val polygon = MatOfPoint(*boundary.map { Point(it.x, it.y - startY) }.toTypedArray())
                    try { Imgproc.fillConvexPoly(mask, polygon, Scalar(255.0)) } finally { polygon.release() }
                    Core.bitwise_not(mask, mask); tile.setTo(Scalar.all(255.0), mask)
                }
                painter.draw(tile, startY)
            }
        } finally { painter.close(); stripeTransform.release(); tile.release(); mask.release() }
    }

    private fun renderMesh(source: Mat, output: Bitmap, inverse: Mat, grid: PageWarp) {
        val outW = output.width; val outH = output.height
        val meshW = min(321, max(33, outW / 6 + 1)); val meshH = min(321, max(33, outH / 6 + 1))
        val (meshX, meshY) = grid.sourceMesh(meshW, meshH)
        for (index in meshX.indices) {
            meshX[index] *= (outW - 1)
            meshY[index] *= (outH - 1)
        }
        val coefficients = DoubleArray(9).also { inverse.get(0, 0, it) }
        val a = coefficients[0]; val b = coefficients[1]; val c = coefficients[2]
        val d = coefficients[3]; val e = coefficients[4]; val f = coefficients[5]
        val g = coefficients[6]; val h = coefficients[7]; val k = coefficients[8]
        val identity = a == 1.0 && b == 0.0 && c == 0.0 && d == 0.0 && e == 1.0 && f == 0.0 &&
            g == 0.0 && h == 0.0 && k == 1.0
        val mapX = Mat(); val mapY = Mat(); val tile = Mat()
        val xs = FloatArray(outW * STRIPE_ROWS); val ys = FloatArray(xs.size)
        val xIndices = IntArray(outW); val fx = FloatArray(outW); val inverseFx = FloatArray(outW)
        val rowX = FloatArray(meshW); val rowY = FloatArray(meshW)
        for (x in 0 until outW) {
            val value = x.toFloat() * (meshW - 1) / (outW - 1)
            xIndices[x] = value.toInt().coerceAtMost(meshW - 2)
            fx[x] = value - xIndices[x]; inverseFx[x] = 1f - fx[x]
        }
        val painter = StripePainter(output)
        try {
            for (startY in 0 until outH step STRIPE_ROWS) {
                val count = min(STRIPE_ROWS, outH - startY)
                for (localY in 0 until count) {
                    val position = (startY + localY).toFloat() * (meshH - 1) / (outH - 1)
                    val row = position.toInt().coerceAtMost(meshH - 2)
                    val fy = position - row; val inverseFy = 1f - fy
                    val base = row * meshW
                    // Vertical interpolation is constant across a mesh cell's output row.
                    // Calculate it per mesh column rather than twice for every output pixel.
                    for (i in 0 until meshW) {
                        rowX[i] = meshX[base + i] * inverseFy + meshX[base + meshW + i] * fy
                        rowY[i] = meshY[base + i] * inverseFy + meshY[base + meshW + i] * fy
                    }
                    var index = localY * outW
                    for (x in 0 until outW) {
                        val i = xIndices[x]
                        val meshSourceX = rowX[i] * inverseFx[x] + rowX[i + 1] * fx[x]
                        val meshSourceY = rowY[i] * inverseFx[x] + rowY[i + 1] * fx[x]
                        if (identity) {
                            // The full-frame book path has no perspective component.
                            // Keep its coordinates verbatim instead of projecting every pixel.
                            xs[index] = meshSourceX
                            ys[index] = meshSourceY
                        } else {
                            val sx = meshSourceX.toDouble(); val sy = meshSourceY.toDouble()
                            val denominator = g * sx + h * sy + k
                            xs[index] = ((a * sx + b * sy + c) / denominator).toFloat()
                            ys[index] = ((d * sx + e * sy + f) / denominator).toFloat()
                        }
                        index++
                    }
                }
                mapX.create(count, outW, CvType.CV_32F); mapY.create(count, outW, CvType.CV_32F)
                mapX.put(0, 0, if (count == STRIPE_ROWS) xs else xs.copyOf(outW * count))
                mapY.put(0, 0, if (count == STRIPE_ROWS) ys else ys.copyOf(outW * count))
                // Sample the full-resolution source once; preserve the existing cubic kernel.
                Imgproc.remap(source, tile, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
                painter.draw(tile, startY)
            }
        } finally {
            painter.close(); mapX.release(); mapY.release(); tile.release()
        }
    }

    /** Reuse one small bitmap across stripes instead of allocating one per draw. */
    private class StripePainter(output: Bitmap) : AutoCloseable {
        private val canvas = Canvas(output)
        private var bitmap: Bitmap? = null

        fun draw(tile: Mat, startY: Int) {
            var buffer = bitmap
            if (buffer == null || buffer.width != tile.cols() || buffer.height != tile.rows()) {
                buffer?.recycle()
                buffer = Bitmap.createBitmap(tile.cols(), tile.rows(), Bitmap.Config.ARGB_8888)
                bitmap = buffer
            }
            Utils.matToBitmap(tile, buffer)
            canvas.drawBitmap(buffer, 0f, startY.toFloat(), null)
        }

        override fun close() { bitmap?.recycle() }
    }
}
