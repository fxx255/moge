package com.moge.app.ui.figure

import com.moge.app.domain.plot.PlotSpec

/** Scatter highlights use colour; open endpoints on function curves retain their meaning. */
internal fun solidScatterHighlights(spec: PlotSpec): PlotSpec {
    if (spec.series.none { it.markerShape == "open_circle" }) return spec
    val scatter = spec.voronoi != null || spec.polar == null && spec.equalAspect &&
        spec.series.isNotEmpty() && spec.series.all { it.style == "marker" && it.expr == null } &&
        spec.series.flatMap { it.points.orEmpty() }.distinct().size >= 3
    if (!scatter) return spec
    return spec.copy(series = spec.series.map {
        if (it.markerShape == "open_circle") it.copy(markerShape = "circle") else it
    })
}
