package com.moge.app.domain.plot

import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class ConstellationSite(val x: Double, val y: Double, val label: String = "")

/** Euclidean nearest-neighbour regions: equal priors and isotropic noise only. */
@Serializable
data class VoronoiSpec(
    val sites: List<ConstellationSite>,
    val highlight: List<Int> = emptyList(),
    val allRegions: Boolean = false,
) {
    val selectedIndices: List<Int> get() = if (allRegions) sites.indices.toList() else highlight.distinct()
    fun colorIndex(site: Int): Int = if (site in selectedIndices) site + 1 else 0
}

data class DataWindow(val xMin: Double, val xMax: Double, val yMin: Double, val yMax: Double)

/** Clip the view window against every closer-to-target half plane. No hand-written slopes. */
fun voronoiCell(sites: List<ConstellationSite>, target: Int, window: DataWindow): List<Pair<Double, Double>> {
    require(target in sites.indices && sites.size >= 2)
    require(sites.all { it.x.isFinite() && it.y.isFinite() })
    require(sites.map { it.x to it.y }.distinct().size == sites.size) { "星座点不能重合" }
    require(window.xMin < window.xMax && window.yMin < window.yMax)
    var polygon = listOf(window.xMin to window.yMin, window.xMax to window.yMin,
        window.xMax to window.yMax, window.xMin to window.yMax)
    val p = sites[target]
    for ((index, q) in sites.withIndex()) {
        if (index == target || polygon.isEmpty()) continue
        // q-p · (r - (p+q)/2) <= 0 avoids subtracting large squared coordinates.
        val a = q.x - p.x; val b = q.y - p.y
        fun distance(r: Pair<Double, Double>) = a * (r.first - (p.x + q.x) / 2) + b * (r.second - (p.y + q.y) / 2)
        val result = mutableListOf<Pair<Double, Double>>()
        for (i in polygon.indices) {
            val from = polygon[i]; val to = polygon[(i + 1) % polygon.size]
            val df = distance(from); val dt = distance(to)
            if (df <= 0) result += from
            if ((df <= 0) != (dt <= 0)) {
                val t = df / (df - dt)
                result += from.first + t * (to.first - from.first) to from.second + t * (to.second - from.second)
            }
        }
        polygon = result
    }
    return polygon
}

/** Window edges close the fill, but are not mathematical decision boundaries. */
fun voronoiBoundaryEdges(cell: List<Pair<Double, Double>>, window: DataWindow): List<List<Pair<Double, Double>>> {
    val eps = maxOf(window.xMax - window.xMin, window.yMax - window.yMin) * 1e-8
    fun onEdge(a: Pair<Double, Double>, b: Pair<Double, Double>) =
        listOf(window.xMin, window.xMax).any { abs(a.first - it) < eps && abs(b.first - it) < eps } ||
            listOf(window.yMin, window.yMax).any { abs(a.second - it) < eps && abs(b.second - it) < eps }
    return cell.indices.mapNotNull { i ->
        val a = cell[i]; val b = cell[(i + 1) % cell.size]
        if (onEdge(a, b) || abs(a.first - b.first) < eps && abs(a.second - b.second) < eps) null else listOf(a, b)
    }
}

data class DecisionRegion(val siteIndex: Int, val polygon: List<Pair<Double, Double>>)
data class DecisionRegions(val cells: List<DecisionRegion>, val boundaries: List<List<Pair<Double, Double>>>)

/** Every cell uses every competitor. Shared selected-cell boundaries are drawn exactly once. */
fun decisionRegions(spec: VoronoiSpec, window: DataWindow): DecisionRegions {
    val cells = spec.selectedIndices.map { DecisionRegion(it, voronoiCell(spec.sites, it, window)) }
    val eps = maxOf(window.xMax - window.xMin, window.yMax - window.yMin) * 1e-8
    fun near(a: Pair<Double, Double>, b: Pair<Double, Double>) = abs(a.first - b.first) < eps && abs(a.second - b.second) < eps
    val boundaries = mutableListOf<List<Pair<Double, Double>>>()
    cells.flatMap { voronoiBoundaryEdges(it.polygon, window) }.forEach { edge ->
        if (boundaries.none { old -> near(edge[0], old[0]) && near(edge[1], old[1]) || near(edge[0], old[1]) && near(edge[1], old[0]) }) {
            boundaries += edge
        }
    }
    return DecisionRegions(cells, boundaries)
}

/** Equal physical distance per coordinate unit, preserving the explicitly chosen data range. */
fun equalAspectSize(width: Float, height: Float, spanX: Double, spanY: Double): Pair<Float, Float> {
    val scale = minOf(width / spanX, height / spanY)
    return (spanX * scale).toFloat() to (spanY * scale).toFloat()
}
