package com.moge.app.ui.figure

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.moge.app.core.LatexEngine
import com.moge.app.domain.plot.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConstellationLabelRenderTest {
    @Before fun prepare() { LatexEngine.init(RuntimeEnvironment.getApplication()) }

    private val points = listOf(-3.0, -1.0, 1.0, 3.0).flatMap { x ->
        listOf(-3.0, -1.0, 1.0, 3.0).map { y -> x to y }
    }
    private val names = linkedMapOf(-3.0 to -3.0 to "\$s=-3-3j\$", -3.0 to -1.0 to "0000",
        -1.0 to -1.0 to "0010", -1.0 to -3.0 to "0011")

    private fun spec(decision: Boolean): PlotSpec {
        val axes = PlotSpec(title = if (decision) "s = −3−3j 的 ML 判决域" else "16-QAM 星座图",
            x = Axis("Re(s)", min = -5.5, max = 5.5, ticks = listOf(-4.0, -2.0, 0.0, 2.0, 4.0)),
            y = Axis("Im(s)", min = -5.5, max = 5.5, ticks = listOf(-4.0, -2.0, 0.0, 2.0, 4.0)), equalAspect = true)
        if (decision) return axes.copy(voronoi = VoronoiSpec(points.map {
            ConstellationSite(it.first, it.second, names[it].orEmpty())
        }, listOf(0)))
        return axes.copy(series = listOf(Series(points = points, style = "marker", markerSize = 4f)) +
            names.entries.mapIndexed { index, (point, name) -> Series(label = name, points = listOf(point),
                style = "marker", colorIndex = index + 1,
                markerShape = if (index == 0) "open_circle" else "circle", markerSize = 4f) })
    }

    private data class Dot(val x: Float, val y: Float, val radius: Float, val style: Paint.Style, val color: Int)

    @Test fun `QAM labels stay local with vertical ticks on the left and horizontal ticks close to the axis`() {
        for (decision in listOf(false, true)) {
            for (theme in listOf(PlotBitmapRenderer.Theme.LIGHT, PlotBitmapRenderer.Theme.DARK)) {
                for (width in listOf(600, 1166)) {
                    val height = (width * .82f).toInt()
                    val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val dots = mutableListOf<Dot>()
                    val axes = mutableListOf<LabelObstacleLine>()
                    val leaders = mutableListOf<LabelObstacleLine>()
                    val canvas = object : Canvas(image) {
                        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
                            dots += Dot(cx, cy, radius, paint.style, paint.color)
                            super.drawCircle(cx, cy, radius, paint)
                        }
                        override fun drawLine(startX: Float, startY: Float, stopX: Float, stopY: Float, paint: Paint) {
                            val line = LabelObstacleLine(startX, startY, stopX, stopY)
                            if (paint.color == theme.axis && paint.pathEffect == null) axes += line
                            if (paint.color == theme.subText && abs(paint.strokeWidth - width * .001f) < .001f) leaders += line
                            super.drawLine(startX, startY, stopX, stopY, paint)
                        }
                    }
                    try {
                        canvas.drawColor(theme.background)
                        val renderer = PlotBitmapRenderer(3f, theme)
                        renderer.drawAll(canvas, spec(decision), width.toFloat(), height.toFloat())
                        val folder = File("build/figure-layout-samples").apply { mkdirs() }
                        val suffix = if (theme == PlotBitmapRenderer.Theme.DARK) "chalk" else "paper"
                        val sample = "qam-${if (decision) "decision" else "labels"}-$suffix-$width"
                        File(folder, "$sample.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        File(folder, "$sample.txt").writeText(renderer.labelPlacements.joinToString("\n"))
                        assertEquals(if (decision) 16 else 20, dots.size)
                        assertTrue("Highlights must be solid dots", dots.all { it.style == Paint.Style.FILL })
                        val vertical = axes.filter { it.x0 == it.x1 }.maxBy { it.y1 - it.y0 }
                        val horizontal = axes.filter { it.y0 == it.y1 }.maxBy { it.x1 - it.x0 }
                        val gridDots = dots.take(16)
                        for ((point, name) in names) {
                            val dot = gridDots[points.indexOf(point)]
                            val label = renderer.labelPlacements.single { it.first == name }.second
                            val distance = hypot(dot.x - dot.x.coerceIn(label.left, label.right),
                                dot.y - dot.y.coerceIn(label.top, label.bottom))
                            assertTrue("$width/$decision/$name must stay by its dot: $distance",
                                distance <= dot.radius + width * .04f)
                            assertTrue(label.inside(LabelRect(0f, 0f, width.toFloat(), height.toFloat())))
                            assertFalse("Point labels must clear both axes", label.intersects(vertical) || label.intersects(horizontal))
                            assertTrue("Point labels must not cover other labels", renderer.labelPlacements
                                .filter { it.first != name }.none { label.overlaps(it.second) })
                        }
                        for (line in leaders) assertTrue("Avoid long label leaders: $line",
                            hypot(line.x1 - line.x0, line.y1 - line.y0) < width * .055f)

                        val yMin = gridDots.minOf { it.y }
                        val yMax = gridDots.maxOf { it.y }
                        val unit = (yMax - yMin) / 6
                        val drawLeft = gridDots.minOf { it.x } - 2.5f * unit
                        for (tick in listOf(-4, -2, 2, 4)) {
                            val expectedY = horizontal.y0 - tick * unit
                            val label = renderer.labelPlacements.filter { it.first == tick.toString() }
                                .minBy { abs((it.second.top + it.second.bottom) / 2 - expectedY) }.second
                            assertTrue("Y tick $tick should stay in the left margin: $label",
                                drawLeft - label.right in 0f..width * .02f)
                            assertEquals(expectedY, (label.top + label.bottom) / 2, width * .025f)
                        }
                        for (tick in listOf(-4, 4)) {
                            val expectedX = vertical.x0 + tick * unit
                            val label = renderer.labelPlacements.filter { it.first == tick.toString() }
                                .minBy { abs((it.second.left + it.second.right) / 2 - expectedX) }.second
                            assertTrue("X tick $tick should sit close below the axis: $label",
                                label.top - horizontal.y0 in 0f..width * .015f)
                        }
                    } finally { image.recycle() }
                }
            }
        }
    }
}
