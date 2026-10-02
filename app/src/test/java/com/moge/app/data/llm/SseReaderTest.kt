package com.moge.app.data.llm

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SSE 读取器验收：**空行**才是事件边界，多个 `data:` 行属于同一个事件。
 *
 * 逐行 parse 会把每个 data 行当成独立事件 —— 每行都不是合法 JSON，
 * 于是整段内容被静默丢弃。这是这里要守住的核心不变量。
 */
class SseReaderTest {

    private fun read(body: String): List<SseEvent> {
        val events = mutableListOf<SseEvent>()
        val source = Buffer().apply { writeUtf8(body) }
        runBlocking { source.readSseEvents { events += it; true } }
        return events
    }

    @Test
    fun `multiple data lines form one event`() {
        val events = read(
            "data: {\"a\":\n" +
                "data: 1}\n" +
                "\n" +
                "data: {\"b\":2}\n\n",
        )
        assertEquals(2, events.size)
        // 两个 data 行用 \n 连接后是一个完整 JSON。
        assertEquals("{\"a\":\n1}", events[0].data)
        assertEquals("{\"b\":2}", events[1].data)
    }

    @Test
    fun `event name is kept alongside data`() {
        val events = read("event: response.completed\ndata: {\"ok\":true}\n\n")
        assertEquals(1, events.size)
        assertEquals("response.completed", events[0].name)
    }

    @Test
    fun `comments are ignored`() {
        val events = read(": keep-alive comment\ndata: {\"a\":1}\n\n:after\n")
        assertEquals(1, events.size)
        assertEquals("{\"a\":1}", events[0].data)
    }

    /** 末尾没有空行时，最后一个事件必须在 EOF 提交。 */
    @Test
    fun `pending event without trailing blank line is dispatched at eof`() {
        val events = read("data: {\"first\":1}\n\ndata: {\"last\":2}")
        assertEquals(2, events.size)
        assertEquals("{\"last\":2}", events[1].data)
    }

    @Test
    fun `crlf line endings are handled`() {
        val events = read("data: {\"a\":1}\r\n\r\n")
        assertEquals(1, events.size)
        assertEquals("{\"a\":1}", events[0].data)
    }

    /** 非 SSE 行（端点直接回 JSON）交给 onRawLine，不静默丢弃；原始空白原样保留。 */
    @Test
    fun `non sse lines are surfaced as raw lines preserving whitespace`() {
        val raw = mutableListOf<String>()
        val events = mutableListOf<SseEvent>()
        val body = "{\n \"id\":\"x\",\n\t\"text\":\"y\"\n}\n"
        val source = Buffer().apply { writeUtf8(body) }
        runBlocking {
            source.readSseEvents(onRawLine = { raw.add(it) }) { events += it; true }
        }
        assertEquals(0, events.size)
        // 行按原样保留（含前导空白），拼接后能解析出同一个 JSON。
        assertEquals(4, raw.size)
        assertEquals("{", raw[0])
        assertEquals(" \"id\":\"x\",", raw[1])
        assertEquals("\t\"text\":\"y\"", raw[2])
        assertEquals("}", raw[3])
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(
            raw.joinToString(""),
        ).let { it as kotlinx.serialization.json.JsonObject }
        assertEquals("x", parsed["id"]?.toString()?.trim('"'))
    }
}
