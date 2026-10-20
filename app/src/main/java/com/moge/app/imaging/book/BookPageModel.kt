package com.moge.app.imaging.book

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Optional book-photo corrections. All morphology is confined to the small paper model. */
internal object BookPageModel {
    /** Retain long, soft gutters in the light field, rather than mistaking them for ink. */
    fun refineGutter(rgb: Mat, background: Mat, field: Mat) {
        val w = rgb.cols(); val h = rgb.rows()
        if (w < 32 || h < 64) return
        val vertical = Mat(); val soft = Mat(); val mask = Mat(); val labels = Mat()
        val stats = Mat(); val centroids = Mat(); val local = Mat()
        val longKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, (max(31, (h * .12).roundToInt()) or 1).toDouble()))
        val narrow = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 1.0))
        val shoulder = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(7.0, 1.0))
        val localKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, (max(9, (h * .035).roundToInt()) or 1).toDouble()))
        try {
            Imgproc.morphologyEx(rgb, vertical, Imgproc.MORPH_CLOSE, longKernel)
            vertical.convertTo(soft, CvType.CV_32F)
            Imgproc.GaussianBlur(soft, soft, Size(), .65)
            val v = FloatArray(w * h * 3).also { soft.get(0, 0, it) }
            val bg = ByteArray(w * h * 3).also { background.get(0, 0, it) }
            val candidate = ByteArray(w * h)
            for (p in candidate.indices) {
                val x = p % w; val i = p * 3
                if (x >= w * .13 && x < w * .87) continue
                val hi = maxOf(v[i], v[i + 1], v[i + 2]); val lo = minOf(v[i], v[i + 1], v[i + 2])
                val paper = maxOf(bg[i].toInt() and 255, bg[i + 1].toInt() and 255, bg[i + 2].toInt() and 255)
                // Reject very dark printed rules, narrow rules, and saturated colored marks.
                if (hi >= 45 && hi < paper * .84 && hi - lo < hi * .4 && v[i] - v[i + 1] < hi * .15) candidate[p] = 1
            }
            // A printed gray frame can also be long and near a margin. Require a gradual
            // shoulder on at least one side; a hard step on both sides is not a gutter.
            val raw = ByteArray(w * h * 3).also { rgb.get(0, 0, it) }
            fun value(x: Int, y: Int): Int {
                val i = (y * w + x) * 3
                return maxOf(raw[i].toInt() and 255, raw[i + 1].toInt() and 255, raw[i + 2].toInt() and 255)
            }
            fun gradient(from: Int, to: Int, y: Int): Int {
                var largest = 0
                for (x in max(1, from)..min(w - 1, to)) largest = max(largest, abs(value(x, y) - value(x - 1, y)))
                return largest
            }
            for (y in 0 until h) {
                var x = 0
                while (x < w) {
                    if (candidate[y * w + x].toInt() == 0) { x++; continue }
                    val start = x
                    while (x < w && candidate[y * w + x].toInt() != 0) x++
                    val end = x - 1; val p = (y * w + (start + end) / 2) * 3
                    val paper = maxOf(bg[p].toInt() and 255, bg[p + 1].toInt() and 255, bg[p + 2].toInt() and 255)
                    if (min(gradient(start - 4, start + 1, y), gradient(end - 1, end + 5, y)) > paper * .25) {
                        for (xx in start..end) candidate[y * w + xx] = 0
                    }
                }
            }
            mask.create(h, w, CvType.CV_8U); mask.put(0, 0, candidate)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, narrow)
            val components = Imgproc.connectedComponentsWithStats(mask, labels, stats, centroids)
            val keep = BooleanArray(components)
            for (i in 1 until components) keep[i] = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0] > h * .5 &&
                stats.get(i, Imgproc.CC_STAT_AREA)[0] > h * 1.5
            if (keep.none { it }) return
            val ids = IntArray(w * h).also { labels.get(0, 0, it) }
            for (p in candidate.indices) candidate[p] = if (keep[ids[p]]) 1 else 0
            mask.put(0, 0, candidate); mask.convertTo(mask, CvType.CV_32F)
            Imgproc.dilate(mask, mask, shoulder); Imgproc.GaussianBlur(mask, mask, Size(), .7)
            // A shorter model follows the detected gutter's bend. It still bridges small ink.
            Imgproc.morphologyEx(rgb, local, Imgproc.MORPH_CLOSE, localKernel)
            local.convertTo(local, CvType.CV_32F); Imgproc.GaussianBlur(local, local, Size(), .5)
            val m = FloatArray(w * h).also { mask.get(0, 0, it) }
            local.get(0, 0, v)
            val values = FloatArray(w * h * 4).also { field.get(0, 0, it) }
            for (p in m.indices) for (c in 0..2) values[p * 4 + c] =
                values[p * 4 + c] * (1 - m[p]) + v[p * 3 + c] * m[p]
            field.put(0, 0, values)
        } finally {
            listOf(vertical, soft, mask, labels, stats, centroids, local, longKernel, narrow, shoulder, localKernel).forEach { it.release() }
        }
    }

}
