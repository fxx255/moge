package com.moge.app.imaging.book

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Conservative text baseline alignment. Orientation (90/180 degrees) needs user input. */
internal object TextDeskewer {
    fun estimate(source: Mat): Double {
        if (min(source.rows(), source.cols()) < 48) return 0.0
        val gray = Mat(); val small = Mat(); val ink = Mat(); val labels = Mat()
        val stats = Mat(); val centers = Mat(); val mask = Mat(); val rotated = Mat(); val projection = Mat()
        try {
            if (source.channels() == 1) source.copyTo(gray)
            else Imgproc.cvtColor(source, gray, if (source.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            val scale = min(1.0, 720.0 / max(gray.rows(), gray.cols()))
            Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA); gray.release()
            val width = small.cols(); val height = small.rows()
            Imgproc.adaptiveThreshold(small, ink, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 12.0)
            val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centers)
            val keep = BooleanArray(count); val component = IntArray(5); var glyphs = 0
            for (i in 1 until count) {
                stats.get(i, 0, component)
                val x = component[0]; val y = component[1]; val w = component[2]; val h = component[3]
                if (component[4] >= 4 && h >= 3 && w < width * .2 && h < height * .18 &&
                    x > 5 && y > 5 && x + w < width - 5 && y + h < height - 5) { keep[i] = true; glyphs++ }
            }
            if (glyphs < 16) return 0.0
            val ids = IntArray(width * height).also { labels.get(0, 0, it) }
            val pixels = ByteArray(ids.size) { if (keep[ids[it]]) 1 else 0 }
            mask.create(height, width, CvType.CV_8U); mask.put(0, 0, pixels)
            labels.release(); ink.release(); stats.release(); centers.release(); small.release()
            val rows = FloatArray(height)
            fun score(angle: Double): Double {
                val transform = Imgproc.getRotationMatrix2D(Point(width / 2.0, height / 2.0), angle, 1.0)
                try { Imgproc.warpAffine(mask, rotated, transform, mask.size(), Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0)) }
                finally { transform.release() }
                Core.reduce(rotated, projection, 1, Core.REDUCE_SUM, CvType.CV_32F); projection.get(0, 0, rows)
                var energy = 0.0; var total = 0.0
                for (y in 5 until height - 5) { energy += rows[y] * rows[y]; total += rows[y] }
                return energy / max(1.0, total)
            }
            val original = score(0.0)
            var angle = 0.0; var best = original
            for (step in -14..14) {
                val candidate = step * .5; val quality = score(candidate)
                if (quality > best) { best = quality; angle = candidate }
            }
            val coarse = angle
            for (step in -5..5) {
                val candidate = coarse + step * .1; val quality = score(candidate)
                if (quality > best) { best = quality; angle = candidate }
            }
            if (abs(angle) < .25 || abs(angle) > 7.0 || best < original * 1.035 ||
                best < max(score(angle - 1), score(angle + 1)) * 1.025) return 0.0
            return angle
        } finally { listOf(gray, small, ink, labels, stats, centers, mask, rotated, projection).forEach { it.release() } }
    }
}
