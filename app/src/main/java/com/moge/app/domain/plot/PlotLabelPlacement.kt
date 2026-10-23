package com.moge.app.domain.plot

import kotlin.math.abs
import kotlin.math.ceil

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

/** Keep ticks close to their original position; never move them across the whole plot. */
fun placeNearbyPlotLabel(
    initial: LabelRect,
    bounds: LabelRect,
    occupied: List<LabelRect>,
    lines: List<LabelObstacleLine>,
    gap: Float,
    maxOffset: Float,
): LabelRect? {
    if (!initial.inside(bounds)) return null
    val radius = maxOffset.coerceAtLeast(0f)
    val searchArea = initial.padded(radius + gap)
    val nearbyBoxes = occupied.filter(searchArea::overlaps)
    val nearbyLines = lines.filter(searchArea::intersects)
    if (isLabelPositionFree(initial, nearbyBoxes, nearbyLines, gap)) return initial
    if (radius == 0f) return null

    // A small local grid gives short horizontal/vertical/diagonal moves the same
    // priority. Distance sorting also accounts for clamping at the image edge.
    val steps = ceil(radius / maxOf(1f, minOf(gap, initial.height / 4))).toInt()
    val step = radius / steps
    val candidates = mutableListOf<LabelRect>()
    for (row in -steps..steps) for (column in -steps..steps) {
        val left = (initial.left + column * step).coerceIn(bounds.left, bounds.right - initial.width)
        val top = (initial.top + row * step).coerceIn(bounds.top, bounds.bottom - initial.height)
        val distance = (left - initial.left) * (left - initial.left) + (top - initial.top) * (top - initial.top)
        if (distance <= radius * radius) {
            candidates += LabelRect(left, top, left + initial.width, top + initial.height)
        }
    }
    return candidates.distinct().sortedBy {
        (it.left - initial.left) * (it.left - initial.left) + (it.top - initial.top) * (it.top - initial.top)
    }.firstOrNull { isLabelPositionFree(it, nearbyBoxes, nearbyLines, gap) }
}

private fun isLabelPositionFree(
    box: LabelRect,
    occupied: List<LabelRect>,
    lines: List<LabelObstacleLine>,
    gap: Float,
): Boolean {
    val padded = box.padded(gap)
    return occupied.none(padded::overlaps) && lines.none(padded::intersects)
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
            if (isLabelPositionFree(box, occupied, lines, gap * .35f)) return box
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
        if (isLabelPositionFree(box, occupied, lines, gap * .35f)) return box
    }
    return null
}
