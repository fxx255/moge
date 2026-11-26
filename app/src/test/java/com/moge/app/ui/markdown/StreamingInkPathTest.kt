package com.moge.app.ui.markdown

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingInkPathTest {
    @Test fun `compact clip matches full selection geometry including margins math bidi and emoji`() {
        val source = "# 标题 heading\n\n21. 第一项 **加粗** 和 emoji 😀 继续换行。".repeat(2) +
            "\n22. 第二项 ${'$'}\\frac{x+1}{2}${'$'}\n\n> 引用的一行\n> 下一行 quote " +
            "continued words ".repeat(16) + "\n\nمرحبا بالعالم 中文 ABC 😀\n\n" +
            "普通段落 ordinary paragraph words. ".repeat(12)
        for (size in listOf(28f, 44f)) {
            val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, fontSizePx = size)
            view.setLineSpacing(7f, 1.15f)
            renderMarkdown(view, source, 400, Color.BLACK, Color.BLUE, stableLayout = true)
            view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, 400, view.measuredHeight)
            val layout = view.layout
            val text = view.text as Spanned
            val positions = mutableSetOf(0f, .5f, text.length - .25f)
            for (line in listOf(0, 1, 2, 3, layout.lineCount / 2, layout.lineCount - 1)) {
                positions += layout.getLineStart(line).toFloat()
                positions += (layout.getLineStart(line) + .35f).coerceAtMost(text.length.toFloat())
                positions += (layout.getLineStart(line) + 3.6f).coerceAtMost(text.length.toFloat())
            }
            for (span in text.getSpans(0, text.length, BaselineLatexSpan::class.java)) {
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                positions += start + .25f
                positions += (start + end) / 2f
                positions += end - .1f
            }
            val emoji = text.indexOf("😀")
            assertTrue(emoji >= 0)
            positions += emoji + 1.4f
            positions += emoji + .5f
            for (visible in positions) {
                val expected = Path().apply { originalPath(layout, text, visible, this) }
                val actual = Path().apply { streamingInkPath(layout, text, visible, this, Path()) }
                val before = mask(layout, expected)
                val after = mask(layout, actual)
                try { assertTrue("Reveal changed at $visible, size=$size", before.sameAs(after)) }
                finally { before.recycle(); after.recycle() }
            }
        }
    }

    @Test fun `appending the next visual line preserves the partially painted last glyph`() {
        for (glyph in listOf("M", "田", "ש")) {
            val view = TextView(RuntimeEnvironment.getApplication()).apply {
                textSize = 32f
                layoutParams = ViewGroup.LayoutParams(-2, -2)
            }
            val row = glyph.repeat(4)
            val width = ceil(view.paint.measureText(row)).toInt() + 1
            fun frame(text: String): Bitmap {
                view.text = text
                layoutAt(view, width)
                val layout = view.layout
                assertEquals(row.length, layout.getLineEnd(0))
                val path = Path()
                streamingInkPath(layout, view.text, row.length - .35f, path, Path())
                return mask(layout, path)
            }
            val before = frame(row)
            val after = frame(row + row)
            try {
                for (x in 0 until width) {
                    val y = before.height / 2
                    assertEquals("$glyph: line-end ink changed at x=$x after wrapping", before.getPixel(x, y), after.getPixel(x, y))
                }
            } finally { before.recycle(); after.recycle() }
        }
    }

    @Test fun `moving the viewport past a partial line does not erase it from the recorded frame`() {
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            val view = TextView(activity.get())
            activity.get().setContentView(view)
            val host = ReadingScrollHost { 0f }
            val block = ReadingBlock().also { it.view = view }
            val reveal = StreamingInkReveal(host, block)
            view.text = "First row\nSecond row\nPreviously visible"
            reveal.update(view, catchingUp = false)
            view.append(" and arriving text")
            layoutAt(view, 500)
            reveal.enabled = true
            reveal.update(view, catchingUp = false)
            fun frame(bottom: Float): Bitmap {
                host.viewport = RectF(0f, 0f, 500f, bottom)
                return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                    Canvas(it).apply { reveal.clip(this, view); view.draw(this) }
                }
            }
            val top = requireNotNull(block.bounds()).top
            val visible = frame(top + view.height)
            val offscreen = frame(top + view.layout.getLineTop(2) - 1)
            try { assertTrue("Scrolling can replay this frame before the next animation tick", visible.sameAs(offscreen)) }
            finally { visible.recycle(); offscreen.recycle(); reveal.finish(view) }
        }
    }

    private fun layoutAt(view: TextView, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }

    @Test fun `prepending a summary never clips previously readable ink on the first edited frame`() {
        val reveal = StreamingInkReveal()
        val view = android.widget.TextView(RuntimeEnvironment.getApplication())
        val body = (1..30).joinToString("\n") { "Original line $it" }
        view.text = body
        reveal.update(view, catchingUp = false)
        reveal.enabled = true
        view.text = "Final summary\n\n$body"
        view.measure(View.MeasureSpec.makeMeasureSpec(340, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, 340, view.measuredHeight)
        reveal.update(view, catchingUp = true)
        val bitmap = Bitmap.createBitmap(340, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            val original = canvas.clipBounds
            reveal.clip(canvas, view)
            org.junit.Assert.assertEquals(original, canvas.clipBounds)
        } finally { bitmap.recycle() }
    }

    private fun mask(layout: Layout, path: Path) =
        Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { clipPath(path); drawColor(Color.WHITE) }
        }

    /** Reference draws the entire native selection rather than merging completed rows. */
    private fun originalPath(layout: Layout, text: Spanned, visible: Float, path: Path) {
        val offset = visible.toInt().coerceIn(0, text.length)
        var start = offset
        var end = minOf(offset + 1, text.length)
        var fraction = visible - offset
        text.getSpans(offset, end, BaselineLatexSpan::class.java).firstOrNull()?.let { span ->
            start = text.getSpanStart(span)
            end = text.getSpanEnd(span)
            fraction = ((visible - start) / (end - start).coerceAtLeast(1)).coerceIn(0f, 1f)
        }
        if (start > 0 && start < text.length && Character.isLowSurrogate(text[start])) start--
        layout.getSelectionPath(0, start, path)
        if (end > start && text[start] != '\n') {
            val line = layout.getLineForOffset(start)
            val x = layout.getPrimaryHorizontal(start)
            val target = if (layout.getLineForOffset(end) == line) layout.getPrimaryHorizontal(end)
                else if (layout.getParagraphDirection(line) > 0) layout.getLineRight(line) else layout.getLineLeft(line)
            val right = x + (target - x) * fraction
            path.addRect(minOf(x, right), layout.getLineTop(line).toFloat(), maxOf(x, right),
                layout.getLineBottom(line).toFloat(), Path.Direction.CW)
        }
        if (visible <= 0f) return
        val revealedEnd = ceil(visible.toDouble()).toInt().coerceAtMost(text.length)
        val lastLine = layout.getLineForOffset((revealedEnd - 1).coerceAtLeast(0))
        for (line in 0..lastLine) {
            val lineStart = layout.getLineStart(line)
            val lineEnd = minOf(revealedEnd, layout.getLineEnd(line))
            if (lineStart >= lineEnd || text.getSpans(lineStart, lineEnd, LeadingMarginSpan::class.java).isEmpty()) continue
            val x = layout.getPrimaryHorizontal(lineStart)
            val left = if (layout.getParagraphDirection(line) > 0) 0f else x
            val right = if (layout.getParagraphDirection(line) > 0) x else layout.width.toFloat()
            if (left < right) path.addRect(left, layout.getLineTop(line).toFloat(), right,
                layout.getLineBottom(line).toFloat(), Path.Direction.CW)
        }
    }
}
