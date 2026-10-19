package com.moge.app.imaging.book

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** Optional framed-book model. Reject unsupported pages instead of inventing their geometry. */
internal object BookDewarper {
    data class Analysis(val warp: PageWarp, val frameLines: Int, val textLines: Int)
    private data class Curve(val left: Int, val right: Int, val target: Float, val path: FloatArray)
    private data class Glyph(val x: Float, val bottom: Float, val width: Int)
    private const val SAMPLES = 65
    private val samples get() = FloatArray(SAMPLES) { it.toFloat() / (SAMPLES - 1) }

    fun analyze(image: Mat): Analysis? {
        if (image.cols() < 180 || image.rows() < 300 || image.channels() < 3) return null
        // Renderer already uses a small analysis image; keep direct callers bounded too.
        val small = Mat()
        val ratio = min(1.0, 1400.0 / max(image.cols(), image.rows()))
        try {
            if (ratio < 1) Imgproc.resize(image, small, Size(), ratio, ratio, Imgproc.INTER_AREA) else image.copyTo(small)
            val pixels = ByteArray(small.rows() * small.cols() * small.channels()).also { small.get(0, 0, it) }
            val histogram = IntArray(256)
            for (i in pixels.indices step small.channels()) histogram[maxOf(pixels[i].toInt() and 255,
                pixels[i + 1].toInt() and 255, pixels[i + 2].toInt() and 255)]++
            var rank = 0; var brightness = 0
            while (brightness < 255 && rank < small.total() * .65) rank += histogram[brightness++]
            if (brightness < 140) return null
            val curves = frames(small)
            if (curves.size < 6 || curves.size % 2 != 0) return null
            val w = small.cols(); val h = small.rows()
            if (curves.last().target - curves.first().target < .55f) return null
            // Require several distinct boxes distributed over the page, not text underlines.
            for (i in curves.indices step 2) {
                val a = curves[i]; val b = curves[i + 1]
                if (b.target - a.target !in .025f.. .20f || min(a.right, b.right) - max(a.left, b.left) < w * .35) return null
                // Tinted panels have sustained interior color. Rows of colored letters
                // should not masquerade as a stack of boxes on an otherwise white page.
                val first = (max(a.left, b.left) + w * .08).toInt().coerceIn(0, w - 1)
                val last = (min(a.right, b.right) - w * .05).toInt().coerceIn(first, w - 1)
                var inside = 0.0; var outside = 0.0; var count = 0
                fun chroma(x: Int, y: Int): Int {
                    val offset = (y.coerceIn(0, h - 1) * w + x) * small.channels()
                    val r = pixels[offset].toInt() and 255; val g = pixels[offset + 1].toInt() and 255
                    val blue = pixels[offset + 2].toInt() and 255
                    return maxOf(r - g, blue - r, g - max(r, blue), 0)
                }
                for (x in first..last step 5) {
                    val pos = x.toFloat() / (w - 1) * (SAMPLES - 1)
                    val index = pos.toInt().coerceAtMost(SAMPLES - 2); val fraction = pos - index
                    fun at(curve: Curve) = ((curve.path[index] * (1 - fraction) + curve.path[index + 1] * fraction) * (h - 1)).roundToInt()
                    val top = at(a); val bottom = at(b)
                    inside += chroma(x, (top + bottom) / 2)
                    outside += (chroma(x, top - 8) + chroma(x, bottom + 8)) / 2.0; count++
                }
                if (count == 0 || (inside - outside) / count < 3.0) return null
            }
            val horizontal = TableRectifier.Tracks(samples, curves.map { it.target }.toFloatArray(), curves.map { it.path })
            val bottoms = curves.filterIndexed { index, _ -> index % 2 == 1 }
            val xs = bottoms.map { it.left.toFloat() / (w - 1) }.toFloatArray()
            val targetLeft = median(xs)
            if (targetLeft !in .08f.. .65f || xs.max() - xs.min() > .25f) return null
            val leftPath = FloatArray(SAMPLES) { index ->
                interpolate(bottoms.map { it.target }.toFloatArray(), xs, index.toFloat() / (SAMPLES - 1))
            }
            val vertical = TableRectifier.Tracks(samples, floatArrayOf(targetLeft), listOf(leftPath))
            val base = TableRectifier.Grid(horizontal, vertical)
            if (!valid(base)) return null
            val correction = textCorrection(small, base, curves.map { it.target })
            val warp = if (correction == null) base else object : PageWarp {
                override fun sourceMesh(columns: Int, rows: Int): Pair<FloatArray, FloatArray> {
                    val xx = FloatArray(columns * rows); val yy = FloatArray(xx.size)
                    for (j in 0 until rows) for (i in 0 until columns) {
                        val x = i.toFloat() / (columns - 1); val y = j.toFloat() / (rows - 1)
                        val point = base.source(x, correction.observed(y, x))
                        xx[j * columns + i] = point.first; yy[j * columns + i] = point.second
                    }
                    return xx to yy
                }
            }
            if (!valid(warp)) return Analysis(base, curves.size, 0)
            return Analysis(warp, curves.size, correction?.targets?.size?.minus(curves.size) ?: 0)
        } finally { small.release() }
    }

