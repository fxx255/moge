package com.moge.app.ui.markdown

import android.content.Context
import io.noties.markwon.ext.tables.TableTheme

/** 表格单元格内边距（dp）。Markwon 默认 4dp，这里收紧一档让单元格更紧凑。 */
internal const val TABLE_CELL_PADDING_DP = 2
private const val TABLE_WIDTH_ESTIMATE_MARGIN = 1.08f

/** 表格分隔行：`|---|---|` / `|:--:|--:|` 这类只由 `|` `-` `:` 空白组成的行。 */
private val TABLE_SEPARATOR_ROW = Regex("""^[|\-:\s]+$""")

/** 行内公式 `$…$` / `$$…$$`。 */
private val INLINE_MATH_CELL = Regex("""\$\$?([^$]+)\$\$?""")

/** 不占显示宽度的强调标记：`**` `*`` ` `` `_` `{` `}` `~`。 */
private val EMPHASIS_MARKS = Regex("""[*`_{}~]""")

/**
 * 估算表格「每列内容都不换行」时需要的宽度（px）。
 *
 * **为什么是估算而不是试排**：这个宽度在流式输出期间会被反复计算，试排意味着
 * 反复 `setText` + `measure`（一次就是一次完整文本排版），在长回答里会直接把
 * 主线程压垮 —— 那正是「长回答里表格拖不动、图点不开」的可疑根因
 * （见 `docs/known-issues.md` #A）。纯文本估算只有微秒级开销。
 *
 * **判据按「均分列宽」来算**：Markwon 把可用宽度**均分**给每一列，而不是按内容
 * 分配。所以「不挤压」的条件不是「各列宽度之和 ≤ 容器宽」，而是
 * 「**最宽那一列**的宽度 × 列数 ≤ 容器宽」。用前者会严重低估需求 —— 例如三列
 * 200/50/50 的表格，和是 300，看起来正好，但均分后每列只有 100，第一列照样被挤换行。
 */
internal fun estimateTableNaturalWidthPx(
    table: String,
    paint: android.text.TextPaint,
    cellPaddingPx: Int,
    /**
     * 测宽函数，默认用 `paint.measureText`。
     *
     * 做成可注入是为了**可测**：Robolectric 不栅格化字体，它的 `measureText` 直接
     * 返回字符数（实测「名称」= 2.0），拿它断言宽度全是假绿/假红。测试里注入一个
     * 确定性的近似实现，「按需撑宽」这个行为才验得动。
     */
    measureText: (String) -> Float = { paint.measureText(it) },
): Int {
    val textSizePx = paint.textSize
    val columnMax = mutableListOf<Float>()

    table.lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && !TABLE_SEPARATOR_ROW.matches(it) }
        .forEach { row ->
            // `| a | b |` → 去掉首尾的竖线再切，得到 [a, b]
            val cells = row.trim('|').split('|')
            cells.forEachIndexed { index, raw ->
                val cell = raw.trim()
                if (cell.isEmpty() && cells.size == 1) return@forEachIndexed
                val width = measureCellTextPx(cell, textSizePx, measureText)
                while (columnMax.size <= index) columnMax += 0f
                if (width > columnMax[index]) columnMax[index] = width
            }
        }

    if (columnMax.isEmpty()) return 0
    // 均分列宽 ⇒ 需要的总宽 = 最宽列 × 列数，而不是各列之和
    val widest = columnMax.maxOrNull() ?: return 0
    val columns = columnMax.size
    val total = widest * columns + columns * 2f * cellPaddingPx
    return (total * TABLE_WIDTH_ESTIMATE_MARGIN).toInt().coerceAtLeast(1)
}

/**
 * 决定表格最终用多宽：放得下就不撑，放不下才撑到刚好够，但不超过上限。
 *
 * 单独抽成纯函数是为了能直接测 —— 「撑不撑、撑多少」正是这次要修的行为，
 * 埋在 Composable 里就没法断言了。
 */
internal fun resolveTableWidthPx(containerWidthPx: Int, naturalWidthPx: Int): Int {
    if (containerWidthPx <= 0) return 0
    return if (naturalWidthPx <= containerWidthPx) containerWidthPx
    else minOf(naturalWidthPx, (containerWidthPx * TABLE_MAX_WIDTH_FACTOR).toInt())
}

/**
 * 测量一个单元格的显示宽度。
 *
 * Markdown 标记不占显示宽度，先剥掉；行内公式**不能**按源码字符宽度算 —— 渲染出来的
 * 公式通常比源码宽得多。这里按源码长度保守估（并给一个小公式的宽度下限），
 * 宁可多撑一点也不能把公式挤变形。
 */
private fun measureCellTextPx(
    cell: String,
    textSizePx: Float,
    measureText: (String) -> Float,
): Float {
    var width = 0f
    var cursor = 0
    INLINE_MATH_CELL.findAll(cell).forEach { match ->
        width += measureText(EMPHASIS_MARKS.replace(cell.substring(cursor, match.range.first), ""))
        val latex = match.groupValues[1]
        width += maxOf(latex.length * 0.5f * textSizePx, textSizePx * 2f)
        cursor = match.range.last + 1
    }
    width += measureText(EMPHASIS_MARKS.replace(cell.substring(cursor), ""))
    return width
}

/**
 * 表格主题：在 Markwon 默认值之上**只改单元格内边距**。
 *
 * 默认内边距来自 `TableTheme.buildWithDefaults` 的 `Dip.toPx(4)` = 4dp，
 * 而行高公式是「单元格内容高 + 2 × padding」（反编译 `TableRowSpan.getSize` 确认）。
 * 于是 density 3 的真机上，每行光上下内边距就吃掉 24px —— 文字很少的表格也会显得很空，
 * 还会把内容顶得显示不下。收紧到 [TABLE_CELL_PADDING_DP]。
 *
 * 注意必须从 [TableTheme.create] 的默认值出发（`asBuilder()`），
 * **不能**改用 `TablePlugin.create { }`：那个入口内部走的是 `emptyBuilder()`，
 * 会把边框宽度/颜色、奇偶行底色一起丢成 0。
 */
internal fun tableThemeFor(context: Context): TableTheme {
    val paddingPx = (TABLE_CELL_PADDING_DP * context.resources.displayMetrics.density)
        .toInt()
        .coerceAtLeast(1)
    return TableTheme.create(context).asBuilder()
        .tableCellPadding(paddingPx)
        .build()
}
