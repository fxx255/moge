package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `wrapLongFormulas` 会把放不下的行内公式拆成多个块级公式——这对正文是对的，
 * 但**绝不能作用在表格行上**：表格行被拆开就再也拼不回表格了。
 *
 * 用户截图里「表格只剩表头和一行、竖线都没了」正是这个：一行
 * `| 频率点 | $$P_{Y_c}$$ |` 里公式量出来超过可用宽度，被拆成
 * 「| 频率点 |」+「$$」+「公式」+「$$」，表格结构当场消失。
 */
class TableFormulaWrappingTest {

    /** 宽测量器：任何公式都「放不下」，从而必然触发拆分路径。 */
    private val alwaysTooWide: (String) -> Int = { 100_000 }

    @Test
    fun `table rows are never split by formula wrapping`() {
        val md = "| 频率点 | \$\$P_{Y_c}\$\$ |\n| --- | --- |\n| \$\$f = 0\$\$ | \$\$4\\pi^2\$\$ |"

        val out = wrapLongFormulas(md, maxWidthPx = 100, measure = alwaysTooWide)

        assertEquals("表格行必须原样放行，实际:\n$out", md, out)
    }

    @Test
    fun `table preceded by paragraph keeps both intact`() {
        val md = "利用公式：\n\n| 频率点 | \$\$P_{Y_c}\$\$ |\n| --- | --- |\n| \$\$f = 0\$\$ | 4 |\n\n后面一段"

        val out = wrapLongFormulas(md, maxWidthPx = 100, measure = alwaysTooWide)

        assertEquals(md, out)
    }

    @Test
    fun `normal long formula in prose is still split`() {
        val md = "结果是 \$\$a + b + c\$\$ 这样。"

        val out = wrapLongFormulas(md, maxWidthPx = 100, measure = alwaysTooWide)

        assertTrue("正文里的超宽公式仍应被拆成块级公式，实际:\n$out", out.contains("\$\$\n"))
    }

    @Test
    fun `pipe line that is not a table is still processed`() {
        val md = "| a | b |\n普通一行"

        val out = wrapLongFormulas(md, maxWidthPx = 100, measure = alwaysTooWide)

        assertEquals("不是表格的行不该被特殊对待", md, out)
    }
}
