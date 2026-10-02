package com.moge.app.data.parse

import com.moge.app.data.parse.ReplyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绘图协议解析：模型输出的 plots 必须被安全地转成 PlotSpec，
 * 坏数据要能局部丢弃而不是拖垮整条回答。
 */
class PlotParsingTest {

    @Test
    fun `parses plots array into specs`() {
        val raw = """{"reply":"看图","plots":[{"title":"抛物线","x":{"label":"x","min":-3,"max":3},
            "y":{"label":"y"},"series":[{"label":"y=x^2","expr":"x^2"},
            {"label":"实测","points":[[0,0],[1,1.1]]}]}],
            "plan_actions":[],"english_actions":[]}"""

        val parsed = ReplyParser.parse(raw)

        assertEquals(1, parsed.plots.size)
        val plot = parsed.plots.first()
        assertEquals("抛物线", plot.title)
        assertEquals(2, plot.series.size)
        assertEquals("x^2", plot.series[0].expr)
        assertEquals(2, plot.series[1].points?.size)
        assertEquals(-3.0, plot.x.min!!, 1e-9)
        assertEquals(3.0, plot.x.max!!, 1e-9)
        assertTrue("两条曲线应自动开图例", plot.legend)
    }

    @Test
    fun `rejects broken expression but keeps the answer`() {
        val raw = """{"reply":"正文还在","plots":[{"series":[{"expr":"nope(x)"}]}],
            "plan_actions":[],"english_actions":[]}"""

        val parsed = ReplyParser.parse(raw)

        assertTrue("坏表达式应被丢弃", parsed.plots.isEmpty())
        assertEquals("正文还在", parsed.reply)
        assertTrue("应留下警告", parsed.warnings.any { it.contains("图表") })
    }

    @Test
    fun `invalid axis range falls back to auto`() {
        val raw = """{"reply":"看图","plots":[{"x":{"min":5,"max":1},"series":[{"expr":"x"}]}],
            "plan_actions":[],"english_actions":[]}"""

        val parsed = ReplyParser.parse(raw)

        val axis = parsed.plots.first().x
        assertNull("min>=max 时应退回自动范围", axis.min)
        assertNull(axis.max)
    }

    @Test
    fun `expression with semicolon is refused`() {
        val raw = """{"reply":"看图","plots":[{"series":[{"expr":"x; rm -rf /"}]}],
            "plan_actions":[],"english_actions":[]}"""

        val parsed = ReplyParser.parse(raw)

        assertTrue(parsed.plots.isEmpty())
    }

    @Test
    fun `caps series count per plot`() {
        val series = (1..12).joinToString(",") { """{"expr":"x^$it"}""" }
        val raw = """{"reply":"看图","plots":[{"series":[$series]}],
            "plan_actions":[],"english_actions":[]}"""

        val parsed = ReplyParser.parse(raw)

        assertEquals("每张图最多 6 条曲线", 6, parsed.plots.first().series.size)
    }

    @Test
    fun `keeps mark lines and areas`() {
        val raw = """{"reply":"看图","plots":[{"series":[{"expr":"x"}],
            "markLines":[{"x":5,"label":"f_c"}],"markAreas":[{"x0":4,"x1":6,"label":"B"}]}],
            "plan_actions":[],"english_actions":[]}"""

        val parsed = ReplyParser.parse(raw)

        val plot = parsed.plots.first()
        assertEquals(1, plot.markLines.size)
        assertEquals("f_c", plot.markLines.first().label)
        assertEquals(1, plot.markAreas.size)
        assertEquals("B", plot.markAreas.first().label)
    }

    @Test
    fun `latex reply still parses when plots present`() {
        val raw = """{"reply":"结论 \frac{a}{b} 如下","plots":[{"series":[{"expr":"x"}]}],
            "plan_actions":[],"english_actions":[]}"""

        val parsed = ReplyParser.parse(raw)

        assertTrue(parsed.reply.contains("""\frac{a}{b}"""))
        assertEquals(1, parsed.plots.size)
    }

    @Test
    fun `bare plot spec is parsed without exposing json as reply`() {
        val raw = """{"title":"f(x)","x":{"min":-2,"max":3},"y":{"min":-1.5,"max":2.5},"series":[{"points":[[-2,0],[0,0],[2,2]]}]}"""

        val parsed = ReplyParser.parse(raw)

        assertEquals(1, parsed.plots.size)
        assertEquals("", parsed.reply)
        assertTrue("裸 PlotSpec 不应把 JSON 重新显示为正文", !parsed.reply.contains("series"))
    }

    @Test
    fun `plot json fenced inside reply is extracted and removed`() {
        val embedded = """{"title":"f(x)","series":[{"expr":"x"}]}"""
        val escaped = embedded.replace("\"", "\\\"")
        val raw = """{"reply":"图如下：\n```json\n$escaped\n```","plan_actions":[]}"""

        val parsed = ReplyParser.parse(raw)

        assertEquals(1, parsed.plots.size)
        assertEquals("图如下：", parsed.reply)
        assertTrue("绘图协议不应继续作为正文显示", !parsed.reply.contains("series"))
    }

    @Test
    fun `axis position accepts auto and bottom`() {
        val raw = """{"reply":"图","plots":[{"x":{"position":"bottom"},"series":[{"expr":"x"}]}]}"""

        val parsed = ReplyParser.parse(raw)

        assertEquals("bottom", parsed.plots.single().x.position)
    }
}
