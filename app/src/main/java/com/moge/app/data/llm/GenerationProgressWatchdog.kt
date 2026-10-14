package com.moge.app.data.llm

/** Continuing content can run as long as needed; keep-alive comments do not count as generation. */
internal class GenerationProgressWatchdog(
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
    private val idleNanos: Long = 10L * 60 * 1_000_000_000,
) {
    private var lastProgress = clock.nanoTime()
    fun progress() { lastProgress = clock.nanoTime() }
    fun check() {
        if (clock.nanoTime() - lastProgress >= idleNanos) throw ModelException(ModelException.Kind.NETWORK,
            "模型超过 10 分钟没有返回新的推理或正文，已保留已有内容")
    }
}
