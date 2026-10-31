package com.moge.app.ui.solve

import com.moge.app.data.llm.AnswerUsage
import com.moge.app.data.llm.AnswerUsageCodec
import com.moge.app.data.llm.UsageSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 页脚用量文案与语音结果拼接：纯函数。 */
class SolveFormattingTest {

    @Test
    fun `usage label shows only fields the provider gave`() {
        assertEquals(
            "旧版单次 · 输入 1.2k · 缓存 900 · 输出 860",
            usageLabel("""{"inputTokens":1234,"cachedInputTokens":900,"outputTokens":860}"""),
        )
        assertEquals("旧版单次 · 输出 42", usageLabel("""{"outputTokens":42}"""))
        assertEquals("旧版单次 · 输入 2k · 输出 5", usageLabel("""{"inputTokens":2000,"cachedInputTokens":0,"outputTokens":5}"""))
    }

    @Test fun `answer footer displays sums and missing coverage honestly`() {
        val total = AnswerUsage().plus(UsageSample(1000, 800, 200)).plus(UsageSample(500, 400, 300))
        assertEquals("输入 1.5k · 缓存 1.2k · 输出 500", usageLabel(AnswerUsageCodec.encode(total)))
        assertEquals("输入 ≥1.5k · 缓存 ≥1.2k · 输出 ≥500 · 部分用量未返回",
            usageLabel(AnswerUsageCodec.encode(total.plus(null))))
        assertEquals("用量未返回", usageLabel(AnswerUsageCodec.encode(AnswerUsage().plus(null))))
        assertEquals("输入 未知 · 输出 42 · 部分用量未返回",
            usageLabel(AnswerUsageCodec.encode(AnswerUsage().plus(UsageSample(null, null, 42)))))
        assertEquals("输入 ≥1.2k · 输出 ≥10 · 部分用量未返回",
            usageLabel(AnswerUsageCodec.encode(AnswerUsage().plus(UsageSample(1260, null, 10)).plus(null))))
    }

    @Test fun `cache coverage and a saved prefix cannot claim complete totals`() {
        val total = AnswerUsage().plus(UsageSample(1000, 800, 200)).plus(UsageSample(500, null, 300))
        assertEquals("输入 1.5k · 缓存 ≥800 · 输出 500", usageLabel(AnswerUsageCodec.encode(total)))
        assertEquals("输入 ≥1.5k · 缓存 ≥800 · 输出 ≥500 · 前段用量缺失",
            usageLabel(AnswerUsageCodec.encode(total.copy(priorUsageUnknown = true))))
    }

    @Test fun `average speed uses exact multi-request output and whole answer duration`() {
        val total = AnswerUsage().plus(UsageSample(1000, 800, 1260, reasoningTokens = 1000))
            .plus(UsageSample(500, null, 315, reasoningTokens = 200))
        assertEquals(63.0, averageOutputTokensPerSecond(total, 25_000)!!, 0.0001)
        // Reasoning is already in output; missing cache coverage does not make output incomplete.
        assertEquals("输入 1.5k · 缓存 ≥800 · 输出 1.6k · 平均 63.0 tokens/s",
            usageLabel(AnswerUsageCodec.encode(total), durationMs = 25_000))
    }

    @Test fun `average speed is omitted without full output coverage or positive elapsed time`() {
        val total = AnswerUsage().plus(UsageSample(1000, 0, 200))
        for (durationMs in listOf(0L, -1L)) {
            assertNull(averageOutputTokensPerSecond(total, durationMs))
        }
        for (unknown in listOf(AnswerUsage(), total.plus(null),
            total.plus(UsageSample(100, 0, null)), total.copy(priorUsageUnknown = true))) {
            assertNull(averageOutputTokensPerSecond(unknown, 10_000))
            org.junit.Assert.assertFalse(usageLabel(AnswerUsageCodec.encode(unknown), durationMs = 10_000)
                .orEmpty().contains("tokens/s"))
        }
        // A historical single-request sample cannot be divided by the duration of a whole answer.
        assertEquals("旧版单次 · 输出 200", usageLabel("""{"outputTokens":200}""", durationMs = 10_000))
        assertEquals("输入 1k · 输出 200", usageLabel(AnswerUsageCodec.encode(total)))
    }

    @Test fun `speed handles subsecond timing missing input and very slow output`() {
        val total = AnswerUsage().plus(UsageSample(null, null, 1))
        assertEquals("输入 未知 · 输出 1 · 部分用量未返回 · 平均 4.0 tokens/s",
            usageLabel(AnswerUsageCodec.encode(total), durationMs = 250))
        assertEquals("输入 未知 · 输出 1 · 部分用量未返回 · 平均 <0.1 tokens/s",
            usageLabel(AnswerUsageCodec.encode(total), durationMs = 20_000))
        assertEquals(0.0, averageOutputTokensPerSecond(AnswerUsage().plus(UsageSample(1, 0, 0)), 1000)!!, 0.0)
    }

    @Test
    fun `usage label is null when unknown or malformed`() {
        assertNull(usageLabel(""))
        assertNull(usageLabel("""{"source":"x"}"""))
        assertNull(usageLabel("not json"))
    }

    @Test
    fun `compact count`() {
        assertEquals("999", compactCount(999))
        assertEquals("12.5k", compactCount(12_500))
        assertEquals("250k", compactCount(250_400))
    }

    @Test fun `sharing and collecting use persistent question association`() {
        val q1 = SolveItem.Question("q1", "第一题", emptyList(), "", true)
        val q2 = SolveItem.Question("q2", "第二题", emptyList(), "", false)
        val a = SolveItem.Answer("a", AnswerState.COMPLETED, "答案", replyToMessageId = "q1")
        org.junit.Assert.assertEquals(q1, questionForAnswer(listOf(q1, q2, a), a))
    }

}
