package com.moge.app.data.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ReasoningUsageTest {
    @Test fun `chat and responses parse native reasoning token counts`() {
        for (details in listOf("completion_tokens_details", "output_tokens_details")) {
            val sample = UsageParser.parseUsageObject(Json.parseToJsonElement("""{"output_tokens":200,"$details":{"reasoning_tokens":120}}""").jsonObject)
            assertEquals(120L, sample.reasoningTokens)
        }
    }

    @Test fun `missing conflicting and impossible reasoning counts stay unknown`() {
        for (raw in listOf("""{"output_tokens":200}""", """{"output_tokens":100,"reasoning_tokens":101}""",
            """{"output_tokens":200,"reasoning_tokens":1,"completion_tokens_details":{"reasoning_tokens":2}}""")) {
            assertNull(UsageParser.parseUsageObject(Json.parseToJsonElement(raw).jsonObject).reasoningTokens)
        }
    }

    @Test fun `diagnostics clearly distinguish a reported zero from a missing reasoning count`() {
        val diagnostics = GenerationDiagnostics()
        diagnostics.recordHttpUsage("model", UsageSample(null, null, 10L, reasoningTokens = 0))
        assertTrue(diagnostics.summary().contains("其中推理 0"))
        diagnostics.recordHttpUsage("model", UsageSample(null, null, 10L))
        assertTrue(diagnostics.summary().contains("供应商未提供"))
        diagnostics.clear()
        assertFalse(diagnostics.summary().contains("最近请求 token"))
    }
}
