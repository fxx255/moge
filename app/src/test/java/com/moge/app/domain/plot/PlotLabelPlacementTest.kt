package com.moge.app.domain.plot

import org.junit.Assert.*
import org.junit.Test

class PlotLabelPlacementTest {
    @Test fun `labels avoid points axes boundaries and previous labels`() {
        val bounds = LabelRect(0f, 0f, 400f, 300f)
        val occupied = mutableListOf(LabelRect(196f, 146f, 204f, 154f))
        val lines = listOf(LabelObstacleLine(200f, 0f, 200f, 300f),
            LabelObstacleLine(0f, 150f, 400f, 150f), LabelObstacleLine(100f, 260f, 300f, 40f))
        repeat(8) {
            val box = requireNotNull(placePlotLabel(200f, 150f, 40f, 20f, bounds, occupied, lines, 8f))
            assertTrue(box.inside(bounds))
            assertTrue(occupied.none(box::overlaps))
            assertTrue(lines.none(box::intersects))
            occupied += box
        }
    }

    @Test fun `vertical horizontal diagonal and degenerate line collisions are detected`() {
        val box = LabelRect(10f, 10f, 30f, 30f)
        assertTrue(box.intersects(LabelObstacleLine(20f, 0f, 20f, 50f)))
        assertTrue(box.intersects(LabelObstacleLine(0f, 20f, 50f, 20f)))
        assertTrue(box.intersects(LabelObstacleLine(0f, 0f, 50f, 50f)))
        assertTrue(box.intersects(LabelObstacleLine(20f, 20f, 20f, 20f)))
        assertFalse(box.intersects(LabelObstacleLine(0f, 0f, 0f, 0f)))
        assertFalse(box.intersects(LabelObstacleLine(0f, 9f, 50f, 9f)))
    }

    @Test fun `oversized label requests a margin callout`() {
        assertNull(placePlotLabel(50f, 50f, 101f, 20f, LabelRect(0f, 0f, 100f, 100f), emptyList(), emptyList(), 4f))
    }
}
