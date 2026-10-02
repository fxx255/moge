package com.moge.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownTokenizerTest {

    @Test
    fun `普通段落在空行前留在 tail`() {
        val tokenizer = StreamingMarkdownTokenizer()

        val first = tokenizer.update("第一段还在输出")
        assertTrue(first.blocks.isEmpty())
        assertEquals("第一段还在输出", first.tail)

        val completed = tokenizer.update("第一段还在输出\n\n第二段")
        assertEquals(listOf("第一段还在输出"), completed.blocks.map { it.text })
        assertEquals("第二段", completed.tail)
    }

    @Test
    fun `跨增量闭合的块公式会进入稳定块`() {
        val tokenizer = StreamingMarkdownTokenizer()

        val first = tokenizer.update("说明\n\n\$\$ x + y")
        assertEquals(listOf("说明"), first.blocks.map { it.text })
        assertEquals("\$\$ x + y", first.tail)

        val completed = tokenizer.update("说明\n\n\$\$ x + y \$\$\n\n后续")
        assertEquals(listOf("说明", "\$\$ x + y \$\$"), completed.blocks.map { it.text })
        assertEquals("后续", completed.tail)
    }

    @Test
    fun `行内公式闭合后仍与后续文字保持同一段`() {
        val tokenizer = StreamingMarkdownTokenizer()
        val first = tokenizer.update("结论是 $" + "x^2" + "$")
        assertTrue(first.blocks.isEmpty())
        assertTrue(first.mathPrefixEnd > first.tailStart)

        val continued = tokenizer.update(first.tail + "，因此答案为 4。")
        assertTrue(continued.blocks.isEmpty())
        assertEquals("结论是 $" + "x^2" + "$，因此答案为 4。", continued.tail)
        assertTrue(continued.mathPrefixEnd > continued.tailStart)

        val committed = tokenizer.update(continued.tail + "\n\n下一段")
        assertEquals(listOf(continued.tail), committed.blocks.map { it.text })
        assertEquals("下一段", committed.tail)
    }

    @Test
    fun `未闭合公式不会交给 Markwon`() {
        val tokenizer = StreamingMarkdownTokenizer()

        val snapshot = tokenizer.update("结果是 \\(x + y")

        assertTrue(snapshot.blocks.isEmpty())
        assertEquals("结果是 \\(x + y", snapshot.tail)
    }

    @Test
    fun `代码围栏中的美元符号不触发公式边界`() {
        val tokenizer = StreamingMarkdownTokenizer()

        val snapshot = tokenizer.update("```text\n\$notMath\n```\n\n后续")

        assertEquals(1, snapshot.blocks.size)
        assertTrue(snapshot.blocks.single().text.contains("\$notMath"))
        assertEquals("后续", snapshot.tail)
    }

    @Test
    fun `半截表格在空行前留在 tail`() {
        val tokenizer = StreamingMarkdownTokenizer()

        val first = tokenizer.update("| 名称 | 数值 |\n| --- | --- |\n| a | 1 |")
        assertTrue(first.blocks.isEmpty())
        assertEquals("| 名称 | 数值 |\n| --- | --- |\n| a | 1 |", first.tail)

        val completed = tokenizer.update(first.tail + "\n\n结论")
        assertEquals(1, completed.blocks.size)
        assertTrue(completed.blocks.single().text.contains("| a | 1 |"))
        assertEquals("结论", completed.tail)
    }

    @Test
    fun `内容被替换时生成新的 block key`() {
        val tokenizer = StreamingMarkdownTokenizer()

        val old = tokenizer.update("旧回答\n\n下一段")
        val replacement = tokenizer.update("新回答\n\n下一段")

        assertNotEquals(old.blocks.single().id, replacement.blocks.single().id)
    }

    @Test
    fun `flush 会提交未落在空行边界的普通文本`() {
        val tokenizer = StreamingMarkdownTokenizer()

        val snapshot = tokenizer.update("回答已经结束", flush = true)

        assertEquals(listOf("回答已经结束"), snapshot.blocks.map { it.text })
        assertEquals("", snapshot.tail)
    }

    @Test
    fun `普通 frac 反斜杠不会被当成数学分隔符`() {
        val tokenizer = StreamingMarkdownTokenizer()

        val snapshot = tokenizer.update("比例为 \\frac{x}{y}")

        assertTrue(snapshot.blocks.isEmpty())
        assertEquals("比例为 \\frac{x}{y}", snapshot.tail)
    }
    @Test
    fun `first closed inline formula remains in prefix while second stays open`() {
        val tokenizer = StreamingMarkdownTokenizer()

        val snapshot = tokenizer.update("first $" + "a" + "$, then $" + "b")

        assertEquals("first $" + "a" + "$, then $" + "b", snapshot.tail)
        assertTrue(snapshot.mathPrefixEnd > 0)
        assertEquals("first \$a\$, then ", snapshot.tail.substring(0, snapshot.mathPrefixEnd))
    }
}
