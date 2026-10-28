package com.moge.app.data.llm

import org.junit.Assert.*
import org.junit.Test

class AnswerUsageTest {
    @Test fun `totals count repeated history inputs and keep subsets separate`() {
        val total = AnswerUsage()
            .plus(UsageSample(1000, 800, 200, reasoningTokens = 120))
            .plus(UsageSample(1500, 1000, 300, reasoningTokens = 200))
            .plus(UsageSample(1800, 1500, 400, reasoningTokens = 250))
        assertEquals(3, total.requestCount)
        assertEquals(4300L, total.inputTokens)
        assertEquals(3300L, total.cachedInputTokens)
        assertEquals(900L, total.outputTokens)
        assertEquals(570L, total.reasoningTokens)
        assertEquals(3, total.inputSamples)
        assertEquals(total, AnswerUsageCodec.decode(AnswerUsageCodec.encode(total)))
    }

    @Test fun `missing requests and fields retain known subtotals and coverage`() {
        val total = AnswerUsage().plus(null)
            .plus(UsageSample(100, null, 0))
            .plus(UsageSample(null, 0, 40))
        assertEquals(3, total.requestCount)
        assertEquals(2, total.requestsWithUsage)
        assertEquals(100L, total.inputTokens)
        assertEquals(0L, total.cachedInputTokens)
        assertEquals(40L, total.outputTokens)
        assertNull(total.reasoningTokens)
        assertEquals(1, total.inputSamples)
        assertEquals(1, total.cachedInputSamples)
        assertEquals(2, total.outputSamples)
        assertEquals(0, total.reasoningSamples)
        assertNull(AnswerUsage().plus(null).inputTokens)
    }

    @Test fun `stream cumulative chunks are reduced before adding an HTTP request`() {
        val stream = UsageAccumulator("test")
        stream.accept(UsageSample(100, 80, 20))
        stream.accept(UsageSample(100, 80, 60))
        stream.accept(null)
        val total = AnswerUsage().plus(stream.result()).plus(UsageSample(200, 100, 10))
        assertEquals(2, total.requestCount)
        assertEquals(300L, total.inputTokens)
        assertEquals(70L, total.outputTokens)
    }

    @Test fun `legacy missing or future schemas cannot masquerade as totals`() {
        assertNull(AnswerUsageCodec.decode(""))
        assertNull(AnswerUsageCodec.decode("[]"))
        assertNull(AnswerUsageCodec.decode("""{"inputTokens":123}"""))
        assertNull(AnswerUsageCodec.decode("""{"scope":"answer","version":2}"""))
        assertNull(AnswerUsageCodec.decode("""{"scope":[]}"""))
    }

    @Test fun `preparation totals survive snapshot persistence and adding the final request`() {
        val prep = AnswerUsage().plus(UsageSample(200, 0, 30))
        val snapshot = RequestSnapshot(preparationUsage = prep)
        val restored = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))!!
        val total = restored.preparationUsage!!.plus(UsageSample(800, 500, 400))
        assertEquals(1000L, total.inputTokens)
        assertEquals(430L, total.outputTokens)
        assertEquals(2, total.requestCount)
    }
}
