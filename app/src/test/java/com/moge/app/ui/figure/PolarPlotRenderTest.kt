package com.moge.app.ui.figure

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.moge.app.core.LatexEngine
import com.moge.app.domain.plot.LabelRect
import com.moge.app.domain.plot.PlotSpec
import com.moge.app.domain.plot.PolarAxes
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
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PolarPlotRenderTest {
    @Before fun prepare() { LatexEngine.init(RuntimeEnvironment.getApplication()) }

    @Test fun `polar circle uses equal scale and labels stay within the image in both themes`() {
        for (theme in listOf(PlotBitmapRenderer.Theme.LIGHT, PlotBitmapRenderer.Theme.DARK)) {
            for (width in listOf(600, 1166)) {
                val image = Bitmap.createBitmap(width, (width * .82).toInt(), Bitmap.Config.ARGB_8888)
                val circles = mutableListOf<Float>()
                var circleX = 0f
                var circleY = 0f
                val curves = mutableListOf<RectF>()
                val canvas = object : Canvas(image) {
                    override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
                        circles += radius
                        circleX = cx
                        circleY = cy
                        super.drawCircle(cx, cy, radius, paint)
                    }
                    override fun drawPath(path: Path, paint: Paint) {
                        if (paint.color == theme.seriesColors.first() && paint.style == Paint.Style.STROKE) {
                            curves += RectF().also { path.computeBounds(it, true) }
                            assertTrue(path.approximate(.25f).all { it.isFinite() })
                        }
                        super.drawPath(path, paint)
                    }
                }
                try {
                    canvas.drawColor(theme.background)
                    val renderer = PlotBitmapRenderer(3f, theme)
                    renderer.drawAll(canvas, PlotSpec(title = "极坐标圆 r=1", polar = PolarAxes(radiusMax = 2.0),
                        series = listOf(Series(expr = "1"))), width.toFloat(), image.height.toFloat())
                    assertTrue("Draw actual concentric circles", circles.size >= 4)
                    val curve = curves.single()
                    assertEquals(curve.width(), curve.height(), .1f)
                    assertEquals("Unit circle occupies half the viewport diameter", circles.max(), curve.width(), .1f)
                    assertTrue(renderer.labelPlacements.any { it.first == "π/2" })
                    assertTrue(renderer.labelPlacements.any { it.first == "3π/2" })
                    assertFalse(renderer.labelPlacements.any { it.first in setOf("x", "y") })
                    renderer.labelPlacements.forEach { (_, box) ->
                        assertTrue(box.toString(), box.inside(LabelRect(0f, 0f, width.toFloat(), image.height.toFloat())))
                    }
                    for ((text, box) in renderer.labelPlacements.filter { it.first.contains('π') || it.first == "0" }) {
                        val nearestX = circleX.coerceIn(box.left, box.right)
                        val nearestY = circleY.coerceIn(box.top, box.bottom)
                        assertTrue("$text should clear the outer circle", hypot(nearestX - circleX, nearestY - circleY) > circles.max())
                    }
                } finally { image.recycle() }
            }
        }
    }

    @Test fun `cardioid rose lemniscate and finite pole render through the shared pipeline`() {
        val cases = listOf(
            Triple("cardioid", "1-cos(theta)", 2.2),
            Triple("rose", "cos(2*theta)", 1.2),
            Triple("lemniscate", "sqrt(cos(2*theta))", 1.2),
            Triple("pole", "1/cos(theta)", 2.5),
        )
        for ((name, expr, radius) in cases) {
            for (theme in listOf(PlotBitmapRenderer.Theme.LIGHT, PlotBitmapRenderer.Theme.DARK)) {
                val renderer = PlotBitmapRenderer(3f, theme)
                val title = when (name) {
                    "cardioid" -> "心形线 r=1−cos θ"
                    "rose" -> "四叶玫瑰线 r=cos 2θ"
                    "lemniscate" -> "双纽线"
                    else -> "极坐标直线 r=1/cos θ"
                }
                val spec = PlotSpec(title = title, polar = PolarAxes(radiusMax = radius),
                    series = listOf(Series(expr = expr)))
                val image = renderer.render(spec, 1166, 956)
                try {
                    var curvePixels = 0
                    for (y in 0 until image.height) for (x in 0 until image.width) {
                        if (image.getPixel(x, y) == theme.seriesColors.first()) curvePixels++
                    }
                    assertTrue("$name must draw a visible curve", curvePixels > 500)
                    val folder = File("build/figure-layout-samples").apply { mkdirs() }
                    val suffix = if (theme == PlotBitmapRenderer.Theme.DARK) "chalk" else "paper"
                    File(folder, "polar-$name-$suffix.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { image.recycle() }
            }
        }
    }
}
