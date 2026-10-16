package com.moge.app.ui.markdown

import android.content.Context
import io.noties.markwon.ext.tables.TableTheme
import kotlin.math.ceil

internal const val TABLE_CELL_PADDING_DP = 2

private val TABLE_INLINE_MATH = Regex("""\$\$?([^$]+)\$\$?""")
private val TABLE_EMPHASIS = Regex("""[*`_{}~]""")
private val TABLE_LINK = Regex("""\[([^]]*)]\([^)]*\)""")

/** Short columns keep their natural width. Only longer prose columns give up space and wrap. */
internal fun tableColumnWidthsPx(
    table: MarkdownTable,
    availableWidthPx: Int,
    textSizePx: Float,
    cellInsetPx: Int,
    measureText: (String) -> Float,
    measureFormula: (String) -> Int,
): List<Int> {
    if (availableWidthPx <= 0 || table.columns == 0) return emptyList()
    val rows = listOf(table.headers) + table.rows
    val preferred = IntArray(table.columns)
    val minimum = IntArray(table.columns)
    for (column in 0 until table.columns) {
        var natural = textSizePx
        var widestFormula = 0f
        for (row in rows) {
            val cell = row[column]
            val math = TABLE_INLINE_MATH.findAll(cell).toList()
            var cursor = 0
            var width = 0f
            fun plain(source: String): Float = measureText(TABLE_EMPHASIS.replace(
                TABLE_LINK.replace(source, "$1"), ""))
            for (match in math) {
                width += plain(cell.substring(cursor, match.range.first))
                val formula = measureFormula(match.groupValues[1]).coerceAtLeast(0).toFloat()
                widestFormula = maxOf(widestFormula, formula)
                width += formula
                cursor = match.range.last + 1
            }
            width += plain(cell.substring(cursor))
            natural = maxOf(natural, width)
        }
        val padding = cellInsetPx.coerceAtLeast(0) * 2
        preferred[column] = ceil(natural * 1.08f).toInt() + padding
        // Four characters are readable for prose. An indivisible formula retains its real width.
        minimum[column] = ceil(maxOf(minOf(natural, textSizePx * 4f), widestFormula) * 1.08f).toInt() + padding
    }
    if (preferred.sum() <= availableWidthPx) return preferred.toList()
    if (minimum.sum() >= availableWidthPx) return minimum.toList()
    val room = availableWidthPx - minimum.sum()
    val demand = preferred.indices.sumOf { preferred[it] - minimum[it] }.toDouble()
    val widths = minimum.mapIndexed { index, width ->
        width + (room * (preferred[index] - width) / demand).toInt()
    }.toMutableList()
    var remainder = availableWidthPx - widths.sum()
    for (index in widths.indices) {
        if (remainder > 0 && widths[index] < preferred[index]) { widths[index]++; remainder-- }
    }
    return widths
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