    private fun frames(image: Mat): List<Curve> {
        val w = image.cols(); val h = image.rows(); val channels = image.channels()
        val bytes = ByteArray(w * h * channels).also { image.get(0, 0, it) }
        val chroma = Mat(h, w, CvType.CV_32F); val gradient = Mat(); val mask = Mat(); val joined = Mat()
        val labels = Mat(); val stats = Mat(); val centers = Mat()
        val hk = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(max(13, w / 45).toDouble(), 1.0))
        val bridge = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(21.0, 3.0))
        try {
            // Channel contrast ignores neutral shadows; it can follow red or blue borders.
            val color = FloatArray(w * h) { p ->
                val i = p * channels; val r = bytes[i].toInt() and 255
                val g = bytes[i + 1].toInt() and 255; val b = bytes[i + 2].toInt() and 255
                // Red-green / blue-red differences suppress warm neutral paper. Using
                // max-min alone makes black rules on cream paper produce false edges.
                maxOf(r - g, b - r, g - max(r, b), 0) / 255f
            }
            chroma.put(0, 0, color); Imgproc.GaussianBlur(chroma, chroma, Size(), .7)
            Imgproc.Sobel(chroma, gradient, CvType.CV_32F, 0, 1, 3, .25)
            val response = FloatArray(w * h).also { gradient.get(0, 0, it) }
            val binary = ByteArray(response.size) { if (abs(response[it]) > .014f) 255.toByte() else 0 }
            mask.create(h, w, CvType.CV_8U); mask.put(0, 0, binary)
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, hk)
            Imgproc.morphologyEx(mask, joined, Imgproc.MORPH_CLOSE, bridge)
            val count = Imgproc.connectedComponentsWithStats(joined, labels, stats, centers)
            val ids = IntArray(w * h).also { labels.get(0, 0, it) }
            val curves = mutableListOf<Curve>()
            for (id in 1 until count) {
                val x = stats.get(id, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(id, Imgproc.CC_STAT_TOP)[0].toInt()
                val width = stats.get(id, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val height = stats.get(id, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                if (width < w * .35 || height > h * .10) continue
                val xx = mutableListOf<Double>(); val yy = mutableListOf<Double>()
                for (col in x until x + width) {
                    var total = 0.0; var support = 0
                    for (row in y until y + height) if (ids[row * w + col] == id) { total += row; support++ }
                    if (support > 0) { xx += col.toDouble() / w; yy += total / support }
                }
                if (xx.size < width * .85) continue
                val coefficients = fit(xx, yy, 3) ?: continue
                val errors = xx.indices.map { abs(evaluate(coefficients, xx[it]) - yy[it]).toFloat() }.toFloatArray()
                if (median(errors) > 1.5f) continue
                val right = x + width - 1
                val path = FloatArray(w) { evaluate(coefficients, it.coerceIn(x, right).toDouble() / w).toFloat() }
                // Labels interrupt top-left borders. A limited continuation is safer than
                // following their letter strokes. Clamp beyond the short supported margin.
                val start = max(0, x - (w * .15).roundToInt())
                for (col in start until x) path[col] = evaluate(coefficients, col.toDouble() / w).toFloat()
                for (col in 0 until start) path[col] = path[start]
                extendRight(path, right, response, w, h)
                val smooth = Mat(1, w, CvType.CV_32F)
                try {
                    smooth.put(0, 0, path); Imgproc.GaussianBlur(smooth, smooth, Size(), max(2.0, w * .007))
                    smooth.get(0, 0, path)
                } finally { smooth.release() }
                if (path.any { !it.isFinite() || it < 0 || it >= h }) continue
                val sampled = FloatArray(SAMPLES) { path[(it.toDouble() * (w - 1) / (SAMPLES - 1)).roundToInt()] / (h - 1) }
                val target = median(path.sliceArray((w * .30).toInt() until (w * .85).toInt())) / (h - 1)
                curves += Curve(x, right, target, sampled)
            }
            val sorted = curves.sortedBy { it.target }
            if (sorted.zipWithNext().any { (a, b) -> b.target - a.target < .012f }) return emptyList()
            return sorted
        } finally { listOf(chroma, gradient, mask, joined, labels, stats, centers, hk, bridge).forEach { it.release() } }
    }

    private fun extendRight(path: FloatArray, start: Int, response: FloatArray, w: Int, h: Int) {
        if (start >= w - 1) return
        var last = path[start]; var slope = (path[start] - path[max(0, start - 3)]) / 3
        var missed = 0; var previous = start
        for (col in start + 1 until w step 4) {
            val predicted = last + slope * 4
            val low = max(1, predicted.roundToInt() - 4); val high = min(h - 2, predicted.roundToInt() + 4)
            if (low > high) break
            fun signal(row: Int): Float {
                var total = 0f; val left = max(0, col - 2); val right = min(w - 1, col + 2)
                for (x in left..right) total += abs(response[row * w + x])
                return total / (right - left + 1)
            }
            var peak = low; var best = -Float.MAX_VALUE
            for (row in low..high) {
                val score = signal(row) - .0015f * abs(row - predicted)
                if (score > best) { best = score; peak = row }
            }
            val next = if (signal(peak) > .008f) {
                missed = 0; var total = 0f; var weight = 0f
                for (row in peak - 1..peak + 1) { val v = signal(row); total += row * v; weight += v }
                total / max(.0001f, weight)
            } else { missed++; predicted }
            if (missed > 5) break
            slope = (.7f * slope + .3f * (next - last) / 4).coerceIn(-1.2f, 1.2f)
            for (x in previous + 1..col) path[x] = last + (next - last) * (x - previous) / (col - previous)
            previous = col; last = next
        }
        for (x in previous + 1 until w) path[x] = last
    }

    /** Text only refines an already-supported page model. Formulas/short rows can abstain. */
    private fun textCorrection(image: Mat, base: TableRectifier.Grid, frames: List<Float>): TableRectifier.Tracks? {
        val w = image.cols(); val h = image.rows(); val mapX = Mat(h, w, CvType.CV_32F); val mapY = Mat(h, w, CvType.CV_32F)
        val flat = Mat(); val gray = Mat(); val ink = Mat(); val labels = Mat(); val stats = Mat(); val centers = Mat()
        try {
            // Analysis needs the same smooth shape, not millions of Kotlin mesh queries.
            // Upsample a small coordinate field natively, with endpoint-aligned sampling.
            val edge = 65
            val (xs, ys) = base.sourceMesh(edge, edge)
            for (i in xs.indices) { xs[i] *= w - 1; ys[i] *= h - 1 }
            val coarseX = Mat(edge, edge, CvType.CV_32F); val coarseY = Mat(edge, edge, CvType.CV_32F)
            val transform = Mat(2, 3, CvType.CV_64F)
            try {
                coarseX.put(0, 0, xs); coarseY.put(0, 0, ys)
                transform.put(0, 0, (edge - 1).toDouble() / (w - 1), 0.0, 0.0, 0.0, (edge - 1).toDouble() / (h - 1), 0.0)
                Imgproc.warpAffine(coarseX, mapX, transform, image.size(), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
                Imgproc.warpAffine(coarseY, mapY, transform, image.size(), Imgproc.INTER_LINEAR or Imgproc.WARP_INVERSE_MAP, Core.BORDER_REPLICATE)
            } finally { coarseX.release(); coarseY.release(); transform.release() }
            Imgproc.remap(image, flat, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_REPLICATE)
            Imgproc.cvtColor(flat, gray, if (flat.channels() == 4) Imgproc.COLOR_RGBA2GRAY else Imgproc.COLOR_RGB2GRAY)
            Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 15.0)
            val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centers)
            val glyphs = mutableListOf<Glyph>(); val histogram = FloatArray(h)
            for (i in 1 until count) {
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt(); val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val width = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt(); val height = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                if (width < 3 || width > w * .065 || height < 6 || height > h * .025 ||
                    stats.get(i, Imgproc.CC_STAT_AREA)[0] < 12 || x < w * .25 || x + width > w * .985 || y + height >= h) continue
                glyphs += Glyph(centers.get(i, 0)[0].toFloat(), (y + height).toFloat(), width)
                histogram[y + height] += min(width, 16)
            }
            val histogramMat = Mat(h, 1, CvType.CV_32F)
            try {
                histogramMat.put(0, 0, histogram); Imgproc.GaussianBlur(histogramMat, histogramMat, Size(1.0, 7.0), 1.3)
                histogramMat.get(0, 0, histogram)
            } finally { histogramMat.release() }
            val targets = frames.toMutableList(); val paths = frames.map { level -> FloatArray(SAMPLES) { level } }.toMutableList()
            for (y in 3 until h - 3) {
                if (histogram[y] < w * .04 || (y - 3..y + 3).any { histogram[it] > histogram[y] }) continue
                val chosen = glyphs.filter { abs(it.bottom - y) <= 4 }
                if (chosen.size < 12) continue
                val left = chosen.minOf { it.x }; val right = chosen.maxOf { it.x }
                if (right - left < w * .38) continue
                val xx = chosen.map { it.x.toDouble() / w }; val yy = chosen.map { it.bottom.toDouble() }
                var coefficients = fit(xx, yy, 2) ?: continue
                repeat(4) {
                    val weights = xx.indices.map { min(1.0, 2 / max(.001, abs(evaluate(coefficients, xx[it]) - yy[it]))) }
                    coefficients = fit(xx, yy, 2, weights) ?: coefficients
                }
                val error = xx.indices.map { abs(evaluate(coefficients, xx[it]) - yy[it]).toFloat() }.toFloatArray()
                if (median(error) > 1.5) continue
                val observed = FloatArray(SAMPLES) { i ->
                    evaluate(coefficients, (i.toFloat() / (SAMPLES - 1) * w).coerceIn(left, right).toDouble() / w).toFloat() / (h - 1)
                }
                val level = median(observed)
                if (targets.any { abs(it - level) < .012f }) continue
                // Only a small, well-supported residual. Do not force dissimilar glyph bottoms.
                if (observed.max() - observed.min() > .007f) continue
                targets += level; paths += FloatArray(SAMPLES) { level + .75f * (observed[it] - level) }
            }
            if (targets.size == frames.size) return null
            val order = targets.indices.sortedBy { targets[it] }
            return TableRectifier.Tracks(samples, order.map { targets[it] }.toFloatArray(), order.map { paths[it] })
        } finally { listOf(mapX, mapY, flat, gray, ink, labels, stats, centers).forEach { it.release() } }
    }

    private fun valid(warp: PageWarp): Boolean {
        val (xx, yy) = warp.sourceMesh(65, 65)
        for (j in 0 until 64) for (i in 0 until 64) {
            val k = j * 65 + i
            val dx = (xx[k + 1] - xx[k]) * 64; val dy = (yy[k + 65] - yy[k]) * 64
            val crossX = (xx[k + 65] - xx[k]) * 64; val crossY = (yy[k + 1] - yy[k]) * 64
            if (!dx.isFinite() || !dy.isFinite() || dx !in .45f..1.8f || dy !in .45f..1.8f || dx * dy - crossX * crossY < .3f) return false
        }
        return true
    }

    private fun fit(x: List<Double>, y: List<Double>, degree: Int, weights: List<Double>? = null): DoubleArray? {
        val a = Mat(x.size, degree + 1, CvType.CV_64F); val b = Mat(x.size, 1, CvType.CV_64F); val result = Mat()
        try {
            val values = DoubleArray(x.size * (degree + 1)); val targets = DoubleArray(x.size)
            for (i in x.indices) {
                val weight = weights?.get(i) ?: 1.0; var power = 1.0
                for (j in 0..degree) { values[i * (degree + 1) + j] = power * weight; power *= x[i] }
                targets[i] = y[i] * weight
            }
            a.put(0, 0, *values); b.put(0, 0, *targets)
            if (!Core.solve(a, b, result, Core.DECOMP_SVD)) return null
            return DoubleArray(degree + 1).also { result.get(0, 0, it) }
        } finally { a.release(); b.release(); result.release() }
    }

    private fun evaluate(coefficients: DoubleArray, x: Double): Double {
        var value = 0.0
        for (i in coefficients.indices.reversed()) value = value * x + coefficients[i]
        return value
    }
    private fun median(values: FloatArray): Float = values.sortedArray()[values.size / 2]
    private fun interpolate(xs: FloatArray, ys: FloatArray, x: Float): Float {
        if (x <= xs.first()) return ys.first()
        if (x >= xs.last()) return ys.last()
        val upper = xs.indexOfFirst { it >= x }; val t = (x - xs[upper - 1]) / (xs[upper] - xs[upper - 1])
        return ys[upper - 1] * (1 - t) + ys[upper] * t
    }
}
