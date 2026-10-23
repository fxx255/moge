package com.moge.app.ui.figure

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.moge.app.core.LatexEngine
import com.moge.app.domain.plot.Axis
import com.moge.app.domain.plot.PlotSpec
import com.moge.app.domain.plot.Series
import com.moge.app.domain.plot.LabelObstacleLine
import com.moge.app.domain.plot.LabelRect
import com.moge.app.domain.plot.intersects
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
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

    @Test fun `cardioid ticks avoid the nearby curve while y and zero labels stay beside their axes`() {
        val spec = PlotSpec(title = "笛卡尔心形线 r=1−cos θ", equalAspect = true,
            x = Axis("x", min = -2.4, max = .6, ticks = listOf(-2.0, -1.0, 0.0, .5)),
            y = Axis("y", min = -1.5, max = 1.5, ticks = listOf(-1.5, -1.0, -.5, 0.0, .5, 1.0, 1.5),
                tickLabels = mapOf(0.0 to "\$0\$")),
            series = listOf(Series(label = "r = 1−cos θ", points = (0..120).map { index ->
                val angle = index * 2 * PI / 120
                val radius = 1 - cos(angle)
                radius * cos(angle) to radius * sin(angle)
            }), Series(label = "尖点 O", points = listOf(0.0 to 0.0), style = "marker", markerShape = "open_circle")))
        for (theme in listOf(PlotBitmapRenderer.Theme.LIGHT, PlotBitmapRenderer.Theme.DARK)) {
            for (width in listOf(600, 1166)) {
                val height = (width * .82).toInt()
                val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val lines = mutableListOf<LabelObstacleLine>()
                val curve = mutableListOf<LabelObstacleLine>()
                val canvas = object : Canvas(image) {
                    override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
                        if (paint.color == theme.axis) lines += LabelObstacleLine(startX, startY, stopX, stopY)
                        super.drawLine(startX, startY, stopX, stopY, paint)
                    }
                    override fun drawPath(path: Path, paint: Paint) {
                        if (paint.color == theme.seriesColors.first() && paint.style == Paint.Style.STROKE) {
                            val points = path.approximate(.25f)
                            for (i in 3 until points.size step 3) {
                                curve += LabelObstacleLine(points[i - 2], points[i - 1], points[i + 1], points[i + 2])
                            }
                        }
                        super.drawPath(path, paint)
                    }
                }
                try {
                    canvas.drawColor(theme.background)
                    val renderer = PlotBitmapRenderer(3f, theme)
                    renderer.drawAll(canvas, spec, width.toFloat(), height.toFloat())
                    val horizontal = lines.filter { it.y0 == it.y1 }.maxBy { it.x1 - it.x0 }
                    val vertical = lines.filter { it.x0 == it.x1 }.maxBy { it.y1 - it.y0 }
                    assertTrue("Record the actual rendered curve", curve.isNotEmpty())
                    val minusTwo = renderer.labelPlacements.single { it.first == "-2" }.second
                    assertTrue(minusTwo.inside(LabelRect(0f, 0f, width.toFloat(), height.toFloat())))
                    assertFalse("The -2 tick must not touch the curve", curve.any(minusTwo.padded(2f)::intersects))
                    val crossingX = curve.minOf { minOf(it.x0, it.x1) }
                    assertTrue("Keep -2 close to its tick", kotlin.math.abs((minusTwo.left + minusTwo.right) / 2 - crossingX) < width * .05f)
                    val zeroLabels = renderer.labelPlacements.filter { it.first in setOf("0", "\$0\$") }
                    assertEquals(renderer.labelPlacements.toString(), 2, zeroLabels.size)
                    for ((_, box) in zeroLabels) {
                        assertTrue(box.inside(LabelRect(0f, 0f, width.toFloat(), height.toFloat())))
                        assertFalse(box.toString(), box.intersects(horizontal))
                        assertFalse(box.toString(), box.intersects(vertical))
                    }
                    val name = renderer.labelPlacements.single { it.first == "y" }.second
                    assertTrue("y must follow the vertical arrow", kotlin.math.abs(name.left - vertical.x0) < width * .1f)
                    assertTrue(name.top >= vertical.y0 && name.top < vertical.y0 + height * .06f)
                    assertFalse(name.intersects(vertical))
                    if (width == 1166) {
                        val folder = File("build/figure-layout-samples").apply { mkdirs() }
                        File(folder, if (theme == PlotBitmapRenderer.Theme.DARK) "cardioid-chalk.png" else "cardioid-paper.png")
                            .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                } finally { image.recycle() }
            }
        }
    }

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
