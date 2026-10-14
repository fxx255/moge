package com.moge.app.domain.plot

import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class VoronoiGeometryTest {
    private val root3 = sqrt(3.0)
    private val a = 1 + root3
    private val sites = listOf(ConstellationSite(a, 1.0, "s₁"), ConstellationSite(1.0, 0.0, "s₂"),
        ConstellationSite(0.0, root3, "s₃"), ConstellationSite(-1.0, 0.0, "s₄"),
        ConstellationSite(-a, 1.0, "s₅"), ConstellationSite(-a, -1.0, "s₆"),
        ConstellationSite(0.0, -root3, "s₇"), ConstellationSite(a, -1.0, "s₈"))
    private val window = DataWindow(-4.0, 4.0, -3.0, 3.0)

    @Test fun `eight point cell has the correct two vertices and all three active boundaries`() {
        val cell = voronoiCell(sites, 0, window)
        assertTrue(cell.any { near(it, 1 + 2 * root3 / 3, 0.0) })
        assertTrue(cell.any { near(it, a / 2, a / 2) })
        val edges = voronoiBoundaryEdges(cell, window)
        assertEquals(3, edges.size)
        assertTrue(edges.any { it.all { p -> kotlin.math.abs(root3 * p.first + p.second - (2 + root3)) < 1e-8 } })
        assertTrue(edges.any { it.all { p -> kotlin.math.abs(p.second) < 1e-8 } })
        assertTrue(edges.any { it.all { p -> kotlin.math.abs(p.second - (2 + root3) * (p.first - 1)) < 1e-8 } })
    }

    @Test fun `every filled point satisfies nearest neighbour comparisons against all eight sites`() {
        val cell = voronoiCell(sites, 0, window)
        fun squared(p: Pair<Double, Double>, site: ConstellationSite) =
            (p.first - site.x) * (p.first - site.x) + (p.second - site.y) * (p.second - site.y)
        val centroid = cell.map { it.first }.average() to cell.map { it.second }.average()
        for (vertex in cell) for (fraction in listOf(0.0, .25, .5, .75, 1.0)) {
            val point = vertex.first * fraction + centroid.first * (1 - fraction) to vertex.second * fraction + centroid.second * (1 - fraction)
            val targetDistance = squared(point, sites.first())
            sites.forEach { assertTrue(targetDistance <= squared(point, it) + 1e-8) }
        }
    }

    @Test fun `window crop edges never become decision boundaries`() {
        val cell = voronoiCell(listOf(ConstellationSite(-1.0, 0.0), ConstellationSite(1.0, 0.0)), 0, window)
        assertEquals(4, cell.size)
        val edges = voronoiBoundaryEdges(cell, window)
        assertEquals(1, edges.size)
        assertTrue(edges.single().all { kotlin.math.abs(it.first) < 1e-8 })
    }

    @Test fun `equal scale preserves distances for a wide canvas`() {
        val (w, h) = equalAspectSize(800f, 300f, 8.0, 6.0)
        assertEquals(w / 8, h / 6, 1e-6f)
        assertEquals(400f, w, 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class) fun `coincident sites are rejected`() {
        voronoiCell(listOf(sites.first(), sites.first()), 0, window)
    }

    private fun near(p: Pair<Double, Double>, x: Double, y: Double) =
        kotlin.math.abs(p.first - x) < 1e-8 && kotlin.math.abs(p.second - y) < 1e-8

    @Test fun `single and multiple targets keep all unselected competitors`() {
        val single = decisionRegions(VoronoiSpec(sites, listOf(0)), window)
        val multiple = decisionRegions(VoronoiSpec(sites, listOf(0, 2, 0)), window)
        assertEquals(listOf(0, 2), multiple.cells.map { it.siteIndex })
        assertEquals(single.cells.first().polygon, multiple.cells.first().polygon)
        assertEquals(voronoiCell(sites, 2, window), multiple.cells.last().polygon)
        assertEquals(3, single.boundaries.size)
    }

    @Test fun `all QAM and PSK regions partition the window and share boundaries only once`() {
        val qam = listOf(-3.0, -1.0, 1.0, 3.0).flatMap { x -> listOf(-3.0, -1.0, 1.0, 3.0).map { y -> ConstellationSite(x, y) } }
        val psk = (0..7).map { k ->
            val angle = k * kotlin.math.PI / 4
            ConstellationSite(2 * kotlin.math.cos(angle), 2 * kotlin.math.sin(angle))
        }
        val crop = DataWindow(-4.0, 4.0, -4.0, 4.0)
        for (constellation in listOf(qam, psk, sites)) {
            val result = decisionRegions(VoronoiSpec(constellation, allRegions = true), crop)
            assertEquals(constellation.size, result.cells.size)
            val totalArea = result.cells.sumOf { cell ->
                kotlin.math.abs(cell.polygon.indices.sumOf { i ->
                    val a = cell.polygon[i]; val b = cell.polygon[(i + 1) % cell.polygon.size]
                    a.first * b.second - b.first * a.second
                }) / 2
            }
            assertEquals(64.0, totalArea, 1e-7)
            val allEdgeCount = result.cells.sumOf { voronoiBoundaryEdges(it.polygon, crop).size }
            assertEquals(allEdgeCount, result.boundaries.size * 2)
            result.cells.forEach { cell ->
                val center = cell.polygon.map { it.first }.average() to cell.polygon.map { it.second }.average()
                val distances = constellation.map { (it.x - center.first) * (it.x - center.first) + (it.y - center.second) * (it.y - center.second) }
                assertTrue(distances[cell.siteIndex] <= distances.min() + 1e-8)
            }
        }
    }

    @Test fun `empty selection preserves historical constellation only diagrams`() {
        val result = decisionRegions(VoronoiSpec(sites), window)
        assertTrue(result.cells.isEmpty())
        assertTrue(result.boundaries.isEmpty())
    }
}
