package com.moge.app.imaging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PhotoWorkPriorityTest {
    @Test fun `background pauses at its next checkpoint until all saves finish`() {
        val priority = PhotoWorkPriority()
        val first = priority.beginSave()
        val second = priority.beginSave()
        val checked = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val resumed = pool.submit { priority.backgroundCheckpoint { checked.countDown() } }
            assertTrue(checked.await(2, TimeUnit.SECONDS))
            assertFalse(resumed.isDone)
            first.close()
            first.close() // A late duplicate release cannot release the second save.
            assertFalse(resumed.isDone)
            second.close()
            resumed.get(2, TimeUnit.SECONDS)
        } finally { first.close(); second.close(); pool.shutdownNow() }
    }

    @Test fun `a paused background operation still observes cancellation`() {
        val priority = PhotoWorkPriority()
        val saving = priority.beginSave()
        var checks = 0
        try {
            assertThrows(CancellationException::class.java) {
                priority.backgroundCheckpoint { if (++checks == 2) throw CancellationException("cancelled") }
            }
            assertEquals(2, checks)
        } finally { saving.close() }
    }
}
