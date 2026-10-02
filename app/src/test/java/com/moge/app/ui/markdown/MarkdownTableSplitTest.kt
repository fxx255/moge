package com.moge.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格分块用于给表格单独启用横向滚动，切错会直接影响正文渲染，所以这里覆盖几种边界。
 */
class MarkdownTableSplitTest {

    @Test
    fun `splits table out of surrounding text`() {
        val md = """
            结论如下：

            | 项目 | 均值 |
            | --- | --- |
            | (1) | 0 |

            下面继续推导。
        """.trimIndent()

        val chunks = splitMarkdownTableBlocks(md)

        assertEquals(3, chunks.size)
        assertFalse(chunks[0].isTable)
        assertTrue(chunks[1].isTable)
        assertTrue("表格块应含表头，实际: ${chunks[1].text}", chunks[1].text.startsWith("| 项目"))
        assertFalse(chunks[2].isTable)
    }

    @Test
    fun `plain markdown stays a single non table chunk`() {
        val chunks = splitMarkdownTableBlocks("第一段\n\n第二段")

        assertEquals(1, chunks.size)
        assertFalse(chunks[0].isTable)
    }

    @Test
    fun `pipe inside code fence is not treated as a table`() {
        val md = "```\n| a | b |\n| --- | --- |\n```"

        val chunks = splitMarkdownTableBlocks(md)

        assertEquals(1, chunks.size)
        assertFalse(chunks[0].isTable)
    }

    @Test
    fun `pipe line without separator is not a table`() {
        val chunks = splitMarkdownTableBlocks("| a | b |\n普通一行")

        assertEquals(1, chunks.size)
        assertFalse(chunks[0].isTable)
    }

    @Test
    fun `table with alignment colons is recognized`() {
        val md = "| 左 | 右 |\n|:--|--:|\n| 1 | 2 |"

        val chunks = splitMarkdownTableBlocks(md)

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].isTable)
    }
}
