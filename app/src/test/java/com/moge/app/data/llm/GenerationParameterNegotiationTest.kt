package com.moge.app.data.llm

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class GenerationParameterNegotiationTest {
    private fun payload(key: String = "max_tokens") = buildJsonObject { put(key, Int.MAX_VALUE); put("reasoning_effort", "medium"); put("messages", "unchanged") }
    private fun rejected(body: String, status: Int = 400) = ModelException(ModelException.Kind.SERVER, "HTTP $status: $body", rejectedHttpStatus = status)

    @Test fun `format fallback preserves responses text options and never retries unrelated or started responses`() {
        val initial = JsonObject(payload() + ("text" to buildJsonObject {
            put("verbosity", "low"); put("format", buildJsonObject { put("type", "json_object") })
        }))
        val next = GenerationParameterNegotiation(initial).afterRejection(rejected("text.format is unsupported"))!!
        assertEquals(buildJsonObject { put("verbosity", "low") }, next["text"])
        assertEquals(JsonObject(initial - "text"), JsonObject(next - "text"))
        for (status in listOf(401, 403, 429, 500)) {
            assertNull(GenerationParameterNegotiation(initial).afterRejection(rejected("text.format is unsupported", status)))
        }
        assertNull(GenerationParameterNegotiation(initial).afterRejection(rejected("model is not supported")))
        assertNull(GenerationParameterNegotiation(initial).afterRejection(ModelException(
            ModelException.Kind.SERVER, "text.format is unsupported", // SSE errors after HTTP 200 have no rejection status.
        )))
    }

    @Test fun `extracts explicit protocol upper bounds without fixed provider caps`() {
        for (key in listOf("max_tokens", "max_completion_tokens", "max_output_tokens")) {
            for (detail in listOf("too large, maximum is 32768", "must be <= 32768", "must be less than or equal to 32,768",
                "上限为32768", "不得超过 32768", "too large, max is 32768", "{\"type\":\"less_than_equal\",\"ctx\":{\"le\":32768}}")) {
                val next = GenerationParameterNegotiation(payload(key)).afterRejection(rejected("$key $detail", 422))!!
                assertEquals("$key: $detail", 32768, next[key]!!.jsonPrimitive.int)
                assertEquals("unchanged", next["messages"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun `sse wrapped range error from gateway uses its actual inclusive maximum`() {
        val body = """data: {"error":{"code":"invalid_parameter_error","param":null,"message":"Range of max_tokens should be [1, 131072]","type":"invalid_request_error"},"id":"chatcmpl-test"}"""
        val initial = payload()
        val negotiation = GenerationParameterNegotiation(initial)
        val next = negotiation.afterRejection(rejected(body))!!
        assertEquals(131072, next["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(JsonObject(initial - "max_tokens"), JsonObject(next - "max_tokens"))
        assertNull("The same rejection must not create a retry loop", negotiation.afterRejection(rejected(body)))
    }

    @Test fun `numeric ranges apply to every output parameter and respect exclusive ends`() {
        for (key in listOf("max_tokens", "max_completion_tokens", "max_output_tokens")) {
            for ((detail, upper) in listOf(
                "should be [1,131072]" to 131072,
                "must be in the range [1, 131,072]" to 131072,
                "must be within (0, 65536]" to 65536,
                "must be in [1, 65536)" to 65535,
                "must be between 1 and 32768" to 32768,
                "的取值范围应为 [1，32768]" to 32768,
            )) {
                val next = GenerationParameterNegotiation(payload(key)).afterRejection(rejected("$key $detail", 422))!!
                assertEquals("$key $detail", upper, next[key]!!.jsonPrimitive.int)
            }
        }
    }

    @Test fun `range parsing never invents limits or retries unrelated rejections`() {
        for (detail in listOf(
            "max_tokens must be an integer greater than 0",
            "max_tokens invalid type; temperature should be [0, 2]",
            "max_tokens value rejected; max_output_tokens should be [1, 32768]",
            "max_tokens should be [9, 8]", "max_tokens should be (8, 9)",
            "max_tokens should be [0, 0]", "max_tokens should be [1, 2147483648]",
            "max_tokens should be [1, 131072", "max_tokens should be [1, 13,1072]",
            "max_tokens should be [1, unknown]",
        )) {
            assertNull(detail, GenerationParameterNegotiation(payload()).afterRejection(rejected(detail)))
        }
        for (status in listOf(401, 403, 429, 500, 503)) {
            assertNull(GenerationParameterNegotiation(payload()).afterRejection(rejected("max_tokens should be [1, 131072]", status)))
        }
        val bounded = JsonObject(payload() + ("max_tokens" to JsonPrimitive(65536)))
        assertNull(GenerationParameterNegotiation(bounded).afterRejection(rejected("max_tokens should be [1, 131072]")))
    }

    @Test fun `token spelling budget and effort rejection negotiate in sequence`() {
        val negotiation = GenerationParameterNegotiation(payload())
        val modern = negotiation.afterRejection(rejected("Unsupported parameter: 'max_tokens'. Use 'max_completion_tokens' instead."))!!
        assertFalse(modern.containsKey("max_tokens"))
        assertEquals(Int.MAX_VALUE, modern["max_completion_tokens"]!!.jsonPrimitive.int)
        val bounded = negotiation.afterRejection(rejected("max_completion_tokens must be <= 180000"))!!
        assertEquals(180000, bounded["max_completion_tokens"]!!.jsonPrimitive.int)
        val effort = negotiation.afterRejection(rejected("reasoning_effort should be one of 'low', 'high', 'xhigh'"))!!
        assertEquals("high", effort["reasoning_effort"]!!.jsonPrimitive.content)
        val optional = negotiation.afterRejection(rejected("Unknown parameter reasoning_effort"))!!
        assertFalse(optional.containsKey("reasoning_effort"))
        assertEquals(180000, optional["max_completion_tokens"]!!.jsonPrimitive.int)
    }

    @Test fun `both token spellings rejected use defaults rather than cycling`() {
        val negotiation = GenerationParameterNegotiation(payload())
        negotiation.afterRejection(rejected("unknown max_tokens"))!!
        val next = negotiation.afterRejection(rejected("unknown max_completion_tokens"))!!
        assertTrue(next.keys.none { it.startsWith("max_") })
        assertNull(negotiation.afterRejection(rejected("unknown max_tokens")))
    }

    @Test fun `nested effort rejection preserves other reasoning options`() {
        val initial = buildJsonObject { put("max_output_tokens", 500000); put("reasoning", buildJsonObject { put("effort", "medium"); put("summary", "auto") }) }
        val negotiation = GenerationParameterNegotiation(initial)
        val next = negotiation.afterRejection(rejected("reasoning.effort: Input should be 'low' or 'high'"))!!
        assertEquals("high", next["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        val optional = negotiation.afterRejection(rejected("reasoning.effort unsupported"))!!
        assertEquals(setOf("summary"), optional["reasoning"]!!.jsonObject.keys)
        assertEquals(500000, optional["max_output_tokens"]!!.jsonPrimitive.int)
    }

    @Test fun `context rejection uses maximum remaining space without trimming messages`() {
        val request = payload()
        val next = GenerationParameterNegotiation(request).afterRejection(rejected(
            "This model's maximum context length is 131072 tokens. However, you requested 2147487671 tokens (4024 in the messages, 2147483647 in the completion).",
        ))!!
        assertEquals(127048, next["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(request["messages"], next["messages"])
        assertNull(GenerationParameterNegotiation(request).afterRejection(rejected("maximum context length is 131072 tokens")))
    }

    @Test fun `explicitly unsupported optional tuning preserves negotiated output and effort`() {
        val initial = JsonObject(payload() + ("temperature" to JsonPrimitive(.4)))
        val next = GenerationParameterNegotiation(initial).afterRejection(rejected(
            "Unsupported value: 'temperature' does not support 0.4 with this model. Only the default (1) value is supported.",
        ))!!
        assertFalse(next.containsKey("temperature"))
        assertEquals(initial["max_tokens"], next["max_tokens"])
        assertEquals(initial["reasoning_effort"], next["reasoning_effort"])
    }

    @Test fun `no bound delegates to server but generic errors never retry`() {
        assertFalse(GenerationParameterNegotiation(payload()).afterRejection(rejected("max_tokens exceeds limit"))!!.containsKey("max_tokens"))
        for (body in listOf("messages invalid", "max_tokens must be an integer greater than 0", "max_tokens must be greater than 0", "max_tokens invalid type", "content policy rejected")) {
            assertNull(body, GenerationParameterNegotiation(payload()).afterRejection(rejected(body)))
        }
        for (status in listOf(401, 403, 429, 500, 503)) assertNull(GenerationParameterNegotiation(payload()).afterRejection(rejected("max_tokens maximum is 5000", status)))
        assertNull(GenerationParameterNegotiation(payload()).afterRejection(ModelException(ModelException.Kind.SERVER, "HTTP 400 max_tokens maximum is 5000")))
    }
}
