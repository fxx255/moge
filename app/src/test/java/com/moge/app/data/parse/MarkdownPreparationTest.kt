package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownPreparationTest {
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
