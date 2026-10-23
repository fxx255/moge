package com.moge.app.ui.figure

import com.moge.app.domain.plot.LabelObstacleLine
import com.moge.app.domain.plot.LabelRect
import com.moge.app.domain.plot.intersects
import org.junit.Assert.*
import org.junit.Test

class PlotAxisLabelLayoutTest {
    private val bounds = LabelRect(0f, 0f, 1000f, 800f)

    @Test fun `zero and formula ticks clear either axis without hiding lines`() {
        for (width in listOf(18f, 64f)) {
            val xTick = avoidPlotAxisLines(LabelRect(500f - width / 2, 412f, 500f + width / 2, 440f),
                bounds, 12f, verticalAxis = 500f, horizontalAxis = 400f)
            assertTrue(xTick.inside(bounds))
            assertTrue(xTick.right <= 488f || xTick.left >= 512f)
            assertTrue(xTick.top >= 412f)
            val yTick = avoidPlotAxisLines(LabelRect(100f, 384f, 100f + width, 412f),
                bounds, 12f, horizontalAxis = 400f)
            assertFalse(yTick.intersects(LabelObstacleLine(0f, 400f, 1000f, 400f)))
            assertTrue(yTick.bottom <= 388f || yTick.top >= 412f)
        }
    }

    @Test fun `labels close to image edges move to the available side`() {
        for (axis in listOf(5f, 995f)) {
            val box = avoidPlotAxisLines(LabelRect(axis - 12f, 400f, axis + 12f, 430f),
                bounds, 10f, verticalAxis = axis)
            assertTrue(box.inside(bounds))
            assertFalse(box.intersects(LabelObstacleLine(axis, 0f, axis, 800f)))
        }
    }

    @Test fun `unobstructed labels retain their alignment`() {
        val label = LabelRect(600f, 420f, 630f, 450f)
        assertEquals(label, avoidPlotAxisLines(label, bounds, 12f, verticalAxis = 500f, horizontalAxis = 400f))
    }
}
