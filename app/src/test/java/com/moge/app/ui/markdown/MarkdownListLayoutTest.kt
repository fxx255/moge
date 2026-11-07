package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.moge.app.data.prefs.AppFont
import com.moge.app.ui.theme.appTypeface
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.OrderedListItemSpan
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownListLayoutTest {
    @Test fun numberedEnglishReviewWrapsWithinItsIndentDuringStreamingAndAfterCompletion() {
        val context = RuntimeEnvironment.getApplication()
        val source = """
            ## 重点句子修改

            1. `Dear teacher,`

               能看懂，但英语邮件中这样称呼不自然，最好写具体身份，如：

               > Dear Professor Smith,

            2. `As you know this speech contest is established by our college...`

               说明应自然、准确，继续修改下一句。

            6. `It is your engagement makes us feel honored.`

               即使按强调句理解，也缺少 `that`，而且 `engagement` 在这里不如 `participation` 自然。可改为：

               > We would be honored if you could accept our invitation.

            7. `Looking forward to your response.`

               作为邮件结尾可以看懂，但完整句式更稳妥：

               > I look forward to your response.
        """.trimIndent()
        for ((size, width, font, ink, background) in listOf(
            Fixture(42f, 620, AppFont.WENKAI, Color.WHITE, Color.rgb(30, 43, 38)),
            Fixture(28f, 400, AppFont.WENKAI, Color.WHITE, Color.rgb(30, 43, 38)),
            Fixture(42f, 620, AppFont.SYSTEM, Color.BLACK, Color.WHITE),
            Fixture(28f, 1000, AppFont.WENKAI, Color.BLACK, Color.WHITE),
        )) {
            val views = listOf(false, true).map { incremental ->
                createMarkdownTextView(context, ink, ink, fontSizePx = size,
                    bodyTypeface = appTypeface(context, font), incrementalUpdates = incremental).apply {
                    layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                    if (incremental) setSpannableFactory(StreamingTextBufferFactory)
                }
            }
            var buffer: CharSequence? = null
            var layout: android.text.Layout? = null
            val tokenizer = StreamingMarkdownTokenizer()
            val ends = listOf(source.indexOf("能看懂"), source.indexOf("Dear Professor") + 5,
                source.indexOf("2. `"), source.indexOf("作为邮件") + 10, source.length)
            for (end in ends) {
                val partial = source.take(end)
                val raw = tokenizer.update(partial)
                val snapshot = raw.copy(blocks = emptyList(), tail = partial, tailStart = 0)
                for (view in views) {
                    renderStreamingMarkdown(view, snapshot, width, ink, ink)
                    measure(view, width)
                }
                if (buffer == null) { buffer = views[1].text; layout = views[1].layout }
                assertSame(buffer, views[1].text)
                assertSame(layout, views[1].layout)
                assertSameLayoutAndInk(views[0], views[1], background)
            }
            Selection.setSelection(views[1].text as Spannable, 0, 4)
            for ((index, view) in views.withIndex()) {
                renderStreamingMarkdown(view, tokenizer.update(source, flush = true).copy(blocks = emptyList(),
                    tail = source, tailStart = 0, renderedPrefixEnd = source.length, visibleEnd = source.length),
                    width, ink, ink)
                measure(view, width)
                if (System.getenv("LIST_LAYOUT_EXPORT_SAMPLES") == "1") {
                    val bitmap = draw(view, background)
                    try {
                        val file = File("build/list-text-layout/list-${font.name}-${size.toInt()}-$width-$index.png")
                        file.parentFile!!.mkdirs()
                        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { bitmap.recycle() }
                }
            }
            assertSame(buffer, views[1].text)
            assertSame(layout, views[1].layout)
            assertEquals(0, views[1].selectionStart)
            assertEquals(4, views[1].selectionEnd)
            assertSameLayoutAndInk(views[0], views[1], background)
            assertEquals(views[1].text.toString(), selectedMarkdownText(views[1].text, 0, views[1].text.length))
        }
    }

    @Test fun quotesAndNestedBulletIndentationReflowWhenAnExistingListGrows() {
        val context = RuntimeEnvironment.getApplication()
        val current = createMarkdownTextView(context, Color.BLACK, Color.BLUE, fontSizePx = 34f,
            incrementalUpdates = true).apply { setSpannableFactory(StreamingTextBufferFactory) }
        val reference = createMarkdownTextView(context, Color.BLACK, Color.BLUE, fontSizePx = 34f)
        val source = """
            - **第一项**

              这里的中文说明应根据列表缩进正确换行，完整显示这一段的每一个字。

              > 引用中的长文字也要扣除两层缩进，能够自动换行，不能超出右侧边界。
              >
              > - 子项目的缩进需要叠加，英文 **participation** 与中文混排也不能被遮挡。

            - **第二项**

              更多说明文字，在流式完成时仍应保持位置。
        """.trimIndent()
        val tokenizer = StreamingMarkdownTokenizer()
        val start = source.indexOf("这里") + 1
        for (end in (start until source.length step 11).toList() + source.length) {
            val partial = source.take(end)
            val snapshot = tokenizer.update(partial, flush = true).copy(blocks = emptyList(), tail = partial,
                tailStart = 0, renderedPrefixEnd = partial.length, visibleEnd = partial.length)
            for (view in listOf(reference, current)) {
                renderStreamingMarkdown(view, snapshot, 480, Color.BLACK, Color.BLUE)
                measure(view, 480)
            }
            assertSameLayoutAndInk(reference, current, Color.WHITE)
        }
    }

    @Test fun looseListParagraphsAndQuoteToNextItemHaveEqualCompactGapsWhileTightListsStayCompact() {
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, fontSizePx = 40f)
        (view.tag as Markwon).setMarkdown(view, "1. 原句。\n\n   说明。\n\n   > 引用。\n\n2. 下一条。")
        measure(view, 620)
        val text = view.text.toString()
        val gaps = (0 until view.layout.lineCount).filter { line ->
            text.substring(view.layout.getLineStart(line), view.layout.getLineEnd(line)) == "\n"
        }.map { view.layout.getLineBottom(it) - view.layout.getLineTop(it) }
        assertEquals("Paragraph, quote and next item need the same separator", 3, gaps.size)
        assertTrue(gaps.toString(), gaps.distinct().size == 1)
        assertTrue("Separators must be smaller than a full empty line", gaps[0] < view.lineHeight)
        val spans = view.text as Spanned
        spans.getSpans(0, spans.length, OrderedListItemSpan::class.java).forEach { span ->
            assertFalse("Numbers must sit beside text, not on the separator", spans[spans.getSpanStart(span)].isWhitespace())
        }
        (view.tag as Markwon).setMarkdown(view, "1. 第一条\n2. 第二条")
        measure(view, 620)
        assertEquals("第一条\n第二条", view.text.toString())
        assertEquals(2, view.layout.lineCount)
    }

    private fun assertSameLayoutAndInk(reference: TextView, current: TextView, background: Int) {
        assertEquals(reference.text.toString(), current.text.toString())
        assertEquals(reference.height, current.height)
        assertEquals(reference.layout.lineCount, current.layout.lineCount)
        for (line in 0 until current.layout.lineCount) {
            assertEquals(reference.layout.getLineStart(line), current.layout.getLineStart(line))
            assertEquals(reference.layout.getLineBottom(line), current.layout.getLineBottom(line))
            assertTrue("Line $line exceeds the right edge", current.layout.getLineRight(line) <= current.layout.width + 1)
        }
        if (current.hasSelection()) Selection.setSelection(reference.text as Spannable, current.selectionStart, current.selectionEnd)
        val before = draw(reference, background)
        val after = draw(current, background)
        try { assertTrue("Incremental layout must paint the same ink", before.sameAs(after)) }
        finally { before.recycle(); after.recycle() }
    }

    private fun draw(view: TextView, background: Int) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply { drawColor(background); view.draw(this) }
    }

    private fun measure(view: TextView, width: Int) {
        view.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private data class Fixture(val size: Float, val width: Int, val font: AppFont, val ink: Int, val background: Int)
}
