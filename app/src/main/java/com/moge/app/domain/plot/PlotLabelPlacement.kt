package com.moge.app.domain.plot

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

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

/** Prefer the nearest free edge of a label, including positions directly beside its point. */
fun placePlotLabel(anchorX: Float, anchorY: Float, width: Float, height: Float,
    bounds: LabelRect, occupied: List<LabelRect>, lines: List<LabelObstacleLine>, gap: Float,
    anchorRadius: Float = 0f, maxDistance: Float = Float.POSITIVE_INFINITY): LabelRect? {
    if (width > bounds.width || height > bounds.height) return null
    fun boxAt(x: Float, y: Float): LabelRect {
        val left = x.coerceIn(bounds.left, bounds.right - width)
        val top = y.coerceIn(bounds.top, bounds.bottom - height)
        return LabelRect(left, top, left + width, top + height)
    }
    fun distance(box: LabelRect) = hypot(anchorX - anchorX.coerceIn(box.left, box.right),
        anchorY - anchorY.coerceIn(box.top, box.bottom))
    fun score(box: LabelRect): Float {
        val centerX = (box.left + box.right) / 2 - anchorX
        val centerY = (box.top + box.bottom) / 2 - anchorY
        val edge = distance(box)
        return edge * edge + (centerX * centerX + centerY * centerY) * .01f
    }
    fun nearest(candidates: List<LabelRect>) = candidates.distinct()
        .filter { distance(it) <= maxDistance }
        .sortedBy(::score)
        .firstOrNull { isLabelPositionFree(it, occupied, lines, gap * .35f) }

    val candidates = mutableListOf<LabelRect>()
    for (ring in 0..6) {
        val offset = anchorRadius + gap + ring * (height + gap) / 2
        val diagonal = offset * .70710677f
        candidates += listOf(
            anchorX - width / 2 to anchorY - height - offset,
            anchorX - width / 2 to anchorY + offset,
            anchorX + offset to anchorY - height / 2,
            anchorX - width - offset to anchorY - height / 2,
            anchorX + diagonal to anchorY - height - diagonal,
            anchorX - width - diagonal to anchorY - height - diagonal,
            anchorX + diagonal to anchorY + diagonal,
            anchorX - width - diagonal to anchorY + diagonal,
        ).map { (x, y) -> boxAt(x, y) }
    }
    nearest(candidates)?.let { return it }

    // A crowded point only searches its neighbourhood. Other annotations may
    // still use the rest of the plot when no local position is available.
    val step = if (maxDistance.isFinite()) maxOf(1f, minOf(gap, height / 5)) else height + gap
    val grid = mutableListOf<LabelRect>()
    var y = maxOf(bounds.top, anchorY - maxDistance - height)
    val bottom = minOf(bounds.bottom - height, anchorY + maxDistance)
    while (y <= bottom) {
        var x = maxOf(bounds.left, anchorX - maxDistance - width)
        val right = minOf(bounds.right - width, anchorX + maxDistance)
        val stepX = if (maxDistance.isFinite()) step else maxOf(step / 2, width / 4)
        while (x <= right) { grid += boxAt(x, y); x += stepX }
        y += step
    }
    return nearest(grid)
}

/** Connect the visible point edge to the nearest text edge, never to the text centre. */
fun plotLabelLeader(anchorX: Float, anchorY: Float, box: LabelRect,
    anchorRadius: Float, gap: Float, minLength: Float): LabelObstacleLine? {
    val dx = anchorX.coerceIn(box.left, box.right) - anchorX
    val dy = anchorY.coerceIn(box.top, box.bottom) - anchorY
    val distance = hypot(dx, dy)
    val start = anchorRadius + gap
    val end = distance - gap
    if (distance == 0f || end - start <= minLength) return null
    return LabelObstacleLine(anchorX + dx / distance * start, anchorY + dy / distance * start,
        anchorX + dx / distance * end, anchorY + dy / distance * end)
}
