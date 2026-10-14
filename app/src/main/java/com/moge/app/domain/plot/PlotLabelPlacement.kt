package com.moge.app.domain.plot

import kotlin.math.abs

data class LabelRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun overlaps(other: LabelRect) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    fun inside(other: LabelRect) = left >= other.left && right <= other.right && top >= other.top && bottom <= other.bottom
    fun padded(p: Float) = LabelRect(left - p, top - p, right + p, bottom + p)
}

data class LabelObstacleLine(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

/** Segment/rectangle intersection, including vertical axes and sloping decision boundaries. */
fun LabelRect.intersects(line: LabelObstacleLine): Boolean {
    var lo = 0f; var hi = 1f
    val dx = line.x1 - line.x0; val dy = line.y1 - line.y0
    for ((p, q) in listOf(-dx to (line.x0 - left), dx to (right - line.x0),
        -dy to (line.y0 - top), dy to (bottom - line.y0))) {
        if (abs(p) < 1e-7f) { if (q < 0) return false; continue }
        val t = q / p
        if (p < 0) lo = maxOf(lo, t) else hi = minOf(hi, t)
        if (lo > hi) return false
    }
    return true
}

/** Deterministic nearest free position; null requests a margin callout instead of an overlapping label. */
fun placePlotLabel(anchorX: Float, anchorY: Float, width: Float, height: Float,
    bounds: LabelRect, occupied: List<LabelRect>, lines: List<LabelObstacleLine>, gap: Float): LabelRect? {
    if (width > bounds.width || height > bounds.height) return null
    for (ring in 0..6) {
        val offset = gap + ring * (height + gap)
        val candidates = listOf(
            anchorX + offset to anchorY - height - offset,
            anchorX - width - offset to anchorY - height - offset,
            anchorX + offset to anchorY + offset,
            anchorX - width - offset to anchorY + offset,
            anchorX - width / 2 to anchorY - height - offset,
            anchorX - width / 2 to anchorY + offset,
        )
        for ((x, y) in candidates) {
            val box = LabelRect(x.coerceIn(bounds.left, bounds.right - width),
                y.coerceIn(bounds.top, bounds.bottom - height),
                x.coerceIn(bounds.left, bounds.right - width) + width,
                y.coerceIn(bounds.top, bounds.bottom - height) + height)
            val padded = box.padded(gap * .35f)
            if (occupied.none(padded::overlaps) && lines.none(padded::intersects)) return box
        }
    }
    val step = height + gap
    val grid = mutableListOf<Pair<Float, Float>>()
    var y = bounds.top
    while (y + height <= bounds.bottom) {
        var x = bounds.left
        while (x + width <= bounds.right) { grid += x to y; x += maxOf(step, width / 2) }
        y += step
    }
    for ((x, y) in grid.sortedBy { (it.first - anchorX) * (it.first - anchorX) + (it.second - anchorY) * (it.second - anchorY) }) {
        val box = LabelRect(x, y, x + width, y + height)
        val padded = box.padded(gap * .35f)
        if (occupied.none(padded::overlaps) && lines.none(padded::intersects)) return box
    }
    return null
}
