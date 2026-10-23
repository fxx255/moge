package com.moge.app.data.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class ModelCapabilityCatalogTest {
    private val raw = """{"data":[
        {"id":"cn:step-5-preview","max_output_tokens":64000,"supports_reasoning":true,"reasoning_supported_efforts":["low","medium","high"]},
        {"id":"cn:deepseek-v4-flash","max_output_tokens":50000,"can_disable_thinking":true,"reasoning_supported_efforts":["low","high","xhigh"]},
        {"id":"cn:kimi-k3-1","max_output_tokens":1048576,"reasoning_effort":"medium","reasoning_supported_efforts":["medium"]},
        {"id":"simple"},{"id":""},{"id":"broken","max_output_tokens":-1,"reasoning_supported_efforts":["bogus",{},"high","high"]}
    ]}"""

    @Test fun `parses exact model ids and declaration fields without inventing capabilities`() {
        val catalog = parseModelCatalog(raw)
        assertEquals(5, catalog.ids.size)
        assertEquals(64000, catalog.capabilities["cn:step-5-preview"]!!.maxOutputTokens)
        assertEquals(50000, catalog.capabilities["cn:deepseek-v4-flash"]!!.maxOutputTokens)
        assertEquals(true, catalog.capabilities["cn:deepseek-v4-flash"]!!.canDisableThinking)
        assertEquals("medium", catalog.capabilities["cn:kimi-k3-1"]!!.fixedEffort)
        assertNull(catalog.capabilities["simple"]!!.maxOutputTokens)
        assertNull(catalog.capabilities["broken"]!!.maxOutputTokens)
        assertEquals(listOf("high"), catalog.capabilities["broken"]!!.supportedEfforts)
        assertTrue(parseModelCatalog("not json").ids.isEmpty())
    }

    private fun transport(block: (okhttp3.Request) -> Pair<Int, String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code, body) = block(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody()).build()
    }.build()

    @Test fun `structured output declarations retain explicit false and unknown support`() {
        val catalog = parseModelCatalog("""{"data":[
            {"id":"schema","supports_structured_outputs":true},
            {"id":"parameters","supported_parameters":["structured_outputs","response_format"]},
            {"id":"disabled","supports_json_schema":false,"supports_json_object":false,"supported_parameters":["structured_outputs","response_format"]},
            {"id":"unknown"}
        ]}""").capabilities
        assertEquals(true, catalog["schema"]!!.supportsJsonSchema)
        assertEquals(true, catalog["parameters"]!!.supportsJsonSchema)
        assertEquals(true, catalog["parameters"]!!.supportsJsonObject)
        assertEquals(false, catalog["disabled"]!!.supportsJsonSchema)
        assertEquals(false, catalog["disabled"]!!.supportsJsonObject)
        assertNull(catalog["unknown"]!!.supportsJsonSchema)
        assertNull(catalog["unknown"]!!.supportsJsonObject)
    }

    @Test fun `cache isolates endpoint and credential and refreshes after TTL`() = runBlocking {
        var now = 0L
        var calls = 0
        val cache = ModelCapabilityCatalog(MonotonicClock { now })
        val client = transport { request ->
            calls++
            assertEquals("GET", request.method)
            assertTrue(request.url.encodedPath.endsWith("/models"))
            200 to raw
        }
        val first = cache.resolve(client, "https://gateway.example/v1", "key-a", "cn:step-5-preview")
        assertEquals(64000, first!!.maxOutputTokens)
        cache.resolve(client, "https://gateway.example/v1", "key-a", "cn:deepseek-v4-flash")
        assertEquals(1, calls)
        cache.resolve(client, "https://gateway.example/v1", "key-b", "cn:step-5-preview")
        cache.resolve(client, "https://other.example/v1", "key-a", "cn:step-5-preview")
        assertEquals(3, calls)
        now = java.util.concurrent.TimeUnit.MINUTES.toNanos(6)
        cache.resolve(client, "https://gateway.example/v1", "key-a", "cn:step-5-preview")
        assertEquals(4, calls)
        assertEquals(64000, first.maxOutputTokens) // Previously pinned rounds keep their declaration.
    }

    @Test fun `metadata failure is cached briefly and never blocks generation`() = runBlocking {
        var now = 0L
        var calls = 0
        val cache = ModelCapabilityCatalog(MonotonicClock { now })
        val client = transport { calls++; 401 to "{}" }
        assertNull(cache.resolve(client, "https://gateway.example/v1", "key", "cn:step-5-preview"))
        assertNull(cache.resolve(client, "https://gateway.example/v1", "key", "cn:step-5-preview"))
        assertEquals(1, calls)
        now = java.util.concurrent.TimeUnit.MINUTES.toNanos(2)
        cache.resolve(client, "https://gateway.example/v1", "key", "cn:step-5-preview")
        assertEquals(2, calls)
    }

    @Test fun `manual catalog discovery also enables ordinary named models without changing protocols`() = runBlocking {
        var calls = 0
        val cache = ModelCapabilityCatalog()
        val client = transport { calls++; 200 to """{"data":[{"id":"model","max_output_tokens":200000}]}""" }
        assertEquals(200000, cache.resolve(client, "https://gateway.example/v1/responses", "key", "model")!!.maxOutputTokens)
        assertEquals(1, calls)
        cache.fetch(client, "https://gateway.example/v1/responses", "key")
        assertEquals(200000, cache.resolve(client, "https://gateway.example/v1", "key", "model")!!.maxOutputTokens)
        assertEquals(2, calls)
    }

    @Test fun `parses protocol aliases and supported completion parameter from any catalog`() {
        val catalog = parseModelCatalog("""{"data":[
            {"id":"a","max_completion_tokens":130000},
            {"id":"b","top_provider":{"max_completion_tokens":1000000}},
            {"id":"c","max_tokens":96000,"supported_parameters":["max_completion_tokens","reasoning_effort"]},
            {"id":"d","context_length":500000}
        ]}""")
        assertEquals(130000, catalog.capabilities["a"]!!.maxOutputTokens)
        assertEquals("max_completion_tokens", catalog.capabilities["a"]!!.chatOutputParameter)
        assertEquals(1000000, catalog.capabilities["b"]!!.maxOutputTokens)
        assertEquals("max_completion_tokens", catalog.capabilities["c"]!!.chatOutputParameter)
        assertNull(catalog.capabilities["d"]!!.maxOutputTokens) // Context capacity is not the output allowance.
    }

    @Test fun `cancellation is rethrown rather than converted to unknown capability`() = runBlocking {
        val cache = ModelCapabilityCatalog()
        val client = transport { throw CancellationException("cancel") }
        val error = runCatching { cache.resolve(client, "https://gateway.example/v1", "key", "cn:step-5-preview") }.exceptionOrNull()
        assertTrue(error is CancellationException)
    }
}
