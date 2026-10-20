package com.moge.app.imaging.book

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Estimate a warm shadow's channel attenuation from photographed paper at similar brightness.
 * A wide spatial blur mixes lit paper into a shadow edge and leaves an orange band. Using
 * brightness as the coordinate follows that edge without treating each colored panel as paper.
 * Only the small, ink-closed illumination model is changed; source pixels stay untouched.
 */
internal class WarmPaperShadowModel private constructor(
    private val white: Float,
    private val samples: List<Sample>,
) {
    private data class Sample(val red: Float, val greenRatio: Float, val blueRatio: Float)

    fun refine(paper: ByteArray, illumination: FloatArray) {
        require(paper.size / 3 * 4 == illumination.size)
        val greens = FloatArray(256)
        val blues = FloatArray(256)
        for (r in 60..255) {
            val upper = samples.indexOfFirst { it.red >= r }
            val right = if (upper < 0) samples.lastIndex else max(1, upper)
            val low = samples[right - 1]
            val high = samples[right]
            val fraction = ((r - low.red) / (high.red - low.red)).coerceIn(0f, 1f)
            greens[r] = low.greenRatio + (high.greenRatio - low.greenRatio) * fraction
            blues[r] = low.blueRatio + (high.blueRatio - low.blueRatio) * fraction
        }
        for (p in 0 until paper.size / 3) {
            val r = paper[p * 3].toInt() and 255
            val g = paper[p * 3 + 1].toInt() and 255
            val b = paper[p * 3 + 2].toInt() and 255
            // Fit only reliable paper, but do not cut holes in the correction at the
            // training hue/brightness thresholds. Printing keeps its difference from
            // the learned paper reference instead of becoming a new white reference.
            if (r < 60 || r < max(g, b) - 2) continue
            val blend = ((white * .95f - r) / 25f).coerceIn(0f, 1f)
            if (blend == 0f) continue
            val offset = p * 4
            val brightness = illumination[offset] // Warm paper's red channel is its brightest.
            illumination[offset + 1] += (brightness * greens[r] - illumination[offset + 1]) * blend
            illumination[offset + 2] += (brightness * blues[r] - illumination[offset + 2]) * blend
        }
    }

    companion object {
        private const val BIN_WIDTH = 8
        private const val BIN_COUNT = 256 / BIN_WIDTH

        fun fit(paper: ByteArray): WarmPaperShadowModel? {
            require(paper.size % 3 == 0)
            val pixels = paper.size / 3
            if (pixels < 256) return null
            val brightness = IntArray(256)
            val counts = IntArray(BIN_COUNT)
            val redSum = LongArray(BIN_COUNT)
            val greens = Array(BIN_COUNT) { IntArray(256) }
            val blues = Array(BIN_COUNT) { IntArray(256) }
            for (p in 0 until pixels) {
                val r = paper[p * 3].toInt() and 255
                val g = paper[p * 3 + 1].toInt() and 255
                val b = paper[p * 3 + 2].toInt() and 255
                brightness[maxOf(r, g, b)]++
                if (!isWarmPaper(r, g, b)) continue
                val bin = r / BIN_WIDTH
                counts[bin]++
                redSum[bin] += r.toLong()
                greens[bin][(g * 255f / r).roundToInt().coerceIn(0, 255)]++
                blues[bin][(b * 255f / r).roundToInt().coerceIn(0, 255)]++
            }
            val white = percentile(brightness, pixels, .85f).toFloat()
            if (white < 160f) return null
            val minimum = max(64, pixels / 2000)
            val samples = counts.indices.filter { counts[it] >= minimum }.map { bin ->
                // Upper quantiles prefer paper over colored printing at the same brightness.
                Sample(redSum[bin].toFloat() / counts[bin],
                    percentile(greens[bin], counts[bin], .7f) / 255f,
                    percentile(blues[bin], counts[bin], .7f) / 255f)
            }
            val dark = samples.filter { it.red < white * .8f }
            val light = samples.filter { it.red > white * .95f }
            val shadowPixels = counts.indices.sumOf { if ((it + 1) * BIN_WIDTH < white * .8f) counts[it] else 0 }
            // Uniform warm paper, blackboards and insufficient evidence retain the old model.
            if (samples.size < 3 || dark.isEmpty() || light.isEmpty() || shadowPixels < pixels / 50) return null
            if (light.map { it.blueRatio }.average() - dark.map { it.blueRatio }.average() < .04) return null
            return WarmPaperShadowModel(white, samples)
        }

        private fun isWarmPaper(r: Int, g: Int, b: Int): Boolean = r >= 60 &&
            r >= g - 2 && g >= b - 2 && r - g <= (g - b) * 1.6f + 2 && (r - b) < r * .45f

        private fun percentile(histogram: IntArray, count: Int, fraction: Float): Int {
            val rank = ceil(count * fraction).toInt().coerceAtLeast(1)
            var sum = 0
            for (value in histogram.indices) {
                sum += histogram[value]
                if (sum >= rank) return value
            }
            return 255
        }
    }
}
