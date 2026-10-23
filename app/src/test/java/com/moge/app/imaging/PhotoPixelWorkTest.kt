package com.moge.app.imaging

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PhotoPixelWorkTest {
    @Test fun `parallel ranges cover an uneven stripe exactly once and join before the next phase`() {
        val work = PhotoPixelWork(parallelism = 2)
        val visits = AtomicIntegerArray(100_017)
        val bothStarted = CountDownLatch(2)
        val threads = ConcurrentHashMap.newKeySet<Thread>()
        work.forRange(7, 100_010) { first, last ->
            threads += Thread.currentThread()
            bothStarted.countDown()
            assertTrue(bothStarted.await(5, TimeUnit.SECONDS))
            for (p in first until last) visits.incrementAndGet(p)
        }
        assertEquals(2, threads.size)
        work.forRange(7, 100_010) { first, last ->
            for (p in first until last) assertEquals(1, visits.getAndIncrement(p))
        }
        for (p in 0 until visits.length()) assertEquals(if (p in 7 until 100_010) 2 else 0, visits.get(p))
        val caller = Thread.currentThread()
        work.forRange(9, 12) { first, last ->
            assertSame(caller, Thread.currentThread())
            assertEquals(9, first)
            assertEquals(12, last)
        }
    }

    @Test fun `failure and cancellation cannot return while another worker still uses the buffer`() {
        for (error in listOf(IllegalStateException("failed"), CancellationException("cancelled"))) {
            val entered = CountDownLatch(1)
            val throwing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val caller = Executors.newSingleThreadExecutor()
            try {
                val result = caller.submit {
                    PhotoPixelWork(parallelism = 2).forRange(0, 100_000) { first, _ ->
                        if (first == 0) {
                            entered.countDown()
                            try { assertTrue(release.await(5, TimeUnit.SECONDS)) }
                            finally { finished.countDown() }
                        } else {
                            assertTrue(entered.await(5, TimeUnit.SECONDS))
                            throwing.countDown()
                            throw error
                        }
                    }
                }
                assertTrue(throwing.await(5, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { result.get(100, TimeUnit.MILLISECONDS) }
                release.countDown()
                val reported = assertThrows(ExecutionException::class.java) { result.get(5, TimeUnit.SECONDS) }
                assertEquals(error.javaClass, reported.cause!!.javaClass)
                assertEquals(error.message, reported.cause!!.message)
                assertEquals(0L, finished.count)
            } finally { release.countDown(); caller.shutdownNow() }
        }
    }
}
