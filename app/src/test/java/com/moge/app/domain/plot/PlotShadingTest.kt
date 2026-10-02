package com.moge.app.domain.plot

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class PlotShadingTest {
    @Test fun `between curves follows both boundaries within the requested interval`() {
        val polygons = sampleShadePolygons(ShadeRegion(x0 = 0.0, x1 = 1.0, upper = "x", lower = "x^2"), -2.0, 2.0, 3.0)
        val polygon = polygons.single()
        val twiceArea = polygon.indices.sumOf { i ->
            val a = polygon[i]; val b = polygon[(i + 1) % polygon.size]
            a.first * b.second - b.first * a.second
        }
        assertEquals(1.0 / 6, abs(twiceArea) / 2, .00001)
        assertTrue(polygon.all { it.first in 0.0..1.0 })
    }
    @Test fun `undefined middle does not connect the two filled sides`() {
        val polygons = sampleShadePolygons(ShadeRegion(x0 = -1.0, x1 = 1.0, upper = "1/x"), -1.0, 1.0, 4.0)
        assertTrue(polygons.size >= 2)
        assertTrue(polygons.none { p -> p.any { it.first < 0 } && p.any { it.first > 0 } })
    }
    @Test fun `polygon region preserves vertices and empty clipped interval remains empty`() {
        val points = listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0)
        assertEquals(points, sampleShadePolygons(ShadeRegion(points = points), -2.0, 2.0, 3.0).single())
        assertTrue(sampleShadePolygons(ShadeRegion(x0 = 4.0, x1 = 5.0, upper = "x"), -1.0, 1.0, 2.0).isEmpty())
    }
}
