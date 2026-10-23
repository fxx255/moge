package com.moge.app.data.parse

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class InlineMathListTest {
    @Test fun `reported matrix answer normalizes every formula without changing its paragraphs`() {
        val source = javaClass.getResource("/fixtures/matrix-equivalence.md")!!.readText().trimEnd()
        val expected = source.replace("\$P,Q\$", "\$\$P,Q\$\$").replace("\$A,B\$", "\$\$A,B\$\$")
        assertEquals(expected, prepareReplyMarkdown(source))
        assertEquals(expected, prepareReplyMarkdown(expected))
        val reply = JsonObject(mapOf("reply" to JsonPrimitive(source))).toString()
        assertEquals(expected, ReplyParser.parse(reply).reply)
    }

    @Test fun `letter and numeric lists normalize with spaces and either separator`() {
        for (body in listOf("P,Q", "A, B", "x,y,z", "α, β", "a; b", "0,1,x", "00,10,11,01")) {
            assertEquals(body, "参数 \$\$$body\$\$。", normalizeReplyMarkdown("参数 \$$body\$。"))
        }
    }

    @Test fun `prices word lists code escaped dollars and incomplete lists remain literal`() {
        for (source in listOf(
            "价格 \$5 到 \$10 之间", "Cost \$5 and \$10", "\$apple, pear\$",
            "`\$P,Q\$`", "```latex\n\$P,Q\$\n```", "\\\$P,Q\\\$", "待输出 \$P,Q",
        )) assertEquals(source, source, prepareReplyMarkdown(source))
    }
}
