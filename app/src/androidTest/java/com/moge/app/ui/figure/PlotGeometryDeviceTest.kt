package com.moge.app.ui.figure

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.domain.plot.*
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual Android text and TeX metrics; preserves both theme samples for visual inspection. */
@RunWith(AndroidJUnit4::class)
class PlotGeometryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun singleMultipleAndAllRegionsRenderWithReadableLabelsOnBothThemes() {
        val sites = listOf(-3.0, -1.0, 1.0, 3.0).flatMap { x ->
            listOf(-3.0, -1.0, 1.0, 3.0).map { y -> ConstellationSite(x, y, "s${((x + 3) * 2 + (y + 3) / 2 + 1).toInt()}") }
        }
        for ((selection, geometry) in listOf("single" to VoronoiSpec(sites, listOf(5)),
            "multi" to VoronoiSpec(sites, listOf(0, 5, 10)), "all" to VoronoiSpec(sites, allRegions = true))) {
            val spec = PlotSpec(title = "16-QAM 判决域", x = Axis("I", min = -4.0, max = 4.0),
                y = Axis("Q", min = -4.0, max = 4.0), voronoi = geometry)
            for ((name, theme) in listOf("light" to PlotBitmapRenderer.Theme.LIGHT, "dark" to PlotBitmapRenderer.Theme.DARK)) {
                val renderer = PlotBitmapRenderer(context.resources.displayMetrics.density, theme)
                val bitmap = renderer.render(spec, 1000, 820)
                try {
                    val labels = renderer.labelPlacements.filter { it.first in sites.map(ConstellationSite::label) }
                    assertEquals("$selection/$name must show every competitor", 16, labels.size)
                    labels.forEachIndexed { index, (label, box) ->
                        assertTrue(box.inside(LabelRect(0f, 0f, 1000f, 820f)))
                        assertTrue("$selection/$name label $index overlaps", renderer.labelPlacements.filter { it.first != label }.none { box.overlaps(it.second) })
                    }
                    File(context.getExternalFilesDir(null), "qam-$selection-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun constellationLabelsStayDistinctAndWithinTheImageOnBothThemes() {
        val a = 1 + sqrt(3.0)
        val b = sqrt(3.0)
        val sites = listOf(ConstellationSite(a, 1.0, "s₁"), ConstellationSite(1.0, 0.0, "s₂"),
            ConstellationSite(0.0, b, "s₃"), ConstellationSite(-1.0, 0.0, "s₄"),
            ConstellationSite(-a, 1.0, "s₅"), ConstellationSite(-a, -1.0, "s₆"),
            ConstellationSite(0.0, -b, "s₇"), ConstellationSite(a, -1.0, "s₈"))
        val spec = PlotSpec(title = "八进制星座与 s₁ 判决域", x = Axis("φ₁", min = -4.0, max = 4.0),
            y = Axis("φ₂", min = -3.0, max = 3.0), equalAspect = true, voronoi = VoronoiSpec(sites, listOf(0)))
        for ((name, theme) in listOf("light" to PlotBitmapRenderer.Theme.LIGHT, "dark" to PlotBitmapRenderer.Theme.DARK)) {
            val renderer = PlotBitmapRenderer(context.resources.displayMetrics.density, theme)
            val bitmap = renderer.render(spec, 1000, 820)
            try {
                val labels = renderer.labelPlacements.filter { it.first in sites.map(ConstellationSite::label) }
                assertEquals(8, labels.size)
                labels.forEachIndexed { index, (_, box) ->
                    assertTrue(box.inside(LabelRect(0f, 0f, 1000f, 820f)))
                    assertTrue("$name $index overlaps another constellation label", labels.filterIndexed { other, _ -> other != index }.none { box.overlaps(it.second) })
                    assertTrue("$name $index overlaps a tick/title", renderer.labelPlacements.filter { it.first !in sites.map(ConstellationSite::label) }.none { box.overlaps(it.second) })
                }
                File(context.getExternalFilesDir(null), "constellation-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun multiLineLegendKeepsEveryEntryAndRotatedAxisLabel() {
        val spec = PlotSpec(title = "曲线和图例", x = Axis("x", min = -3.0, max = 3.0),
            y = Axis("功率密度", min = 0.0, max = 12.0), legend = true,
            series = (1..6).map { Series(label = "曲线 $it 的参数说明", expr = "x^2+$it/4", colorIndex = it - 1) })
        val renderer = PlotBitmapRenderer(context.resources.displayMetrics.density, PlotBitmapRenderer.Theme.LIGHT)
        val bitmap = renderer.render(spec, 1000, 660)
        try {
            assertEquals(6, renderer.labelPlacements.count { it.first.startsWith("曲线 ") })
            val axis = renderer.labelPlacements.single { it.first == "功率密度" }.second
            assertTrue("axis label should remain vertical", axis.height > axis.width)
            assertTrue("axis label must be visible", axis.inside(LabelRect(0f, 0f, 1000f, 660f)))
            File(context.getExternalFilesDir(null), "plot-legend.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
