package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格在 CommonMark 下渲染不出来，有两个互相独立的成因，这里各自钉住：
 *
 * 1. 表格与上文之间**缺少空行** ⇒ Markwon 把整块表格当普通段落（实测 TableSpan 数量为 0）；
 * 2. 行宽公式拆分把**表格行拆断** ⇒ `| a | $$x$$ |` 被拆成四行，表格结构消失。
 *
 * 另外覆盖单元格内裸 `|`（绝对值/范数）会导致 TablePlugin 列数错乱的转义。
 */
class TableNormalizeTest {

    @Test
    fun `adds blank line before table glued to previous paragraph`() {
        val md = "利用公式，\n| 频率点 | 值 |\n| --- | --- |\n| 0 | 4 |"

        val out = normalizeReplyMarkdown(md)

        assertTrue("表格前应补空行，实际:\n$out", out.contains("利用公式，\n\n| 频率点"))
    }

    @Test
    fun `adds blank line after table glued to next paragraph`() {
        val md = "| 频率点 | 值 |\n| --- | --- |\n| 0 | 4 |\n带内起伏为"

        val out = normalizeReplyMarkdown(md)

        assertTrue("表格后应补空行，实际:\n$out", out.contains("| 0 | 4 |\n\n带内起伏为"))
    }

    @Test
    fun `keeps existing blank lines untouched`() {
        val md = "前面一段\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\n后面一段"

        val out = normalizeReplyMarkdown(md)

        assertEquals(md, out)
    }

    @Test
    fun `escapes bare pipe inside math in table cell`() {
        val md = "| 条件 | 值 |\n| --- | --- |\n| \$\$|f| \\le B/2\$\$ | 4 |"

        val out = normalizeReplyMarkdown(md)

        // 公式内的裸竖线必须转义，否则表格插件会把它当单元格分隔符，列数错乱
        val expectedCell = "\$\$\\|f\\| \\le B/2\$\$"
        assertTrue(
            "公式内的竖线应转义，期望包含 <$expectedCell>，实际:\n$out",
            out.contains(expectedCell),
        )
        // 单元格分隔符必须保留（不能被一起转义）
        assertTrue("单元格分隔符不能被转义，实际:\n$out", out.contains("| 4 |"))
        // 表格结构（表头 + 分隔行 + 数据行）保持完整
        assertEquals("表格行数不应变化", 3, out.lines().count { '|' in it })
    }

    @Test
    fun `does not touch pipes inside code fence`() {
        val md = "```\n| a | b |\n| --- | --- |\n```"

        val out = normalizeReplyMarkdown(md)

        assertEquals(md, out)
    }

    @Test
    fun `table without separator row is left alone`() {
        val md = "| a | b |\n普通一行"

        val out = normalizeReplyMarkdown(md)

        assertFalse("没有分隔行就不是表格，不该补空行:\n$out", out.contains("\n\n| a | b |"))
    }

    // ---------- 最简数值公式（v1.0.35） ----------

    /**
     * `$0$` 这类**单独的数值公式**必须和其它公式走同一条路径。
     *
     * 旧规则把「纯数字」从行内公式判定里排除掉了，于是 `$0$` 不会被提升成 `$$0$$`、
     * 独自留在行内 —— 而同一段里 `$f_c$` 之类都会被提升，两条路径不一致，
     * 用户看到的就是「单独出现的 $0 渲染不出来」。
     */
    @Test
    fun `单独的数值公式也要提升为块级公式`() {
        val out = normalizeReplyMarkdown("当 \$0\$ 时，取值 \$1\$。")

        assertTrue("数值公式应提升为 \$\$0\$\$，实际：$out", out.contains("\$\$0\$\$"))
        assertTrue("多个数值公式都应处理，实际：$out", out.contains("\$\$1\$\$"))
    }

    @Test
    fun `表格单元格里的数值公式同样被提升`() {
        val out = normalizeReplyMarkdown("| 频率点 | 值 |\n| --- | --- |\n| 0 | \$0\$ |")

        assertTrue("单元格里的数值公式也要渲染，实际：\n$out", out.contains("\$\$0\$\$"))
    }

    @Test
    fun `连续字母公式也要进入公式渲染管线`() {
        val out = normalizeReplyMarkdown("信号为 \$QPSK\$，分量为 \$abc\$ 和 \$x_1\$。")
        assertTrue(out.contains("\$\$QPSK\$\$"))
        assertTrue(out.contains("\$\$abc\$\$"))
        assertTrue(out.contains("\$\$x_1\$\$"))
    }

    @Test
    fun `代码中的美元符号保持原样`() {
        val out = normalizeReplyMarkdown("`\$abc\$`\n```\n\$QPSK\$\n```")
        assertTrue(out.contains("`\$abc\$`"))
        assertTrue(out.contains("\n\$QPSK\$\n"))
    }

    /** 反例：货币写法不能被误判成公式（body 里有空格，仍被挡住）。 */
    @Test
    fun `货币写法不会被误判为公式`() {
        val out = normalizeReplyMarkdown("价格是 \$5 到 \$10 之间")

        assertFalse("不该把金额当公式：$out", out.contains("\$\$5"))
    }
}
