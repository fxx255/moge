package com.moge.app.data.parse

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class NonstandardFigureReplyTest {
    private val fixture = requireNotNull(javaClass.getResource("/fixtures/cardioid-fenced-members.md")).readText()

    @Test fun `screenshot cardioid member fence keeps explanation and restores ordered closed curve`() {
        val parsed = ReplyParser.parse(fixture)
        assertTrue(parsed.reply.contains("右端顶点为"))
        assertTrue(parsed.reply.contains("作图方法"))
        assertFalse(parsed.reply.contains("\"plots\""))
        assertFalse(parsed.reply.contains("```"))
        val plot = parsed.plots.single()
        assertEquals(25, plot.series.single().points!!.size)
        assertEquals(2.0 to 0.0, plot.series.single().points!!.first())
        assertEquals(2.0 to 0.0, plot.series.single().points!!.last())
        assertEquals(0.0 to 0.0, plot.series.single().points!![12])
        assertEquals(-1.5, plot.y.min!!, 0.0)
        assertEquals(1.5, plot.y.max!!, 0.0)
    }

    @Test fun `member fence inside a normal reply field is extracted too`() {
        val parsed = ReplyParser.parse(buildJsonObject { put("reply", fixture); put("answer", "心形线") }.toString())
        assertEquals(1, parsed.plots.size)
        assertEquals("心形线", parsed.finalAnswer)
        assertTrue(parsed.reply.contains("曲线关于"))
        assertFalse(parsed.reply.contains("\"plots\""))
    }

    @Test fun `truncated envelope still extracts completed nested member fence`() {
        val raw = buildJsonObject { put("reply", fixture) }.toString().dropLast(1)
        val parsed = ReplyParser.parse(raw)
        assertEquals(1, parsed.plots.size)
        assertFalse(parsed.reply.contains("\"plots\""))
    }

    @Test fun `unfenced member and standalone plot do not discard surrounding paragraphs`() {
        for (data in listOf("\"plots\":[{\"series\":[{\"expr\":\"x\"}]}]", "{\"plots\":[{\"series\":[{\"expr\":\"x\"}]}]}")) {
            val parsed = ReplyParser.parse("前面的推导。\n$data\n后面的结论。")
            assertEquals(1, parsed.plots.size)
            assertTrue(parsed.reply.contains("前面的推导"))
            assertTrue(parsed.reply.contains("后面的结论"))
            assertFalse(parsed.reply.contains("series"))
        }
    }

    @Test fun `diagram member in json fence is recovered in its own slot`() {
        val parsed = ReplyParser.parse("说明。\n```json\n\"diagrams\":[{\"nodes\":[{\"id\":\"a\",\"label\":\"输入\"},{\"id\":\"b\",\"label\":\"输出\"}],\"edges\":[{\"from\":\"a\",\"to\":\"b\"}]}]\n```\n结束。")
        assertEquals(1, parsed.diagrams.size)
        assertEquals("说明。\n\n结束。", parsed.reply)
    }

    @Test fun `bad slot remains between two valid figures and protocol is not shown`() {
        val parsed = ReplyParser.parse("正文\n```json\n\"plots\":[{\"series\":[{\"expr\":\"x\"}]},{\"series\":[{\"expr\":\"nope(x)\"}]},{\"series\":[{\"expr\":\"x*x\"}]}]\n```")
        assertEquals(3, parsed.plotSlots.size)
        assertNull(parsed.plotSlots[1])
        assertEquals(2, parsed.plots.size)
        assertFalse(parsed.reply.contains("nope"))
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test fun `ordinary and non json code examples remain readable`() {
        for (data in listOf("```json\n{\"example\":\"data\"}\n```", "```python\n\"plots\":[{\"series\":[{\"expr\":\"x\"}]}]\n```", "`{\"series\":[{\"expr\":\"x\"}]}`")) {
            val parsed = ReplyParser.parse("示例：\n$data")
            assertEquals("示例：\n$data", parsed.reply)
            assertTrue(parsed.plots.isEmpty())
        }
    }

    @Test fun `removing an all metadata reply does not put the raw metadata back`() {
        val only = "```json\n\"plots\":[{\"series\":[{\"expr\":\"x\"}]}]\n```"
        val parsed = ReplyParser.parse(buildJsonObject { put("reply", only) }.toString())
        assertEquals("", parsed.reply)
        assertEquals(1, parsed.plots.size)
    }
}
