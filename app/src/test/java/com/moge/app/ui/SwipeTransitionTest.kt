package com.moge.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeTransitionTest {
    @Test fun `system back reversing a previous swipe has no horizontal transition`() {
        val swipe = SwipeTransition("conversation-entry", "new-entry", 1)
        assertEquals(1, swipe.directionFor("conversation-entry", "new-entry"))
        assertEquals(0, swipe.directionFor("new-entry", "conversation-entry"))
        val historySwipe = SwipeTransition("new-entry", "history-entry", 1)
        assertEquals(0, historySwipe.directionFor("history-entry", "new-entry"))
    }
    @Test fun `swiping to a reused entry has the explicit gesture direction regardless of pop`() {
        val swipe = SwipeTransition("new-entry", "conversation-entry", -1)
        assertEquals(-1, swipe.directionFor("new-entry", "conversation-entry"))
    }
    @Test fun `matching routes with fresh entries cannot inherit an earlier transition`() {
        val swipe = SwipeTransition("solve-1", "solve-2", 1)
        assertEquals(0, swipe.directionFor("solve-3", "solve-2"))
        assertEquals(0, swipe.directionFor("solve-1", "solve-4"))
    }
}
