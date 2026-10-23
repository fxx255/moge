package com.moge.app.domain.plot

import org.junit.Assert.*
import org.junit.Test

class PlotLabelPlacementTest {
    @Test fun `ticks take a short clear step around curves and preserve neighbouring labels`() {
        val bounds = LabelRect(0f, 0f, 400f, 300f)
        val original = LabelRect(100f, 160f, 130f, 180f)
        val curve = LabelObstacleLine(114f, 100f, 118f, 240f)
        val axis = LabelObstacleLine(0f, 150f, 400f, 150f)
        val neighbour = LabelRect(140f, 160f, 170f, 180f)
        val box = requireNotNull(placeNearbyPlotLabel(original, bounds, listOf(neighbour), listOf(curve, axis), 4f, 30f))
        assertEquals(original.width, box.width, 0f)
        assertEquals(original.height, box.height, 0f)
        assertTrue(box.inside(bounds))
        assertTrue(kotlin.math.hypot(box.left - original.left, box.top - original.top) <= 30f)
        assertFalse(box.padded(4f).intersects(curve))
        assertFalse(box.padded(4f).intersects(axis))
        assertFalse(box.padded(4f).overlaps(neighbour))
    }

    @Test fun `nearby placement keeps edge labels whole and leaves unobstructed labels unchanged`() {
        val bounds = LabelRect(0f, 0f, 400f, 300f)
        val original = LabelRect(0f, 160f, 30f, 180f)
        assertEquals(original, placeNearbyPlotLabel(original, bounds, emptyList(), emptyList(), 4f, 30f))
        val curve = LabelObstacleLine(2f, 140f, 2f, 200f)
        val box = requireNotNull(placeNearbyPlotLabel(original, bounds, emptyList(), listOf(curve), 4f, 30f))
        assertTrue(box.inside(bounds))
        assertEquals(original.width, box.width, 0f)
        assertFalse(box.padded(4f).intersects(curve))
    }

    @Test fun `crowded ticks request fallback instead of jumping to a distant gap`() {
        val original = LabelRect(100f, 160f, 130f, 180f)
        val bounds = LabelRect(0f, 0f, 400f, 300f)
        val blocked = LabelRect(60f, 120f, 170f, 220f)
        assertNull(placeNearbyPlotLabel(original, bounds, listOf(blocked), emptyList(), 4f, 30f))
    }

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
