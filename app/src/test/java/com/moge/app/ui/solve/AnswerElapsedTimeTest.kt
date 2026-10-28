package com.moge.app.ui.solve

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerElapsedTimeTest {
    @Test fun `task origin covers preparation prose generation and page reentry`() {
        assertEquals(3000L, answerElapsedMs(1000, 4000, 0))
        assertEquals(9000L, answerElapsedMs(1000, 10000, 0))
        assertEquals(19000L, answerElapsedMs(1000, 20000, 0))
    }

    @Test fun `completed duration stays fixed and retry origin replaces the previous attempt`() {
        assertEquals(19000L, answerElapsedMs(null, 100000, 19000))
        assertEquals(1000L, answerElapsedMs(99000, 100000, 19000))
        assertEquals(0L, answerElapsedMs(100001, 100000, 0))
    }
}
