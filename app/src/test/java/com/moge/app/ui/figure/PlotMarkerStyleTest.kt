package com.moge.app.ui.figure

import com.moge.app.domain.plot.ConstellationSite
import com.moge.app.domain.plot.PlotSpec
import com.moge.app.domain.plot.PolarAxes
import com.moge.app.domain.plot.Series
import com.moge.app.domain.plot.VoronoiSpec
import org.junit.Assert.*
import org.junit.Test

class PlotMarkerStyleTest {
    private val points = listOf(-1.0 to -1.0, -1.0 to 1.0, 1.0 to -1.0, 1.0 to 1.0)
    private val highlight = Series(label = "s", points = listOf(points.first()), style = "marker",
        colorIndex = 3, markerShape = "open_circle", markerSize = 6f)

    @Test fun `scatter and decision region highlights use solid dots and keep their colour`() {
        val scatter = PlotSpec(equalAspect = true,
            series = listOf(Series(points = points, style = "marker"), highlight))
        val decision = PlotSpec(voronoi = VoronoiSpec(points.map { ConstellationSite(it.first, it.second) }, listOf(0)),
            series = listOf(highlight))
        for (spec in listOf(scatter, decision)) {
            val result = solidScatterHighlights(spec)
            assertEquals(highlight.copy(markerShape = "circle"), result.series.last())
            assertEquals("open_circle", spec.series.last().markerShape)
        }
    }

    @Test fun `excluded endpoints and polar curve markers remain open`() {
        val curve = PlotSpec(equalAspect = true, series = listOf(Series(expr = "x^2"), highlight))
        val isolated = PlotSpec(equalAspect = true, series = listOf(highlight))
        val polar = PlotSpec(equalAspect = true, polar = PolarAxes(radiusMax = 2.0),
            series = listOf(Series(points = points, style = "marker"), highlight))
        for (spec in listOf(curve, isolated, polar)) assertSame(spec, solidScatterHighlights(spec))
    }
}
