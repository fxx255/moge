package com.moge.app.imaging.book

import kotlin.math.pow

/** Paper cleanup must not turn the antialiased edge of a glyph into a new black core. */
internal object DocumentInkTone {
    private val edgeTone = FloatArray(256) { value ->
        (255 * ((value - 8) / 232.0).coerceIn(0.0, 1.0).pow(.95)).toFloat()
    }
    private val paper = FloatArray(256) { smooth((it - 130) / 65f) }
    private val inkSupport = FloatArray(256) { smooth((145 - it) / 65f) }

    fun value(brightness: Int, nearbyMinimum: Int, chroma: Float): Float {
        val base = edgeTone[brightness]
        // Faint, isolated neutral marks are often show-through from the reverse
        // side. Leave edges beside real dark strokes and colored printing alone.
        val cleanup = paper[brightness] * (1 - inkSupport[nearbyMinimum]) * (1 - chroma)
        return base + (255 - base) * cleanup
    }

    private fun smooth(value: Float): Float = value.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
}
