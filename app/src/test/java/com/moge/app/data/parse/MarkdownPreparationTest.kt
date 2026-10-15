package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownPreparationTest {
    @Test fun screenshotStateAndOutputSequencesBecomeMathWithoutChangingTheirOrder() {
        val source = "**输出结果顺序：** 题目明确按 \$00,10,11,01\$ 给前一时刻度量，" +
            "所以答案也应按同一顺序写为 \$3,1,3,3\$；若改按 \$00,01,10,11\$ 才是 \$3,3,1,3\$。"
        val expected = source.replace(Regex("\\$([^$]+)\\$")) { "\$\$${it.groupValues[1]}\$\$" }
        assertEquals(expected, prepareReplyMarkdown(source))
        assertEquals(expected, prepareReplyMarkdown(expected))
    }

    @Test fun numericListsRenderInTablesWhileCodeAndCurrencyStayLiteral() {
        val source = "| 数值 |\n| --- |\n| \$0.5, 1.5; 2\$ |\n\n" +
            "`\$00,10\$`\n```text\n\$3,1,3,3\$\n```\n价格 \$5 到 \$10，转义 \\$00,10\\$。"
        assertEquals(source.replace("\$0.5, 1.5; 2\$", "\$\$0.5, 1.5; 2\$\$"), prepareReplyMarkdown(source))
    }

    @Test fun fencedAndInlineCodeKeepEscapesAndLatexLikeCommandsLiteral() {
        val code = "```json\n" + """{"reply":"第一行\n\n第二行","pattern":"\tag{literal}"}""" + "\n```\n"
        val inline = """`\n中文 \tag{literal}`"""
        assertEquals(code + inline, prepareReplyMarkdown(code + inline))
    }

    @Test fun proseStillRepairsEscapedParagraphsAroundCode() {
        val code = "~~~text\n" + """\n\n\tag{literal}""" + "\n~~~\n"
        val prose = "前段\\n\\n后段\n"
        assertEquals("前段\n\n后段\n" + code + "尾部\n中文", prepareReplyMarkdown(prose + code + "尾部\\n中文"))
    }

    @Test fun longerClosingFencesAndUnclosedStreamingCodeRemainProtected() {
        val closed = "````text\n" + """\n\n\tag{literal}""" + "\n`````\n"
        val open = "```json\n" + """{"reply":"\n\n\tag{literal}"}"""
        assertEquals(closed + open, prepareReplyMarkdown(closed + open))
    }
}
