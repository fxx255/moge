package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.Editable
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
    @Test fun `closing formats and paragraphs keeps stable ink layout and selection without replacing the document`() {
        val current = view(incremental = true).apply { setSpannableFactory(StreamingTextBufferFactory) }
        val reference = view()
        val tokenizer = StreamingMarkdownTokenizer()
        var source = "**已显示内容** 和 ${'$'}x=2${'$'}，这里保留正文。\n\n当前段落开始。"
        fun render(flush: Boolean = false) {
            val raw = tokenizer.update(source, flush)
            val snapshot = raw.copy(blocks = emptyList(), tail = source, tailStart = 0,
                renderedPrefixEnd = if (flush) source.length else raw.renderedPrefixEnd)
            for (view in listOf(current, reference)) {
                renderStreamingMarkdown(view, snapshot, 400, Color.BLACK, Color.BLUE)
                measure(view)
            }
            assertSameInk(reference, current)
        }
        render()
        val original = current.text as Spannable
        val layout = current.layout
        val firstFormula = (original as Spanned).getSpans(0, original.length, BaselineLatexSpan::class.java).single()
        Selection.setSelection(original, 0, 5)
        val replacements = mutableListOf<Int>()
        current.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { replacements += start }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        for (suffix in listOf(" **新格式**。", " ${'$'}\\frac{y+1}{2}${'$'}。", "\n\n下一段普通文字。", "\n\n再一段 **重点** 😀𠮷。")) {
            source += suffix
            render()
            assertSame(original, current.text)
            assertSame(layout, current.layout)
            assertEquals(0, current.selectionStart)
            assertEquals(5, current.selectionEnd)
            assertSame(firstFormula, (current.text as Spanned).getSpans(0, current.text.length, BaselineLatexSpan::class.java).first())
        }
        render(flush = true)
        assertSame(original, current.text)
        assertSame(layout, current.layout)
        assertTrue("Stable paragraphs must not be replaced when later formatting closes", replacements.all { it > 5 })
    }

    @Test fun `in place formatting matches full rendering for lists quotes references and code`() {
        val cases = listOf(
            "开头。\n\n1. 第一项" to listOf("\n2. 第二项 **重点**", "\n\n    下一段说明。"),
            "> 引用 **重点**" to listOf("\n> 第二行", "\n>\n> 新段落 ${'$'}x=2${'$'}"),
            "先前 [链接][example]。" to listOf("\n\n后文", "\n\n[example]: https://example.com\n\n结尾。"),
            "第一段。\n\n标题" to listOf("\n---\n\n正文。", "\n\n```text\n**literal**\n```\n\n结尾。"),
        )
        for ((initial, suffixes) in cases) {
            val current = view(incremental = true).apply { setSpannableFactory(StreamingTextBufferFactory) }
            val reference = view()
            val tokenizer = StreamingMarkdownTokenizer()
            var source = initial
            for (suffix in listOf("") + suffixes) {
                source += suffix
                val raw = tokenizer.update(source, flush = true)
                val snapshot = raw.copy(blocks = emptyList(), tail = source, tailStart = 0, renderedPrefixEnd = source.length)
                for (view in listOf(current, reference)) {
                    renderStreamingMarkdown(view, snapshot, 400, Color.BLACK, Color.BLUE)
                    measure(view)
                }
                assertSameInk(reference, current)
            }
        }
    }

    @Test fun `source edits use the full setter and reading snapshots never mutate with the buffer`() {
        val current = view(incremental = true).apply { setSpannableFactory(StreamingTextBufferFactory) }
        val tokenizer = StreamingMarkdownTokenizer()
        fun render(source: String) {
            renderStreamingMarkdown(current, tokenizer.update(source, flush = true).copy(tail = source,
                tailStart = 0, renderedPrefixEnd = source.length), 400, Color.BLACK, Color.BLUE)
            measure(current)
        }
        render("[链接](https://example.com/old)")
        val original = current.text
        val snapshot = markdownTextSnapshot(current)
        assertSame(snapshot, markdownTextSnapshot(current))
        render("[链接](https://example.com/new)")
        assertNotSame("Non-append edits must not reuse stale span properties", original, current.text)
        render("原来正文。")
        val old = markdownTextSnapshot(current)
        render("原来正文。追加内容。")
        assertEquals("原来正文。", old)
        assertEquals("原来正文。追加内容。", markdownTextSnapshot(current))
    }

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

    private fun view(incremental: Boolean = false) = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK,
        Color.BLUE, fontSizePx = 28f, incrementalUpdates = incremental).apply {
        layoutParams = ViewGroup.LayoutParams(400, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun draw(view: TextView) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }
    private fun assertSameInk(reference: TextView, current: TextView) {
        assertEquals(reference.text.toString(), current.text.toString())
        assertEquals(reference.height, current.height)
        assertEquals(reference.layout.lineCount, current.layout.lineCount)
        if (current.selectionStart >= 0 && current.selectionEnd >= 0)
            Selection.setSelection(reference.text as Spannable, current.selectionStart, current.selectionEnd)
        val before = draw(reference)
        val after = draw(current)
        try { assertTrue("Incremental formatting changed painted ink", before.sameAs(after)) }
        finally { before.recycle(); after.recycle() }
    }
    private fun measure(view: TextView) {
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, 400, view.measuredHeight)
    }
}
