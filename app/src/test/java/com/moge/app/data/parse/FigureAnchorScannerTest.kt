package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FigureAnchorScannerTest {
    @Test fun `scanner records token ranges without surrounding prose or whitespace`() {
        val text = "结构见 [[ Figure : 2 ]]。\r\n  [[FIGURE:1]]  \r\n"
        val anchors = findFigureAnchors(text)
        assertEquals(listOf(2, 1), anchors.map { it.number })
        assertEquals(listOf("[[ Figure : 2 ]]", "[[FIGURE:1]]"), anchors.map { text.substring(it.range) })
        assertFalse(anchors[0].isStandalone)
        assertTrue(anchors[1].isStandalone)
        assertEquals("结构见 图 2。\r\n  图 1  \r\n", replaceFigureAnchors(text) { "图 ${it.numberText}" })
    }

    @Test fun `fences require matching marker and at least the opening run length`() {
        val text = "````json\n[[FIGURE:1]]\n```\n[[FIGURE:2]]\n~~~\n" +
            "[[FIGURE:3]]\n`````\n实际 [[FIGURE:4]]。"
        assertEquals(listOf(4), findFigureAnchors(text).map { it.number })
    }

    @Test fun `fence-looking code contents do not prematurely close a plain fence`() {
        val text = "```text\n- ```\n[[FIGURE:1]]\n> ```\n[[FIGURE:2]]\n```\n[[FIGURE:3]]"
        assertEquals(listOf(3), findFigureAnchors(text).map { it.number })
    }

    @Test fun `quoted and list-contained fenced examples are protected`() {
        val text = "> ~~~text\n> [[FIGURE:1]]\n> ~~~\n" +
            "- ```text\n  [[FIGURE:2]]\n  ```\n实际 [[FIGURE:3]]"
        assertEquals(listOf(3), findFigureAnchors(text).map { it.number })
    }

    @Test fun `unclosed fence protects the rest of a truncated body`() {
        val text = "真实 [[FIGURE:1]]。\r\n~~~json\r\n[[FIGURE:2]]"
        assertEquals(listOf(1), findFigureAnchors(text).map { it.number })
    }

    @Test fun `inline code matches equal backtick run lengths and may span a line break`() {
        val text = "``例子 ` [[FIGURE:1]]\n[[FIGURE:2]]``，`[[FIGURE:3]]`，实际 [[FIGURE:4]]。"
        assertEquals(listOf(4), findFigureAnchors(text).map { it.number })
    }

    @Test fun `unmatched or escaped backticks are prose and escaped anchors stay literal`() {
        assertEquals(listOf(1), findFigureAnchors("未闭合 ` 然后 [[FIGURE:1]]。").map { it.number })
        assertEquals(listOf(2), findFigureAnchors("\\`[[FIGURE:2]]\\`").map { it.number })
        val text = "\\[[FIGURE:1]]，真实 [[FIGURE:2]]。"
        assertEquals("\\[[FIGURE:1]]，真实 图 2。", replaceFigureAnchors(text) { "图 ${it.number}" })
    }

    @Test fun `inline code cannot span blank paragraphs`() {
        val text = "未闭合 `\n\n真实 [[FIGURE:1]]。`"
        assertEquals(listOf(1), findFigureAnchors(text).map { it.number })
    }
}
