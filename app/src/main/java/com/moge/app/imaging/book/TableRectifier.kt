package com.moge.app.imaging.book

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Conservative ruled-table model. Ordinary text/photos must never become a mesh. */
internal object TableRectifier {
    data class Tracks(val samples: FloatArray, val targets: FloatArray, val paths: List<FloatArray>) {
        private fun upperBand(position: Float): Int {
            var low = 0; var high = targets.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (targets[middle] < position) low = middle + 1 else high = middle
            }
            return low
        }

        internal fun band(position: Float): FloatArray {
            if (position <= 0f || position >= 1f) return FloatArray(samples.size) { position }
            val upper = upperBand(position); val lower = upper - 1
            val start = if (lower < 0) 0f else targets[lower]
            val end = if (upper == targets.size) 1f else targets[upper]
            val fraction = (position - start) / (end - start).coerceAtLeast(.00001f)
            // Blend the 25 path samples once per mesh row/column, rather than per vertex.
            return FloatArray(samples.size) { sample ->
                val a = if (lower < 0) 0f else paths[lower][sample]
                val b = if (upper == targets.size) 1f else paths[upper][sample]
                a + (b - a) * fraction
            }
        }

        fun observed(position: Float, across: Float): Float {
            if (position <= 0f || position >= 1f) return position
            // Targets are strictly increasing. Mesh generation queries this hundreds of
            // thousands of times; a linear scan of all table rows dominated first-use work.
            val upper = upperBand(position)
            val lower = upper - 1
            val start = if (lower < 0) 0f else targets[lower]
            val end = if (upper == targets.size) 1f else targets[upper]
            val a = if (lower < 0) 0f else interpolate(samples, paths[lower], across)
            val b = if (upper == targets.size) 1f else interpolate(samples, paths[upper], across)
            return a + (b - a) * ((position - start) / (end - start).coerceAtLeast(0.00001f))
        }
    }

    data class Grid(val horizontal: Tracks, val vertical: Tracks) : PageWarp {
        fun source(x: Float, y: Float): Pair<Float, Float> {
            val sx = vertical.observed(x, y)
            val sy = horizontal.observed(y, sx)
            return vertical.observed(x, sy) to sy
        }

        override fun sourceMesh(columns: Int, rows: Int): Pair<FloatArray, FloatArray> {
            require(columns >= 2 && rows >= 2)
            val xBands = Array(columns) { vertical.band(it.toFloat() / (columns - 1)) }
            val yBands = Array(rows) { horizontal.band(it.toFloat() / (rows - 1)) }
            val xs = FloatArray(columns * rows); val ys = FloatArray(xs.size)
            for (j in 0 until rows) {
                val y = j.toFloat() / (rows - 1)
                for (i in 0 until columns) {
                    val initialX = interpolate(vertical.samples, xBands[i], y)
                    val sy = interpolate(horizontal.samples, yBands[j], initialX)
                    val index = j * columns + i
                    xs[index] = interpolate(vertical.samples, xBands[i], sy)
                    ys[index] = sy
                }
            }
            return xs to ys
        }
    }

    /** Input is a perspective-corrected, modest-size image. No full-size output is allocated here. */
    fun analyze(image: Mat): Grid? {
        if (image.cols() < 120 || image.rows() < 120) return null
        val gray = Mat(); val ink = Mat(); val horizontal = Mat(); val vertical = Mat(); val transposed = Mat()
        val hk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(12, image.cols() / 28).toDouble(), 1.0))
        val vk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, max(18, image.rows() / 28).toDouble()))
        try {
            when (image.channels()) {
                1 -> image.copyTo(gray)
                4 -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> Imgproc.cvtColor(image, gray, Imgproc.COLOR_RGB2GRAY)
            }
            Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 31, 15.0)
            Imgproc.morphologyEx(ink, horizontal, Imgproc.MORPH_OPEN, hk)
            Imgproc.morphologyEx(ink, vertical, Imgproc.MORPH_OPEN, vk)
            org.opencv.core.Core.transpose(vertical, transposed)
            val rows = track(horizontal) ?: return null
            val columns = track(transposed) ?: return null
            if (rows.paths.size < 6 || columns.paths.size < 3) return null
            return Grid(rows, columns)
        } finally { listOf(gray, ink, horizontal, vertical, transposed, hk, vk).forEach { it.release() } }
    }

    private fun track(mask: Mat): Tracks? {
        val w = mask.cols(); val h = mask.rows()
        fun response(center: Int, radius: Int): FloatArray {
            val left = max(0, center - radius); val right = min(w, center + radius + 1)
            val window = mask.colRange(left, right); val sums = Mat()
            // Morphology preserves a 0/255 mask. Native integer row sums give exactly
            // the previous nonzero counts, avoiding millions of interpreted byte visits.
            val totals = IntArray(h)
            try { Core.reduce(window, sums, 1, Core.REDUCE_SUM, CvType.CV_32S); sums.get(0, 0, totals) }
            finally { window.release(); sums.release() }
            val raw = FloatArray(h) { (totals[it] / 255).toFloat() / (right - left) }
            return FloatArray(h) { y -> (raw[max(0, y - 1)] + 2 * raw[y] + raw[min(h - 1, y + 1)]) / 4 }
        }
        val middle = response(w / 2, (w * .07f).roundToInt())
        val seeds = mutableListOf<Float>()
        var y = 0
        while (y < h) {
            if (middle[y] <= .38f) { y++; continue }
            var weighted = 0f; var total = 0f
            while (y < h && middle[y] > .38f) { weighted += y * middle[y]; total += middle[y]; y++ }
            seeds += weighted / total
        }
        if (seeds.size < 3) return null
        val gap = median(seeds.zipWithNext { a, b -> b - a })
        if (gap < 5f) return null
        val radius = max(3, min(gap * .35f, h * .025f).toInt())
        val samples = FloatArray(25) { .035f + .93f * it / 24 }
        val responses = samples.map { response((it * (w - 1)).roundToInt(), max(6, (w * .016f).toInt())) }
        val paths = mutableListOf<FloatArray>()
        for (seed in seeds) {
            val path = FloatArray(25); path[12] = seed
            var supported = 0
            for (direction in listOf(-1, 1)) {
                var j = 12 + direction
                while (j in path.indices) {
                    val previous = path[j - direction]
                    val low = max(0, previous.roundToInt() - radius)
                    val high = min(h - 1, previous.roundToInt() + radius)
                    val response = responses[j]
                    var peak = low
                    for (k in low..high) if (response[k] > response[peak]) peak = k
                    if (response[peak] > .3f) {
                        var weighted = 0f; var total = 0f
                        for (k in max(low, peak - 2)..min(high, peak + 2)) { weighted += k * response[k]; total += response[k] }
                        path[j] = weighted / total
                        supported++
                    } else path[j] = previous
                    j += direction
                }
            }
            if (supported < 21) continue
            // Reject duplicate/branching tracks (for example a word underline beside a grid line).
            if (paths.lastOrNull()?.let { prior -> path.indices.any { path[it] - prior[it] < gap * .45f } } == true) continue
            val smooth = FloatArray(25) { j ->
                var sum = 0f; var weight = 0f
                for (delta in -2..2) { val k = (j + delta).coerceIn(0, 24); val v = (3 - abs(delta)).toFloat(); sum += path[k] * v; weight += v }
                sum / weight / (h - 1)
            }
            // Keep raw pixel coordinates for comparison with the next candidate.
            paths += FloatArray(25) { smooth[it] * (h - 1) }
        }
        if (paths.size < 3) return null
        val normalized = paths.map { path -> FloatArray(25) { path[it] / (h - 1) } }
        val targets = normalized.map { median(it.toList()) }.toFloatArray()
        if (targets.last() - targets.first() < .65f) return null
        if (targets.asList().zipWithNext().any { (a, b) -> b - a < .002f }) return null
        return Tracks(samples, targets, normalized)
    }

    private fun interpolate(xs: FloatArray, values: FloatArray, x: Float): Float {
        if (x <= xs.first()) return values.first()
        if (x >= xs.last()) return values.last()
        val i = ((x - xs.first()) / (xs.last() - xs.first()) * (xs.size - 1)).toInt().coerceIn(0, xs.size - 2)
        val t = ((x - xs[i]) / (xs[i + 1] - xs[i])).coerceIn(0f, 1f)
        return values[i] * (1 - t) + values[i + 1] * t
    }

    private fun median(values: List<Float>): Float = values.sorted().let { it[it.size / 2] }
}
