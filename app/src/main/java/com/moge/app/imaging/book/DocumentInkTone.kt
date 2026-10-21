package com.moge.app.imaging.book

import kotlin.math.pow
import kotlin.math.roundToInt

/** Paper cleanup must not turn the antialiased edge of a glyph into a new black core. */
internal object DocumentInkTone {
    private val edgeTone = FloatArray(256) { value ->
        (255 * ((value - 8) / 232.0).coerceIn(0.0, 1.0).pow(.95)).toFloat()
    }
    private val paper = FloatArray(256) { smooth((it - 130) / 65f) }
    private val inkSupport = FloatArray(256) { smooth((145 - it) / 65f) }
    // Neutral pixels have identical output channels. Cache the existing curve for
    // every brightness/support pair (256 KiB) instead of repeating it per pixel.
    private val neutralColors = IntArray(256 * 256) { index ->
        val gray = value(index ushr 8, index and 255, 0f).roundToInt().coerceIn(0, 255)
        (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
    }

    fun neutralArgb(brightness: Int, nearbyMinimum: Int): Int =
        neutralColors[(brightness shl 8) or nearbyMinimum]

    fun value(brightness: Int, nearbyMinimum: Int, chroma: Float): Float {
        val base = edgeTone[brightness]
        // Faint, isolated neutral marks are often show-through from the reverse
        // side. Leave edges beside real dark strokes and colored printing alone.
        val cleanup = paper[brightness] * (1 - inkSupport[nearbyMinimum]) * (1 - chroma)
        return base + (255 - base) * cleanup
    }

    private fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
}
