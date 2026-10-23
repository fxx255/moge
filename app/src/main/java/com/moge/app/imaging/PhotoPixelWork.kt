package com.moge.app.imaging

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlin.math.min

/** Bounded CPU work on disjoint portions of a photo's existing stripe buffers. */
internal class PhotoPixelWork(
    val parallelism: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 3),
) {
    init { require(parallelism > 0) }
    private val dispatcher = Dispatchers.Default.limitedParallelism(parallelism)

    /**
     * Joins every worker, including on failure or cancellation, before returning to
     * the synchronous native renderer. It can then safely reuse or free its buffers.
     * Bitmap/Mat access and foreground-save checkpoints stay on the calling thread.
     */
    fun forRange(start: Int, end: Int, work: (start: Int, end: Int) -> Unit) {
        require(end >= start)
        val size = end - start
        if (size == 0) return
        val chunks = min(parallelism, size / MIN_PIXELS_PER_WORKER)
        if (chunks < 2) {
            work(start, end)
            return
        }
        runBlocking {
            (0 until chunks).map { chunk ->
                val first = start + (size.toLong() * chunk / chunks).toInt()
                val last = start + (size.toLong() * (chunk + 1) / chunks).toInt()
                async(dispatcher) {
                    ensureActive()
                    work(first, last)
                }
            }.awaitAll()
        }
    }

    companion object {
        // Small images/tail stripes avoid scheduling overhead. Keep CPU capacity
        // for interaction and never create one full-resolution buffer per worker.
        private const val MIN_PIXELS_PER_WORKER = 32 * 1024
        val Shared = PhotoPixelWork()
    }
}
