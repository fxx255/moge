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
class StreamingTextBufferTest {
    @Test fun `plain prose also grows in place before any formatting arrives`() {
        val view = view().apply { setSpannableFactory(StreamingTextBufferFactory) }
        val tokenizer = StreamingMarkdownTokenizer()
        var source = "普通文字开头。"
        renderStreamingMarkdown(view, tokenizer.update(source), 400, Color.BLACK, Color.BLUE)
        measure(view)
        val buffer = view.text
        val layout = view.layout
        repeat(5) {
            source += " 继续显示新收到的文字。"
            renderStreamingMarkdown(view, tokenizer.update(source), 400, Color.BLACK, Color.BLUE)
            measure(view)
            assertEquals(source, view.text.toString())
            assertSame(buffer, view.text)
            assertSame(layout, view.layout)
        }
    }

    @Test fun `literal arrivals update native layout in place with identical ink metrics and selection`() {
        val current = view().apply { setSpannableFactory(StreamingTextBufferFactory) }
        val reference = view()
        val tokenizer = StreamingMarkdownTokenizer()
        var source = "**重点** 和 ${'$'}\\frac{x+1}{2}${'$'}，继续说明。"
        fun render() {
            val snapshot = tokenizer.update(source)
            for (view in listOf(current, reference)) {
                renderStreamingMarkdown(view, snapshot, 400, Color.BLACK, Color.BLUE)
                measure(view)
            }
        }
        render()
        val layout = current.layout
        val original = current.text as Spannable
        val formula = (current.text as Spanned).getSpans(0, current.text.length, BaselineLatexSpan::class.java).single()
        Selection.setSelection(original, 0, 2)
        repeat(5) {
            source += " 一段追加的普通文字以及 emoji 😀。"
            render()
            assertSame("Arrivals must update the existing buffer", original, current.text)
            assertSame("Appending prose must reuse DynamicLayout", layout, current.layout)
            assertEquals(reference.text.toString(), current.text.toString())
            assertEquals(reference.height, current.height)
            assertEquals(reference.layout.lineCount, current.layout.lineCount)
            for (line in 0 until current.layout.lineCount) {
                assertEquals(reference.layout.getLineStart(line), current.layout.getLineStart(line))
                assertEquals(reference.layout.getLineBottom(line), current.layout.getLineBottom(line))
            }
            assertEquals(0, current.selectionStart)
            assertEquals(2, current.selectionEnd)
            assertEquals("重点", selectedMarkdownText(current.text as Spanned, 0, 2))
            assertSame(formula, (current.text as Spanned).getSpans(0, current.text.length, BaselineLatexSpan::class.java).single())
            Selection.setSelection(reference.text as Spannable, 0, 2)
            val before = draw(reference)
            val after = draw(current)
            try { assertTrue("Appending must preserve the exact painted result", before.sameAs(after)) }
            finally { before.recycle(); after.recycle() }
        }
        // Closing new syntax still runs the normal parser and adds a ready formula.
        source += " ${'$'}y=3${'$'}。"
        render()
        assertEquals(reference.text.toString(), current.text.toString())
        assertEquals(reference.height, current.height)
        val math = (current.text as Spanned).getSpans(0, current.text.length, BaselineLatexSpan::class.java)
        assertEquals(2, math.size)
        assertTrue(math.all { it.drawable.hasResult() })
    }

    private fun view() = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, fontSizePx = 28f).apply {
        layoutParams = ViewGroup.LayoutParams(400, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun draw(view: TextView) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }
    private fun measure(view: TextView) {
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, 400, view.measuredHeight)
    }
}
