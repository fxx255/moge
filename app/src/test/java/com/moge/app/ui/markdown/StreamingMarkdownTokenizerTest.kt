package com.moge.app.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownTokenizerTest {
    private fun visible(snapshot: StreamingMarkdownSnapshot): String = snapshot.tail.take(
        (snapshot.visibleEnd - snapshot.tailStart).coerceIn(0, snapshot.tail.length))

    @Test fun `each open math syntax stays buffered until its closing delimiter arrives`() {
        for ((open, close) in listOf("$" to "$", "$$" to "$$", "\\(" to "\\)", "\\[" to "\\]")) {
            val tokenizer = StreamingMarkdownTokenizer()
            val before = "由条件可得 "
            val source = before + open + "\\frac{x+1}{y}" + close
            val closingStart = source.length - close.length
            for (length in before.length + 1..source.length) {
                val state = tokenizer.update(source.take(length))
                if (length < source.length) {
                    assertEquals("Must not expose partial math at $length: $source", before, visible(state))
                    assertTrue(state.waiting)
                } else {
                    assertEquals(source, visible(state))
                    assertTrue(state.renderedPrefixEnd > closingStart)
                }
            }
        }
    }

    @Test fun `emphasis links and image anchors never expose incomplete protocol syntax`() {
        for (suffix in listOf("**重点", "*斜体", "__重点", "`code", "[链接](https://example", "[[FIGURE:12")) {
            val state = StreamingMarkdownTokenizer().update("说明：$suffix")
            assertEquals(suffix, "说明：", visible(state))
            assertTrue(state.waiting)
        }
        val complete = StreamingMarkdownTokenizer().update("说明：[[FIGURE:12]]，继续")
        assertEquals("说明：[[FIGURE:12]]，继续", visible(complete))
    }

    @Test fun `table rows stay buffered so a later wide formula cannot resize visible columns`() {
        val tokenizer = StreamingMarkdownTokenizer()
        val lead = "测量结果：\n\n"
        val table = "| 类型 | 数值 |\n| --- | --- |\n| SNR | ${'$'}\\frac{P}{N_0B}${'$'} |\n| P | 较长的数据 |"
        for (length in 1..table.length) {
            val state = tokenizer.update(lead + table.take(length))
            assertEquals(listOf("测量结果："), state.blocks.map { it.text })
            assertEquals("", visible(state))
        }
        val closed = tokenizer.update(lead + table + "\n\n结论")
        assertEquals(table, closed.blocks.last().text)
        assertEquals("结论", visible(closed))
    }

    @Test fun `a stop or failure flushes all received text even when syntax remains unfinished`() {
        for (body in listOf("解释 ${'$'}\\frac{x", "| A | B |\n| --- | --- |\n| 一半", "```kotlin\nval x = 1", "[[FIGURE:1")) {
            val tokenizer = StreamingMarkdownTokenizer()
            assertTrue(tokenizer.update(body).waiting)
            val stopped = tokenizer.update(body, flush = true)
            assertEquals(body, (stopped.blocks.map { it.text } + visible(stopped)).filter { it.isNotEmpty() }.joinToString("\n\n"))
            assertTrue(!stopped.waiting)
        }
    }

    @Test fun `a paragraph keeps its source identity when committed and when the stream finishes`() {
        val tokenizer = StreamingMarkdownTokenizer()
        val body = "结果 ${'$'}A^{10}${'$'}。"
        val first = tokenizer.update(body)
        val committed = tokenizer.update("$body\n\n继续")
        assertEquals(first.tailId, committed.blocks.first().id)
        val finished = tokenizer.update("$body\n\n继续", flush = true)
        assertEquals(committed.blocks, finished.blocks.take(committed.blocks.size))
        assertEquals(committed.tailId, finished.blocks.last().id)
    }

    @Test fun `code and escaped pipes cannot start a pending table or figure`() {
        for (source in listOf("概率 ${'$'}P_{0|1}${'$'}，继续", "反斜杠 \\| 只是文字", "`[[FIGURE:1]]` 后续")) {
            assertEquals(source, visible(StreamingMarkdownTokenizer().update(source)))
        }
    }

    @Test fun `completion trimming trailing whitespace retains committed source identities`() {
        val tokenizer = StreamingMarkdownTokenizer()
        val growing = tokenizer.update("结果 ${'$'}A${'$'}\n\n")
        val done = tokenizer.update("结果 ${'$'}A${'$'}", flush = true)
        assertEquals(growing.blocks.single().id, done.blocks.single().id)
    }

    @Test fun `a complete ordinary line after the table closes it without requiring a blank paragraph`() {
        val table = "| A | B |\n| --- | --- |\n| 1 | 2 |"
        val state = StreamingMarkdownTokenizer().update("$table\n结论已经明确。\n继续解释")
        assertEquals(table, state.blocks.single().text)
        assertEquals("结论已经明确。\n继续解释", visible(state))
    }
    @Test fun `closed bold advances the rendered prefix without committing a growing paragraph`() {
        val tokenizer = StreamingMarkdownTokenizer()
        val prefix = "6. **on**: 固定搭配 **effects on sth.**"
        val first = tokenizer.update("6. **on**: 固定搭配 **eff")
        assertEquals("6. **on**", first.tail.substring(0, first.renderedPrefixEnd))
        val completed = tokenizer.update("$prefix = 对……的影响。")
        assertTrue(completed.blocks.isEmpty())
        assertEquals(prefix, completed.tail.substring(0, completed.renderedPrefixEnd))
        assertEquals(completed.renderedPrefixEnd, tokenizer.update(completed.tail + "继续说明").renderedPrefixEnd)
    }

    @Test fun `literal or incomplete asterisks never create a render boundary`() {
        for (source in listOf(
            "这是 **未闭合", "这是 ** 带空格 **", """这是 \*\*转义\*\*""",
            "`**代码", "`` ` **代码", "```text\n**代码**",
            "`\$notMath **代码", "**未闭合\n\n后续 **也未闭合",
            "| 短语 |\n| --- |\n| **effects on sth.** |",
        )) {
            val snapshot = StreamingMarkdownTokenizer().update(source)
            assertEquals(source, snapshot.tailStart, snapshot.renderedPrefixEnd)
        }
    }

    @Test fun `bold spanning math can close without exposing the next incomplete formula`() {
        val source = "**系数 \$2t\$**，后续 \$x+"
        val snapshot = StreamingMarkdownTokenizer().update(source)
        assertEquals("**系数 \$2t\$**", source.substring(0, snapshot.renderedPrefixEnd))
        assertEquals("**系数 \$2t\$**，后续 ", visible(snapshot))
    }

    @Test fun `conditional bar inside a closed formula is not a table separator`() {
        val formula = "${'$'}P_{0|1}=P(\\text{判0}\\mid\\text{发1})${'$'}"
        val source = "概率为 $formula，继续说明"
        val snapshot = StreamingMarkdownTokenizer().update(source)
        assertEquals("概率为 $formula", source.substring(0, snapshot.renderedPrefixEnd))
    }
    @Test fun `actual table row with conditional math stays plain until table boundary`() {
        val source = "| 条件 | 概率 |\n| --- | --- |\n| 误判 | ${'$'}P_{0|1}${'$'} |"
        val snapshot = StreamingMarkdownTokenizer().update(source)
        assertEquals(0, snapshot.renderedPrefixEnd)
    }

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
        assertTrue(first.renderedPrefixEnd > first.tailStart)

        val continued = tokenizer.update(first.tail + "，因此答案为 4。")
        assertTrue(continued.blocks.isEmpty())
        assertEquals("结论是 $" + "x^2" + "$，因此答案为 4。", continued.tail)
        assertTrue(continued.renderedPrefixEnd > continued.tailStart)

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
        assertTrue(snapshot.renderedPrefixEnd > 0)
        assertEquals("first \$a\$", snapshot.tail.substring(0, snapshot.renderedPrefixEnd))
        assertEquals("first \$a\$, then ", visible(snapshot))
    }
}
