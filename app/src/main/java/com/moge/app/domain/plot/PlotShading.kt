package com.moge.app.domain.plot

import kotlin.math.abs

/** Break at nonfinite values and asymptotic jumps rather than filling across a pole. */
fun sampleShadePolygons(region: ShadeRegion, xMin: Double, xMax: Double, ySpan: Double,
    samples: Int = 600): List<List<Pair<Double, Double>>> {
    region.points?.let { return if (it.size >= 3) listOf(it) else emptyList() }
    val lo = maxOf(region.x0 ?: xMin, xMin)
    val hi = minOf(region.x1 ?: xMax, xMax)
    if (hi <= lo) return emptyList()
    val upper = ExprEval.compile(region.upper)
    val lower = ExprEval.compile(region.lower)
    val result = mutableListOf<List<Pair<Double, Double>>>()
    val top = mutableListOf<Pair<Double, Double>>()
    val bottom = mutableListOf<Pair<Double, Double>>()
    fun flush() {
        if (top.size >= 2) result += top.toList() + bottom.asReversed()
        top.clear(); bottom.clear()
    }
    for (i in 0..samples) {
        val x = lo + (hi - lo) * i / samples
        val a = runCatching { upper(x) }.getOrNull()
        val b = runCatching { lower(x) }.getOrNull()
        if (a == null || b == null || !a.isFinite() || !b.isFinite()) { flush(); continue }
        if (top.isNotEmpty() && (abs(a - top.last().second) > ySpan * 2 || abs(b - bottom.last().second) > ySpan * 2)) flush()
        top += x to a
        bottom += x to b
    }
    flush()
    return result
}
