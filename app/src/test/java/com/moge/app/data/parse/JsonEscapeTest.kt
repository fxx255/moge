package com.moge.app.data.parse

import com.moge.app.data.parse.ReplyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型在 reply 里写 LaTeX 时常直接输出单反斜杠（`\frac`、`\alpha`），
 * 这在 JSON 里是非法转义，曾导致整包解析失败、协议原文被当成回答显示。
 */
class JsonEscapeTest {

    @Test
    fun `latex backslashes in reply do not break parsing`() {
        val raw =
            """{"reply":"由 \frac{a}{b} 与 \alpha 可得"}"""

        val parsed = ReplyParser.parse(raw)

        assertFalse("不该把协议原文当正文显示", parsed.reply.contains("\"reply\""))
        assertTrue("公式反斜杠应原样保留", parsed.reply.contains("""\frac{a}{b}"""))
        assertTrue(parsed.reply.contains("""\alpha"""))
    }

    @Test
    fun `an extra latex escaping layer is repaired after json decoding`() {
        val raw =
            """{"reply":"计算：${'$'}${'$'}\\\\int_0^{2\\\\pi} \\\\cos^2\\\\theta \\\\,d\\\\theta${'$'}${'$'}"}"""

        val parsed = ReplyParser.parse(raw)

        assertEquals(
            """计算：${'$'}${'$'}\int_0^{2\pi} \cos^2\theta \,d\theta${'$'}${'$'}""",
            parsed.reply,
        )
    }

    @Test
    fun `genuine json newline escape still becomes a real newline`() {
        val raw = """{"reply":"第一段\n第二段"}"""

        val parsed = ReplyParser.parse(raw)

        assertTrue(parsed.reply.contains("第一段"))
        assertTrue(parsed.reply.contains("第二段"))
        assertFalse("不应留下字面的反斜杠 n", parsed.reply.contains("\\n"))
    }

    @Test
    fun `newline followed by ascii letters is not mistaken for latex`() {
        // 回归：正文换行后紧跟英文（f(x)、uv、network）曾被误判成 LaTeX 命令，
        // 于是换行被吃掉、正文里直接冒出字面的 \nf(x)、\nuv
        val raw =
            """{"reply":"先看第一段\nf(x) 的取值，再看\nuv 的说明"}"""

        val parsed = ReplyParser.parse(raw)

        assertFalse("换行不该变成字面反斜杠 n，实际: ${parsed.reply}", parsed.reply.contains("\\n"))
        assertTrue(parsed.reply.contains("f(x)"))
        assertTrue(parsed.reply.contains("uv 的说明"))
    }

    @Test
    fun `latex n commands are still preserved`() {
        val raw =
            """{"reply":"梯度 \nabla f 与 \neq 0 且 \notin A"}"""

        val parsed = ReplyParser.parse(raw)

        assertTrue("\\nabla 应保留，实际: ${parsed.reply}", parsed.reply.contains("""\nabla"""))
        assertTrue("\\neq 应保留", parsed.reply.contains("""\neq"""))
        assertTrue("\\notin 应保留", parsed.reply.contains("""\notin"""))
    }

    @Test
    fun `plots survive alongside latex in reply`() {
        val raw =
            """{"reply":"如图 \cdot 所示","subject":"数学","plots":[{"title":"P","series":[{"expr":"x"}]}]}"""

        val parsed = ReplyParser.parse(raw)

        assertFalse(parsed.reply.contains("plots"))
        assertEquals(1, parsed.plots.size)
    }
}
