package com.moge.app.ui.capture

import org.junit.Assert.*
import org.junit.Test

class PaperFocusDetectionTest {
    private val width = 64
    private val height = 48
    private fun page(light: Int = 220): ByteArray = ByteArray(width * height) { 45 }.also { pixels ->
        for (y in 5..42) for (x in 8..55) pixels[y * width + x] = light.toByte()
        for (y in 10..36 step 5) for (x in 14..47 step 4) pixels[y * width + x] = 30
    }

    @Test fun `printed light and shaded paper supplies a focus point inside the text`() {
        for (light in listOf(220, 110)) {
            val found = detectPaperFocus(page(light), width, height)
            assertNotNull("paper luminance=$light", found)
            assertTrue(found!!.x in .3f.. .7f && found.y in .3f.. .7f)
            assertTrue(found.coverage > .5f)
        }
    }

    @Test fun `uniform walls empty paper darkness and tiny bright objects do not trigger page focus`() {
        for (light in listOf(0, 40, 180, 255)) {
            assertNull(detectPaperFocus(ByteArray(width * height) { light.toByte() }, width, height))
        }
        val blank = page().also { pixels ->
            for (y in 5..42) for (x in 8..55) pixels[y * width + x] = 220.toByte()
        }
        assertNull(detectPaperFocus(blank, width, height))
        assertNull(detectPaperFocus(ByteArray(0), width, height))
        assertNull(detectPaperFocus(ByteArray(width * height) { if (it in 300..310) 240.toByte() else 40 }, width, height))
    }

    @Test fun `stable page focuses once jitter is ignored and a moved or newly appearing page refocuses`() {
        val tracker = PaperFocusTracker()
        val page = PaperFocusCandidate(.5f, .5f, .6f)
        assertNull(tracker.update(page, 0))
        assertNull(tracker.update(page.copy(x = .51f), 400))
        assertEquals(page, tracker.update(page, 800))
        for (time in 1_200L..8_000L step 400) assertNull(tracker.update(page, time))
        val moved = page.copy(x = .7f)
        assertNull(tracker.update(moved, 8_400))
        assertNull(tracker.update(moved, 8_800))
        assertEquals(moved, tracker.update(moved, 9_200))
        tracker.update(null, 11_000)
        tracker.update(moved, 11_400)
        tracker.update(moved, 11_800)
        assertEquals(moved, tracker.update(moved, 12_200))
    }

    @Test fun `manual interaction suppresses queued autofocus and lifecycle reset allows reacquisition`() {
        val tracker = PaperFocusTracker()
        val page = PaperFocusCandidate(.5f, .5f, .6f)
        tracker.update(page, 0)
        tracker.update(page, 400)
        tracker.suppress(500)
        tracker.suppress(600)
        for (time in 800L..6_400L step 400) assertNull(tracker.update(page, time))
        for (time in 6_800L..8_400L step 400) assertNull(tracker.update(page, time))
        tracker.reset()
        tracker.update(page, 9_000)
        tracker.update(page, 9_400)
        assertEquals(page, tracker.update(page, 9_800))
    }

    @Test fun `motion does not focus and scene changes respect the cooldown`() {
        val tracker = PaperFocusTracker()
        for (i in 0..8) assertNull(tracker.update(PaperFocusCandidate(.2f + i * .06f, .5f, .4f), i * 400L))
        val page = PaperFocusCandidate(.5f, .5f, .6f)
        tracker.reset()
        tracker.update(page, 0); tracker.update(page, 400)
        assertEquals(page, tracker.update(page, 800))
        val closer = page.copy(coverage = .85f)
        tracker.update(closer, 1_200); tracker.update(closer, 1_600)
        assertNull(tracker.update(closer, 2_000))
        assertEquals(closer, tracker.update(closer, 4_000))
    }
}
