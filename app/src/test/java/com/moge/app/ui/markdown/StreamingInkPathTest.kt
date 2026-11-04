package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.text.Layout
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingInkPathTest {
    @Test fun `compact clip paints exactly the original reveal including margins math bidi and emoji`() {
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

    private fun mask(layout: Layout, path: Path) =
        Bitmap.createBitmap(layout.width, layout.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { clipPath(path); drawColor(Color.WHITE) }
        }

    /** The pre-optimization geometry is the visual contract, including native selection edges. */
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
        if (end > start) {
            val line = layout.getLineForOffset(start)
            val x = layout.getPrimaryHorizontal(start)
            val target = layout.getPrimaryHorizontal(end)
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
