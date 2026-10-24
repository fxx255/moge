package com.moge.app.ui.markdown

import com.moge.app.ui.theme.LocalAppTypeface
import android.graphics.Paint
import android.text.TextPaint
import android.view.View
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.CollectionItemInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.moge.app.core.LatexEngine
import com.moge.app.data.parse.prepareReplyMarkdown
import com.moge.app.ui.components.excludePageSwipe
import kotlin.math.ceil

/** Native Markdown cells share column widths, while each row measures its tallest wrapped cell. */
@Composable
internal fun MarkdownTableBlock(source: String) {
    val table = remember(source) { parseMarkdownTable(prepareReplyMarkdown(source)) }
    if (table == null) {
        MarkdownChunk(source, fixedWidthPx = null, selectable = false)
        return
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val textSize = with(density) { MARKDOWN_TEXT_SIZE_SP.sp.toPx() }
    val bodyTypeface = LocalAppTypeface.current
    val paint = remember(textSize, bodyTypeface) { TextPaint(Paint.ANTI_ALIAS_FLAG).apply { this.textSize = textSize; typeface = bodyTypeface } }
    val measureFormula = remember(context, textSize) {
        LatexEngine.init(context)
        val cache = mutableMapOf<String, Int>()
        val measure = formulaWidthMeasurer(textSize)
        val cached: (String) -> Int = { latex -> cache.getOrPut(latex) { measure(latex) } }
        cached
    }
    val padding = TABLE_CELL_PADDING_DP.dp
    val inset = with(density) { padding.roundToPx() } + ceil(textSize * 0.25f).toInt()
    val colors = MaterialTheme.colorScheme
    val border = colors.outlineVariant
    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds().zIndex(FIGURE_Z_INDEX).testTag("markdown-table")) {
        val widths = remember(table, constraints.maxWidth, textSize, inset, bodyTypeface) {
            tableColumnWidthsPx(table, constraints.maxWidth, textSize, inset, paint::measureText, measureFormula)
        }
        if (widths.isEmpty()) return@BoxWithConstraints
        val rows = remember(table) { listOf(table.headers) + table.rows }
        Column(Modifier.excludePageSwipe().horizontalScroll(rememberScrollState()).testTag("markdown-table-scroll")) {
            Column(Modifier.width(with(density) { widths.sum().toDp() })
                .semantics { collectionInfo = CollectionInfo(rows.size, table.columns) }) {
                rows.forEachIndexed { rowIndex, row ->
                    key(rowIndex) {
                        val fill = colors.onSurface.copy(alpha = if (rowIndex == 0) 0.08f else if (rowIndex % 2 == 0) 0.04f else 0f)
                        TableRow(widths, border, fill, Modifier.testTag("markdown-table-row-$rowIndex")) {
                            row.forEachIndexed { column, cell ->
                                Box(Modifier.testTag("markdown-table-cell-$rowIndex-$column")
                                    .semantics { collectionItemInfo = CollectionItemInfo(rowIndex, 1, column, 1) }
                                    .padding(padding)) {
                                    MarkdownChunk(cell, fixedWidthPx = null, selectable = false, bold = rowIndex == 0,
                                        textAlignment = when (table.alignments[column]) {
                                            TableColumnAlignment.Start -> View.TEXT_ALIGNMENT_TEXT_START
                                            TableColumnAlignment.Center -> View.TEXT_ALIGNMENT_CENTER
                                            TableColumnAlignment.End -> View.TEXT_ALIGNMENT_TEXT_END
                                        })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TableRow(widths: List<Int>, border: Color, fill: Color, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth().drawBehind {
        drawRect(fill)
        val stroke = 0.5.dp.toPx()
        drawRect(border, style = Stroke(stroke))
        var x = 0f
        for (width in widths.dropLast(1)) {
            x += width
            drawLine(border, Offset(x, 0f), Offset(x, size.height), stroke)
        }
    }) { measurables, constraints ->
        val cells = measurables.mapIndexed { index, measurable ->
            measurable.measure(Constraints.fixedWidth(widths[index]))
        }
        layout(constraints.constrainWidth(widths.sum()), constraints.constrainHeight(cells.maxOfOrNull { it.height } ?: 0)) {
            var x = 0
            for (cell in cells) { cell.placeRelative(x, 0); x += cell.width }
        }
    }
}
