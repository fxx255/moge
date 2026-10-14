package com.moge.app.data.llm

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class GenerationProgressWatchdogTest {
    @Test fun `active output can exceed the old total time limit`() {
        var now = 0L
        val guard = GenerationProgressWatchdog(MonotonicClock { now }, 100L)
        repeat(1000) { now += 99; guard.check(); guard.progress() }
    }

    @Test fun `heartbeat only stream times out without counting heartbeat as content`() = runBlocking {
        var now = 0L
        val guard = GenerationProgressWatchdog(MonotonicClock { now }, 100L)
        val error = runCatching {
            Buffer().writeUtf8(": ping\n\n: ping\n\n: ping\n\n").readSseEvents(onLine = { now += 25; guard.check() }) {
                guard.progress(); true
            }
        }.exceptionOrNull()
        assertTrue(error is ModelException)
        assertEquals(ModelException.Kind.NETWORK, (error as ModelException).kind)
    }
}
