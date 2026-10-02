package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyParserTest {


    @Test
    fun `summary title is parsed separately and invalid metadata is ignored`() {
        val parsed = ReplyParser.parse(
            """{"conversation_title":"  二重积分的对称性判断  ","reply":"命题不成立。"}""",
        )
        assertEquals("二重积分的对称性判断", parsed.conversationTitle)
        assertEquals("命题不成立。", parsed.reply)
        for (metadata in listOf("null", "123", "[]", "\"图片题目\"", "\"  \"")) {
            val result = ReplyParser.parse("""{"reply":"正常正文","conversation_title":$metadata}""")
            assertEquals(null, result.conversationTitle)
            assertEquals("正常正文", result.reply)
        }
        assertEquals(null, ReplyParser.parse("普通文本回复").conversationTitle)
        assertEquals("", ReplyParser.parse("""{"conversation_title":"主题标题"}""").reply)
    }

    @Test
    fun `truncated reply keeps only a complete leading summary title`() {
        val partial = ReplyParser.parse("""{"conversation_title":"二重积分的对称性判断","reply":"解释到一半""")
        assertEquals("二重积分的对称性判断", partial.conversationTitle)
        assertEquals("解释到一半", partial.reply)
        val nested = ReplyParser.parse("""{"other":{"conversation_title":"错误的内层标题"},"reply":"解释到一半""")
        assertEquals(null, nested.conversationTitle)
    }

    @Test
    fun `final answer is separate and invalid fields never become a guessed answer`() {
        val result = ReplyParser.parse("""{"answer":"x = 2","reply":"完整推导"}""")
        assertEquals("x = 2", result.finalAnswer)
        assertEquals("完整推导", result.reply)
        for (value in listOf("null", "123", "[]", "{}")) {
            assertEquals("", ReplyParser.parse("""{"answer":$value,"reply":"讲解"}""").finalAnswer)
        }
        assertEquals("", ReplyParser.parse("答案是 2").finalAnswer)
    }

    @Test
    fun `truncated explanation preserves only fully closed top level answer`() {
        val result = ReplyParser.parse("""{"answer":"2","reply":"写到一半""")
        assertEquals("2", result.finalAnswer)
        assertEquals("写到一半", result.reply)
        assertEquals("", ReplyParser.parse("""{"reply":"写到一半","answer":"2""").finalAnswer)
        assertEquals("", ReplyParser.parse("""{"other":{"answer":"伪答案"},"reply":"讲解"}""").finalAnswer)
    }

    @Test
    fun `plain text becomes reply without warnings`() {
        val parsed = ReplyParser.parse("先求导，再看单调性。")
        assertEquals("先求导，再看单调性。", parsed.reply)
        assertTrue(parsed.warnings.isEmpty())
    }


    @Test
    fun `markdown and latex remain intact in structured reply`() {
        val raw = """{"reply":"## 解答\\n\\n行内公式：${'$'}${'$'}x^2${'$'}${'$'}"}"""
        val parsed = ReplyParser.parse(raw)
        assertTrue(parsed.reply.contains("## 解答"))
        assertTrue(parsed.reply.contains("x^2"))
    }

    @Test
    fun `double escaped paragraphs and numbered lists become markdown breaks`() {
        val raw = """{"reply":"先给结论。\\n\\n建议：\\n1. 第一项\\n2. 第二项"}"""
        val parsed = ReplyParser.parse(raw)
        assertEquals("先给结论。\n\n建议：\n1. 第一项\n2. 第二项", parsed.reply)
    }

    @Test
    fun `latex commands beginning with n are not treated as line breaks`() {
        val raw = """{"reply":"公式：${'$'}${'$'}\\nu + \\nabla f${'$'}${'$'}"}"""
        val parsed = ReplyParser.parse(raw)
        assertEquals("公式：${'$'}${'$'}\\nu + \\nabla f${'$'}${'$'}", parsed.reply)
    }

    @Test
    fun `standard single dollar formulas are normalized for Markwon`() {
        val normalized = normalizeReplyMarkdown(
            "已知 ${'$'}f(x)=2^x${'$'}，求 ${'$'}D(-1)${'$'}。",
        )

        assertEquals(
            "已知 ${'$'}${'$'}f(x)=2^x${'$'}${'$'}，求 ${'$'}${'$'}D(-1)${'$'}${'$'}。",
            normalized,
        )
    }

    @Test
    fun `slash latex delimiters are normalized but code and prices are untouched`() {
        val normalized = normalizeReplyMarkdown(
            "价格 ${'$'}5，行内代码 `${'$'}x${'$'}`，公式 \\(x+1\\)。\n\\[\nx^2=1\n\\]",
        )

        assertEquals(
            "价格 ${'$'}5，行内代码 `${'$'}x${'$'}`，公式 ${'$'}${'$'}x+1${'$'}${'$'}。\n" +
                "${'$'}${'$'}\nx^2=1\n${'$'}${'$'}",
            normalized,
        )
    }

    @Test
    fun `duplicate latex command slashes are collapsed without eating row breaks`() {
        val normalized = normalizeReplyMarkdown(
            """计算：$$ \\int_0^{2\\pi} \\cos^2\\theta \\,d\\theta \\cdot \\frac{\\pi}{4} $$""",
        )
        assertEquals(
            """计算：$$ \int_0^{2\pi} \cos^2\theta \,d\theta \cdot \frac{\pi}{4} $$""",
            normalized,
        )

        val aligned = """$$\begin{aligned}a &= b \\ c &= d\end{aligned}$$"""
        assertEquals("真实的 aligned 行分隔符必须保留", aligned, normalizeReplyMarkdown(aligned))
    }

    @Test
    fun `json code fence is tolerated`() {
        val parsed = ReplyParser.parse("""```json
            {"reply":"可读回答"}
            ```""".trimIndent())
        assertEquals("可读回答", parsed.reply)
    }

    @Test
    fun `truncated structured response salvages reply`() {
        val parsed = ReplyParser.parse("""{"reply":"第一行\n第二行","plots":[""")
        assertEquals("第一行\n第二行", parsed.reply)
        assertTrue(parsed.warnings.isNotEmpty())
    }

    @Test
    fun `closed answer survives truncation before explanation begins`() {
        val parsed = ReplyParser.parse("""{"answer":"x = 2","reply":""")
        assertEquals("x = 2", parsed.finalAnswer)
        assertFalse(parsed.reply.contains("\"answer\""))
        assertTrue(parsed.warnings.isNotEmpty())
        val beforeReply = ReplyParser.parse("""{"answer":"x = 2","plots":[""")
        assertEquals("x = 2", beforeReply.reply)
        assertEquals("x = 2", beforeReply.finalAnswer)
    }

    @Test
    fun `json wrapped in prose and fences still yields reply`() {
        val raw = "好的，下面是解答：\n```json\n" +
            "{\"reply\":\"答案是 2\",\"subject\":\"数学\"}\n" +
            "```\n以上。"
        val parsed = ReplyParser.parse(raw)
        assertEquals("答案是 2", parsed.reply)
        assertEquals("", parsed.finalAnswer)
    }

    @Test
    fun `braces inside strings do not break truncated salvage`() {
        val raw = """{"reply":"集合 {1,2} 的子集","plots":[""" +
            """{"title":"{P}","series":[{"expr":"x"}]},""" +
            """{"title":"cut"""
        val parsed = ReplyParser.parse(raw)
        assertEquals(1, parsed.plots.size)
        assertEquals("{P}", parsed.plots.single().title)
    }
}
