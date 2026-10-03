package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.ext.tables.TableTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid

/**
 * 表格渲染的两条硬约束（v1.0.33 用户反馈「表格上下拖动与页面滚动冲突 / 单元格很空」）。
 *
 * 一、**表格块不能有 MovementMethod**。
 * `LinkMovementMethod` 继承 `ScrollingMovementMethod`，只要表格内容比 Compose 给的格位高，
 * 用户就能在表格里上下拖动文字 —— 那会和页面的纵向滚动直接打架。表格的横向滚动由外层
 * Compose 的 `horizontalScroll` 负责，纵向交给页面，所以表格块必须把 MovementMethod 留空。
 *
 * 二、**单元格内边距要收紧**。
 * 行高公式是「单元格内容高 + 2 × padding」（`TableRowSpan.getSize`），Markwon 默认 4dp
 * 在 density 3 的真机上每行上下要吃掉 24px，文字很少的表格也会显得很空。
 *
 * 另外这里顺便钉住「表格行高为什么必须复测」这个根因，确认异步 span 的正常布局请求仍能生效
 * （详见该用例注释与 [MarkdownAsyncLayoutTest]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkdownTableRenderTest {

    private val markdown = "| 项目 | 说明 | 备注 |\n|---|---|---|\n| A | 短 | 短 |\n| B | 短 | 短 |"
    private val renderWidth = 900

    private val ctx: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        // TeXFormula 的静态初始化要求先 init，否则同 JVM 的其它测试会被粘性污染
        JLatexMathAndroid.init(ctx)
    }

    private fun newHost(selectable: Boolean): TextView =
        createMarkdownTextView(ctx, Color.BLACK, Color.BLUE, selectable = selectable).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

    private fun TextView.measureWidth(widthPx: Int = renderWidth): Int {
        measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return measuredHeight
    }

    private fun rowHeights(view: TextView): List<Int> {
        val field = TableRowSpan::class.java.getDeclaredField("height").apply { isAccessible = true }
        val spans = (view.text as Spanned).getSpans(0, view.text.length, TableRowSpan::class.java)
        return spans.map { field.getInt(it) }
    }

    @Test
    fun `表格块不挂 MovementMethod 以免表格内部纵向拖动与页面滚动打架`() {
        val table = newHost(selectable = false)
        assertNull(
            "表格块必须没有 MovementMethod：LinkMovementMethod 继承 ScrollingMovementMethod，" +
                "一旦表格内容比格位高就能在表格里上下拖动",
            table.movementMethod,
        )
    }

    @Test
    fun `正文块保留 MovementMethod 以便长按选中与链接点击`() {
        val body = newHost(selectable = true)
        assertNotNull("正文块的链接/选中依赖 MovementMethod，不能一起关掉", body.movementMethod)
    }

    @Test
    fun `单元格内边距收紧为 2dp 且严格小于 Markwon 默认的 4dp`() {
        val tightened = tableThemeFor(ctx).tableCellPadding()
        val default = TableTheme.create(ctx).tableCellPadding()
        val density = ctx.resources.displayMetrics.density

        assertEquals("应恰好是 2dp", (2f * density).toInt().coerceAtLeast(1), tightened)
        assertTrue(
            "默认是 ${default}px（4dp），收紧后是 ${tightened}px，必须更小",
            tightened < default,
        )
    }

    @Test
    fun `表格行高必须强制重排后才算得出来——这就是表格块要复测高度的原因`() {
        val table = newHost(selectable = false)
        (table.tag as Markwon).setMarkdown(table, markdown)

        val heightBefore = table.measureWidth()
        val rowsBefore = rowHeights(table)

        // 首帧绘制：TableRowSpan 在这一步才按可用宽度填充内部 layouts
        table.layout(0, 0, renderWidth, heightBefore.coerceAtLeast(1))
        table.draw(
            Canvas(
                Bitmap.createBitmap(
                    renderWidth,
                    heightBefore.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                ),
            ),
        )
        // Android 会复用宽度/文本都没变的旧 Layout，绘制后直接 measure 拿到的仍是旧高度
        assertEquals("绘制本身不会让行高生效", rowsBefore, rowHeights(table))

        // 强制重排：重建 Layout，getSize() 才会用已填充的 layouts 重算行高
        table.setText(table.text)
        val heightAfter = table.measureWidth()
        val rowsAfter = rowHeights(table)

        assertTrue("重排前行高应当还是 0（getSize 没拿到 layouts）：$rowsBefore", rowsBefore.all { it == 0 })
        assertTrue("重排后每行都必须有真实高度：$rowsAfter", rowsAfter.all { it > 0 })
        assertTrue(
            "重排后整表必须变高（$heightBefore → $heightAfter），" +
                "否则报给 Compose 的高度会偏小、内容溢出后可被上下拖动",
            heightAfter > heightBefore,
        )
    }
}
