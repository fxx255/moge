package com.moge.app.ui.figure

import android.app.Application
import android.graphics.Bitmap
import com.moge.app.core.LatexEngine
import com.moge.app.domain.plot.Axis
import com.moge.app.domain.plot.PlotSpec
import com.moge.app.domain.plot.Series
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlotAxisTransparencyTest {
    @Before fun prepare() { LatexEngine.init(RuntimeEnvironment.getApplication()) }

    @Test fun `numeric and formula ticks cannot erase underlying axes or spectrum`() {
        val spec = PlotSpec(title = "E 点单边功率谱密度",
            x = Axis(label = "\$f-f_c\$ / kHz", min = -25.0, max = 25.0, grid = false,
                ticks = listOf(-25.0, -15.0, 0.0, 15.0, 25.0), tickLabels = mapOf(-15.0 to "\$-15\$")),
            y = Axis(label = "\$P_E(f)\$", min = 0.0, max = 1.3, grid = false, ticks = emptyList()),
            series = listOf(Series(points = listOf(-25.0 to 0.0, -23.0 to .1, -17.0 to .9,
                -15.0 to 1.0, 15.0 to 1.0, 17.0 to .9, 23.0 to .1, 25.0 to 0.0))))
        for (theme in listOf(PlotBitmapRenderer.Theme.LIGHT, PlotBitmapRenderer.Theme.DARK)) {
            val renderer = PlotBitmapRenderer(2f, theme)
            val image = renderer.render(spec, 1166, 769)
            val reference = PlotBitmapRenderer(2f, theme).render(spec.copy(x = spec.x.copy(ticks = emptyList())), 1166, 769)
            try {
                if (System.getenv("FIGURE_LAYOUT_EXPORT_SAMPLES") == "1") {
                    val folder = File("build/figure-layout-samples").apply { mkdirs() }
                    File(folder, if (theme == PlotBitmapRenderer.Theme.DARK) "spectrum-chalk.png" else "spectrum-paper.png")
                        .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
                val ticks = renderer.labelPlacements.filter { it.first in setOf("-25", "\$-15\$", "0", "15", "25") }
                assertEquals(renderer.labelPlacements.map { it.first }.toString(), 5, ticks.size)
                var checkedGeometryPixels = 0
                var erasedPixels = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val before = reference.getPixel(x, y)
                    if (before == theme.axis || before == theme.seriesColors.first()) {
                        checkedGeometryPixels++
                        if (image.getPixel(x, y) == theme.background) erasedPixels++
                    }
                }
                assertTrue(checkedGeometryPixels > 1000)
                assertEquals("Transparent ticks must preserve the axis and curve", 0, erasedPixels)
            } finally { image.recycle(); reference.recycle() }
        }
    }
}
