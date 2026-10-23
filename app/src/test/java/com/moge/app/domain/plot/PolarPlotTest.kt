package com.moge.app.domain.plot

import kotlin.math.PI
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class PolarPlotTest {
    @Test fun `cardioid is sampled locally with the correct cusp and extrema`() {
        val points = samplePolarSeries(Series(expr = "1-cos(theta)"), count = 360)
        assertPoint(0.0, 0.0, points[0])
        assertPoint(0.0, 1.0, points[90])
        assertPoint(-2.0, 0.0, points[180])
        assertPoint(0.0, -1.0, points[270])
        assertPoint(0.0, 0.0, points[360])
        assertEquals(2.16, polarRadius(points, PolarAxes()), 1e-9)
    }

    @Test fun `rose keeps negative radii and supports Greek angle notation`() {
        val points = samplePolarSeries(Series(expr = "cos(2*θ)"), count = 360)
        assertPoint(0.0, -1.0, points[90])
        assertPoint(0.0, 1.0, points[270])
        assertEquals(-1.0, compilePolarExpression("cos(θ+π)")(0.0), 1e-9)
    }

    @Test fun `a partial circle preserves its angular interval and equal radius`() {
        val points = samplePolarSeries(Series(expr = "2", thetaMin = PI / 2, thetaMax = PI), count = 90)
        assertPoint(0.0, 2.0, points.first())
        assertPoint(-2.0, 0.0, points.last())
        assertTrue(points.all { (x, y) -> y != null && kotlin.math.abs(hypot(x, y) - 2.0) < 1e-9 })
        assertEquals(3.0, polarRadius(points, PolarAxes(radiusMax = 3.0)), 0.0)
    }

    @Test fun `undefined lemniscate angles break the path between lobes`() {
        val points = samplePolarSeries(Series(expr = "sqrt(cos(2*theta))"), count = 360)
        assertPoint(1.0, 0.0, points.first())
        assertPoint(1.0, 0.0, points.last())
        assertTrue(points.any { (x, y) -> y != null && x < -.99 })
        val boundaries = points.zipWithNext().mapNotNull { (a, b) ->
            when {
                a.second == null && b.second != null -> b
                b.second == null && a.second != null -> a
                else -> null
            }
        }
        assertEquals("Two undefined angular intervals remain separated", 4, boundaries.size)
        assertTrue("Each visible lobe must reach the pole", boundaries.all { (x, y) -> hypot(x, y!!) < 1e-3 })
    }

    @Test fun `finite viewport breaks a pole rather than joining opposite branches`() {
        val points = samplePolarSeries(Series(expr = "1/cos(theta)"), radiusMax = 3.0, count = 360)
        assertTrue(points.any { it.second == null })
        assertTrue(points.all { (x, y) -> y == null || hypot(x, y) <= 3e6 })
        val narrowPole = samplePolarSeries(Series(expr = "1/(theta-1)", thetaMin = .5, thetaMax = 1.5),
            radiusMax = 2.0, count = 99)
        assertTrue(narrowPole.any { it.second == null })
    }

    @Test fun `invalid domains and all undefined expressions fail safely`() {
        assertThrows(IllegalArgumentException::class.java) { samplePolarSeries(Series(expr = "1", thetaMin = 2.0, thetaMax = 1.0)) }
        assertThrows(IllegalArgumentException::class.java) { samplePolarSeries(Series(expr = "sqrt(-1)")) }
        assertThrows(IllegalArgumentException::class.java) { compilePolarExpression("x+theta") }
        assertThrows(IllegalArgumentException::class.java) { ExprEval.compile("theta") }
    }

    @Test fun `Cartesian expressions and explicit overlay points retain their semantics`() {
        assertPoint(2.0, 4.0, sampleSeries(Series(expr = "x^2"), -2.0, 2.0, 4).last())
        val points = listOf(1.0 to -2.0, -3.0 to 4.0)
        assertEquals(points, samplePolarSeries(Series(points = points)))
        assertEquals("π/6", polarAngleLabel(30))
        assertEquals("3π/2", polarAngleLabel(270))
    }

    private fun assertPoint(x: Double, y: Double, actual: Pair<Double, Double?>) {
        assertEquals(x, actual.first, 1e-9)
        assertEquals(y, actual.second!!, 1e-9)
    }
}
