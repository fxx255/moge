package com.moge.app.data.parse

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenshotFormulaNormalizationTest {
    private val bare = """=\arcsin\Bigl(2\sqrt{(1-x)-(1-x)^{2}}\Bigr)"""
    private val probability = """P_{0|1}=P(\text{判0}\mid\text{发1})"""
    private val dd = "$$"

    @Test
    fun `exact bare arcsin continuation gains only display delimiters`() {
        val expected = "$dd\n$bare\n$dd"
        assertEquals(expected, normalizeReplyMarkdown(bare))
        assertEquals(expected, sanitizeReplyLatex(expected))
        assertEquals(expected, normalizeReplyMarkdown(expected))
        assertEquals("上一行推导\n$expected\n因此得证。",
            normalizeReplyMarkdown("上一行推导\n$bare\n因此得证。"))
    }

    @Test
    fun `exact probability formula retains subscript conditional bar and Chinese labels`() {
        val raw = "${'$'}$probability${'$'}"
        val expected = "$dd$probability$dd"
        assertEquals(expected, normalizeReplyMarkdown(raw))
        assertEquals(expected, sanitizeReplyLatex(expected))
        assertEquals(expected, normalizeReplyMarkdown(expected))
        assertEquals("误判概率为 $expected。", normalizeReplyMarkdown("误判概率为 $raw。"))
    }

    @Test
    fun `JSON reply parsing preserves both exact formula bodies`() {
        val source = "$bare\n\n${'$'}$probability${'$'}"
        val json = JsonObject(mapOf("reply" to JsonPrimitive(source))).toString()
        assertEquals("$dd\n$bare\n$dd\n\n$dd$probability$dd", ReplyParser.parse(json).reply)
    }

    @Test
    fun `nested and escaped braces in Chinese text arguments remain untouched`() {
        val formula = """P(\text{判{0}}\mid\text{发\{1\}})"""
        assertEquals("$dd$formula$dd", normalizeReplyMarkdown("${'$'}$formula${'$'}"))
    }

    @Test
    fun `Chinese prose prices code and incomplete text arguments stay literal`() {
        for (source in listOf(
            "${'$'}价格5${'$'}", "${'$'}P=判0${'$'}", "价格 ${'$'}5 到 ${'$'}10 之间",
            "Cost ${'$'}5 and ${'$'}10", "`${'$'}$probability${'$'}`", "`$bare`",
            "```latex\n$bare\n${'$'}$probability${'$'}\n```",
            "~~~latex\n$bare\n~~~", """=\arcsin\Bigl(""",
            """=\arcsin(x) is the answer""", """=C:\temp\x""",
            "${'$'}P(\\text{判0)${'$'}", "${'$'}P(\\\\text{判0})${'$'}",
        )) assertEquals(source, source, normalizeReplyMarkdown(source))
    }

    @Test
    fun `continuations already inside a display block are not wrapped twice`() {
        for (source in listOf("$dd\n$bare\n$dd", "$dd$bare$dd", "\\[\n$bare\n\\]")) {
            val expected = if (source.startsWith("\\[")) "$dd\n$bare\n$dd" else source
            assertEquals(source, expected, normalizeReplyMarkdown(source))
        }
    }

    @Test
    fun `literal dollar pairs before a bare continuation do not open a math block`() {
        for (prefix in listOf("`$dd`", "\\${'$'}\\${'$'}", "```\n$dd\n```")) {
            assertEquals("$prefix\n$dd\n$bare\n$dd", normalizeReplyMarkdown("$prefix\n$bare"))
        }
    }
}
