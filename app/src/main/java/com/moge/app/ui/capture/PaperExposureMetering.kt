package com.moge.app.ui.capture

import kotlin.math.roundToInt

internal data class PaperExposureSample(val x: Float, val y: Float, val luminance: Int)

/** Paper needs a positive bias; shaded paper gets extra light, bounded to avoid washing out ink. */
internal fun paperMeteringBiasEv(luminance: Int?): Float {
    val shadow = luminance?.let { ((180 - it.coerceIn(0, 255)) / 100f).coerceIn(0f, 1f) } ?: 0f
    return PAPER_METERING_BIAS_EV + shadow * (MAX_PAPER_METERING_BIAS_EV - PAPER_METERING_BIAS_EV)
}

internal const val PAPER_METERING_BIAS_EV = 1f
internal const val MAX_PAPER_METERING_BIAS_EV = 5f / 3f

/** The upper quartile of a small patch describes the paper behind printed strokes. */
internal fun samplePaperLuminance(luma: ByteArray, width: Int, height: Int, x: Float, y: Float): Int? {
    if (width <= 0 || height <= 0 || luma.size != width * height ||
        !x.isFinite() || !y.isFinite() || x !in 0f..1f || y !in 0f..1f) return null
    val cx = (x * (width - 1)).roundToInt()
    val cy = (y * (height - 1)).roundToInt()
    val rx = (width * .05f).roundToInt().coerceAtLeast(1)
    val ry = (height * .05f).roundToInt().coerceAtLeast(1)
    val values = ArrayList<Int>()
    for (py in (cy - ry).coerceAtLeast(0)..(cy + ry).coerceAtMost(height - 1))
        for (px in (cx - rx).coerceAtLeast(0)..(cx + rx).coerceAtMost(width - 1))
            values += luma[py * width + px].toInt() and 255
    values.sort()
    return values[((values.size - 1) * .75f).roundToInt()]
}

/** Meter shaded paper inside its detected bounds, excluding isolated ink and the surrounding desk. */
internal fun paperShadowSample(luma: ByteArray, width: Int, height: Int,
    left: Int, top: Int, right: Int, bottom: Int, paperLevel: Int): PaperExposureSample? {
    val samples = buildList {
        for (row in 0..3) for (column in 0..3) {
            val x = (left + (column + .5f) * (right - left + 1) / 4) / width
            val y = (top + (row + .5f) * (bottom - top + 1) / 4) / height
            val light = samplePaperLuminance(luma, width, height, x, y) ?: continue
            if (light >= maxOf(40, (paperLevel * .35f).roundToInt())) add(PaperExposureSample(x, y, light))
        }
    }.sortedBy { it.luminance }
    // A low percentile favors a shaded region without chasing one anomalous black patch.
    return samples.getOrNull(((samples.size - 1) * .2f).roundToInt().coerceAtLeast(0))
}
