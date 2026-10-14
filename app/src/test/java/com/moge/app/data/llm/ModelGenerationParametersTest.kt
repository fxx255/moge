package com.moge.app.data.llm

import com.moge.app.data.credential.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelGenerationParametersTest {
    private fun native(fields: JsonObject) = fields["reasoning_effort"]?.jsonPrimitive?.content
        ?: fields["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content

    @Test fun `every endpoint and model uses the same undeclared protocol strategy`() {
        for (protocol in AiApiProtocol.entries) for (effort in AiReasoningEffort.entries) {
            val expected = ModelGenerationParameters.fields("https://relay.example/v1", "new-model", effort, protocol)
            for ((base, model) in listOf("https://api.deepseek.com" to "deepseek-v4-pro",
                "https://api.stepfun.com/v1" to "step-5-preview", "https://openrouter.ai/api/v1" to "unknown",
                "https://any-gateway.example/v1" to "cn:deepseek-v4-flash")) {
                assertEquals(expected, ModelGenerationParameters.fields(base, model, effort, protocol))
            }
            val key = if (protocol == AiApiProtocol.RESPONSES) "max_output_tokens" else "max_tokens"
            assertEquals(Int.MAX_VALUE, expected[key]!!.jsonPrimitive.int)
            assertEquals(effort.name.lowercase(), native(expected))
            assertFalse(expected.containsKey("thinking"))
        }
    }

    @Test fun `metadata maps supported levels and preserves full output capability on both protocols`() {
        val capability = ModelCapability(1048576, listOf("low", "high", "xhigh"), canDisableThinking = true)
        for (protocol in AiApiProtocol.entries) {
            val key = if (protocol == AiApiProtocol.RESPONSES) "max_output_tokens" else "max_tokens"
            for ((effort, expected) in AiReasoningEffort.entries.zip(listOf("low", "high", "xhigh"))) {
                val fields = ModelGenerationParameters.fields("https://any.example", "any", effort, protocol, capability = capability)
                assertEquals(1048576, fields[key]!!.jsonPrimitive.int)
                assertEquals(expected, native(fields))
            }
            assertEquals("none", native(ModelGenerationParameters.fields("https://any.example", "any", AiReasoningEffort.HIGH,
                protocol, finalAnswer = true, capability = capability)))
        }
    }

    @Test fun `declared completion parameter overrides chat legacy spelling`() {
        val fields = ModelGenerationParameters.fields("https://any.example", "any", AiReasoningEffort.MEDIUM,
            AiApiProtocol.CHAT_COMPLETIONS, capability = ModelCapability(130000, chatOutputParameter = "max_completion_tokens"))
        assertEquals(130000, fields["max_completion_tokens"]!!.jsonPrimitive.int)
        assertFalse(fields.containsKey("max_tokens"))
    }

    @Test fun `fixed effort and explicitly unsupported reasoning remain honest`() {
        val fixed = ModelCapability(1048576, listOf("medium"), fixedEffort = "medium")
        for (effort in AiReasoningEffort.entries) for (finalAnswer in listOf(false, true)) {
            assertEquals("medium", native(ModelGenerationParameters.fields("https://any.example", "any", effort,
                AiApiProtocol.RESPONSES, finalAnswer, fixed)))
        }
        assertTrue(ModelGenerationParameters.reasoningStatus("https://any.example", "any", AiReasoningEffort.HIGH,
            AiApiProtocol.RESPONSES, fixed).contains("固定为 medium"))
        assertEquals(setOf("max_tokens"), ModelGenerationParameters.fields("https://any.example", "any", AiReasoningEffort.HIGH,
            AiApiProtocol.CHAT_COMPLETIONS, capability = fixed.copy(supportsReasoning = false)).keys)
    }

    @Test fun `undeclared recovery lowers standard effort and status does not claim execution`() {
        for (protocol in AiApiProtocol.entries) {
            assertEquals("low", native(ModelGenerationParameters.fields("https://any.example", "any", AiReasoningEffort.HIGH, protocol, true)))
            assertTrue(ModelGenerationParameters.reasoningStatus("https://any.example", "any", AiReasoningEffort.HIGH, protocol).contains("通用协议参数"))
        }
    }
}
