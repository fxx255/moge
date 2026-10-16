package com.moge.app.ui.markdown

import org.junit.Assert.*
import org.junit.Test

/** Column allocation must preserve short labels and spend available width on prose. */
class TableWidthEstimateTest {
    private fun widths(source: String, available: Int = 360, size: Float = 17f): List<Int> =
        tableColumnWidthsPx(parseMarkdownTable(source)!!, available, size, 6,
            { text -> text.sumOf { if (it.code > 0x2E80) size.toDouble() else size * 0.55 }.toFloat() },
            { 80 })

    @Test fun `score and question columns stay compact while explanations wrap within the page`() {
        val table = "| 小题 | 得分 | 说明 |\n|---|---:|---|\n| (1) | 0/3 | 结果错误 |\n| 总分 | 7/12 | ${"较长的结果说明".repeat(20)} |"
        val result = widths(table)
        assertEquals(360, result.sum())
        assertTrue(result[0] < 70)
        assertTrue(result[1] < 70)
        assertTrue(result[2] > result[0] + result[1])
        assertEquals(result.take(2), widths(table.replace("较长的结果说明".repeat(20), "更多说明".repeat(40))).take(2))
    }

    @Test fun `short tables keep their content width instead of stretching every cell`() {
        val table = "| 名称 | 数值 |\n|---|---|\n| 速度 | 5 |"
        val compact = widths(table)
        assertTrue(compact.sum() < 180)
        assertEquals(compact, widths(table, available = 900))
    }

    @Test fun `genuinely wide formula tables scroll without squeezing indivisible formulas`() {
        val header = List(8) { "列" }.joinToString("|", "|", "|")
        val divider = List(8) { "---" }.joinToString("|", "|", "|")
        val values = List(8) { "${'$'}x${'$'}" }.joinToString("|", "|", "|")
        val result = widths("$header\n$divider\n$values")
        assertEquals(8, result.size)
        assertTrue(result.all { it >= 92 })
        assertTrue(result.sum() > 360)
    }

    @Test fun `escaped pipes code and alignment survive the shared table parser`() {
        val table = parseMarkdownTable("| A | B | C |\n|:---|:---:|---:|\n| a\\|b | `c|d` | 2 |")!!
        assertEquals(listOf("a\\|b", "`c|d`", "2"), table.rows.single())
        assertEquals(listOf(TableColumnAlignment.Start, TableColumnAlignment.Center, TableColumnAlignment.End), table.alignments)
    }

    @Test fun `large font and unmeasured viewport keep safe readable dimensions`() {
        val table = "| 项目 | 内容 |\n|---|---|\n| 短 | ${"较长说明".repeat(12)} |"
        val normal = widths(table)
        val large = widths(table, size = 30f)
        assertTrue(large[0] > normal[0])
        assertTrue(large.all { it > 0 })
        assertTrue(widths(table, available = 0).isEmpty())
    }
}
