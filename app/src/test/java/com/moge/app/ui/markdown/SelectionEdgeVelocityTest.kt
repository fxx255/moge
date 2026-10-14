package com.moge.app.ui.markdown

import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SelectionEdgeVelocityTest {
    private val viewport = RectF(0f, 100f, 400f, 700f)

    @Test fun `middle stays still while both edges accelerate smoothly and symmetrically`() {
        assertEquals(0f, selectionEdgeVelocity(400f, viewport, 1f), 0f)
        assertEquals(0f, selectionEdgeVelocity(164f, viewport, 1f), 0f)
        assertEquals(0f, selectionEdgeVelocity(636f, viewport, 1f), 0f)
        val distances = listOf(60f, 40f, 20f, 0f)
        var previous = 0f
        for (distance in distances) {
            val bottom = selectionEdgeVelocity(viewport.bottom - distance, viewport, 1f)
            val top = selectionEdgeVelocity(viewport.top + distance, viewport, 1f)
            assertTrue(bottom > previous)
            assertEquals(-bottom, top, 0.001f)
            previous = bottom
        }
        assertEquals(900f, previous, 0.001f)
    }

    @Test fun `outside the viewport is capped and density preserves perceived speed`() {
        assertEquals(-900f, selectionEdgeVelocity(-1000f, viewport, 1f), 0f)
        assertEquals(900f, selectionEdgeVelocity(2000f, viewport, 1f), 0f)
        val doubled = RectF(0f, 200f, 800f, 1400f)
        assertEquals(selectionEdgeVelocity(680f, viewport, 1f) * 2,
            selectionEdgeVelocity(1360f, doubled, 2f), 0.01f)
    }

    @Test fun `small viewport does not overlap edge bands and invalid geometry cannot scroll`() {
        assertEquals(0f, selectionEdgeVelocity(130f, RectF(0f, 100f, 400f, 160f), 3f), 0f)
        assertEquals(0f, selectionEdgeVelocity(100f, RectF(), 1f), 0f)
        assertEquals(0f, selectionEdgeVelocity(Float.NaN, viewport, 1f), 0f)
        assertEquals(0f, selectionEdgeVelocity(100f, viewport, 0f), 0f)
    }

    @Test fun `word selection preserves Chinese and complete surrogate pairs`() {
        val text = "hello 中文 😀 world"
        val word = selectionWord(text, 2)
        assertEquals("hello", text.substring(word.start, word.end))
        for (index in listOf(text.indexOf("中"), text.indexOf("😀"), text.indexOf("😀") + 1)) {
            val chosen = selectionWord(text, index)
            val copied = text.substring(chosen.start, chosen.end)
            assertTrue(copied.isNotEmpty())
            assertFalse(Character.isLowSurrogate(copied.first()))
            assertFalse(Character.isHighSurrogate(copied.last()))
        }
    }
}
