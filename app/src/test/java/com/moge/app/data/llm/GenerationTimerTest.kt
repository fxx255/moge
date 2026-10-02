package com.moge.app.data.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * 计时与诊断验收（实施文档 §4「最小诊断」、§7.1）。
 *
 * 用**可控单调时钟**断言「收到可展示正文后约 300ms 内更新」这类要求，
 * 不用脆弱的真实 sleep —— 真实睡眠在 CI 上会因为调度抖动而随机失败。
 */
class GenerationTimerTest {

    private class FakeClock : MonotonicClock {
        val nanos = AtomicLong(0)
        fun advance(ms: Long) {
            nanos.addAndGet(ms * 1_000_000)
        }

        override fun nanoTime(): Long = nanos.get()
    }

    @Test
    fun `each stage is recorded once and keeps the first mark`() {
        val clock = FakeClock()
        val timer = GenerationTimer(clock)
        clock.advance(120)
        timer.markReasoning()
        clock.advance(80)
        timer.markAnswerByte()
        clock.advance(300)
        timer.markVisibleText()
        // 第二阶段重复打点不得覆盖首次时间。
        clock.advance(500)
        timer.markVisibleText()
        clock.advance(1000)
        timer.markCompleted()

        val timings = timer.snapshot()
        assertEquals(120L, timings.firstReasoningMs)
        assertEquals(200L, timings.firstAnswerByteMs)
        assertEquals("首次可见正文必须保留", 500L, timings.firstVisibleTextMs)
        assertEquals(2000L, timings.completedMs)
        assertNull(timings.interruptedMs)
    }

    @Test
    fun `visible text within 300ms of first answer byte is measurable`() {
        val clock = FakeClock()
        val timer = GenerationTimer(clock)
        clock.advance(100)
        timer.markAnswerByte()
        clock.advance(250)
        timer.markVisibleText()
        val timings = timer.snapshot()
        val delta = timings.firstVisibleTextMs!! - timings.firstAnswerByteMs!!
        assertTrue("首字节到可见正文应在 300ms 内，实测 $delta ms", delta <= 300)
    }

    @Test
    fun `interruption is recorded separately from completion`() {
        val clock = FakeClock()
        val timer = GenerationTimer(clock)
        clock.advance(700)
        timer.markInterrupted()
        val timings = timer.snapshot()
        assertEquals(700L, timings.interruptedMs)
        assertNull(timings.completedMs)
    }

    @Test
    fun `summary omits missing stages and never includes content`() {
        val clock = FakeClock()
        val timer = GenerationTimer(clock)
        clock.advance(50)
        timer.markVisibleText()
        val summary = timer.snapshot().summary()
        assertTrue(summary.contains("首可见正文 50ms"))
        assertTrue("未发生的阶段不应出现", !summary.contains("完成"))
    }

    @Test
    fun `diagnostics store is bounded and keeps newest entries`() {
        val diagnostics = GenerationDiagnostics()
        repeat(GenerationDiagnostics.MAX_ENTRIES + 25) { index ->
            diagnostics.record(
                GenerationDiagnostics.Entry(
                    requestId = "req-$index",
                    modelKey = "model-x",
                    protocol = "chat_completions",
                    timings = GenerationTimings(),
                    extraRequests = 0,
                    outcome = "COMPLETED",
                ),
            )
        }
        val recent = diagnostics.recent(limit = 500)
        assertEquals("诊断必须是有界的", GenerationDiagnostics.MAX_ENTRIES, recent.size)
        // 保留的是最新的：开头是 25 号（前 25 条被挤掉）。
        assertEquals("req-25", recent.last().requestId)
        assertEquals("req-${GenerationDiagnostics.MAX_ENTRIES + 24}", recent.first().requestId)
    }

    @Test
    fun `diagnostics aggregates cache stats per model`() {
        val diagnostics = GenerationDiagnostics()
        diagnostics.record(
            GenerationDiagnostics.Entry(
                requestId = "a",
                modelKey = "deepseek-chat",
                protocol = "chat_completions",
                timings = GenerationTimings(),
                extraRequests = 1,
                outcome = "COMPLETED",
            ),
        )
        diagnostics.record(
            GenerationDiagnostics.Entry(
                requestId = "b",
                modelKey = "deepseek-chat",
                protocol = "chat_completions",
                timings = GenerationTimings(),
                extraRequests = 0,
                outcome = "NETWORK",
            ),
        )
        // usage 由**逐次 HTTP 上报**统计，不再随终态 Entry 一起记。
        diagnostics.recordHttpUsage(
            "deepseek-chat",
            UsageSample(inputTokens = 1000, cachedInputTokens = 900, outputTokens = 5),
        )
        diagnostics.recordHttpUsage("deepseek-chat", null)
        val stats = diagnostics.cacheStats()["deepseek-chat"]
        assertNotNull(stats)
        assertEquals(1, stats!!.validSamples)
        assertEquals(1, stats.unknownSamples)
        assertEquals(0.9, stats.cachedRatio!!, 1e-9)
    }

    @Test
    fun `summary mentions extra requests when continuation happened`() {
        val diagnostics = GenerationDiagnostics()
        diagnostics.record(
            GenerationDiagnostics.Entry(
                requestId = "a",
                modelKey = "m",
                protocol = "responses",
                timings = GenerationTimings(completedMs = 1234),
                extraRequests = 2,
                outcome = "COMPLETED",
            ),
        )
        val summary = diagnostics.summary()
        assertTrue(summary.contains("额外请求：2 次"))
        assertTrue(summary.contains("responses"))
    }
}
