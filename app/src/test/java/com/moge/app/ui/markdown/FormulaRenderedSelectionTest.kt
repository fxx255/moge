package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.text.Spanned
import android.view.View
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
class FormulaRenderedSelectionTest {
    private val equations = listOf("x=1", "A'=[\\alpha_1,\\alpha_3,\\alpha_5]",
        "B'=[\\beta_1,\\beta_3,\\beta_5]", "c_1\\alpha_1+c_3\\alpha_3+c_5\\alpha_5=0",
        "\\int_0^1x^2dx=\\frac13", "\\boxed{N_0\\left[1+\\cos\\left(\\frac{\\pi f}{B}\\right)\\right]}")

    private fun render(source: String, streaming: Boolean, size: Float, width: Int = 740): TextView {
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, fontSizePx = size)
        view.layoutParams = android.view.ViewGroup.LayoutParams(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        view.setPadding(19, 11, 23, 13)
        update(view, source, streaming, width)
        measure(view, width)
        return view
    }

    private fun update(view: TextView, source: String, streaming: Boolean, width: Int) {
        // Use the production entry points, without replacing their drawables or
        // injecting an alignment as the older selection tests did.
        if (streaming) renderStreamingMarkdown(view, StreamingMarkdownSnapshot(emptyList(), source,
            renderedPrefixEnd = source.length, visibleEnd = source.length), width, Color.BLACK, Color.BLUE)
        else renderMarkdown(view, source, width, Color.BLACK, Color.BLUE, stableLayout = true)
    }

    private fun measure(view: TextView, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun verifyInk(view: TextView) {
        val text = view.text as Spanned
        val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java)
        assertTrue(spans.isNotEmpty())
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(bitmap))
            spans.forEach { span ->
                assertTrue(span.drawable.hasResult())
                val start = text.getSpanStart(span)
                val end = text.getSpanEnd(span)
                val path = Path().also { markdownSelectionPath(view, start, end, it) }
                val highlight = RectF().also { path.computeBounds(it, true) }
                val line = view.layout.getLineForOffset(start)
                val ink = RectF()
                val pixels = mutableListOf<Pair<Int, Int>>()
                val top = (view.totalPaddingTop + view.layout.getLineTop(line) - view.scrollY).coerceAtLeast(0)
                val bottom = (view.totalPaddingTop + view.layout.getLineBottom(line) - view.scrollY).coerceAtMost(view.height)
                for (y in top until bottom) for (x in 0 until view.width) {
                    val color = bitmap.getPixel(x, y)
                    if (Color.alpha(color) > 128 && Color.red(color) < 32) {
                        if (pixels.isEmpty()) ink.set(x.toFloat(), y.toFloat(), x + 1f, y + 1f)
                        else ink.union(x.toFloat(), y.toFloat(), x + 1f, y + 1f)
                        pixels += x to y
                    }
                }
                assertTrue("Formula must paint real ink", pixels.isNotEmpty())
                if (span.drawable.destination.trim().startsWith("\\boxed")) {
                    // Remove the independently observed rectangle stroke, not
                    // any of the predicted selection boxes, before testing ink.
                    val frame = RectF(ink).apply { inset(view.textSize * .12f, view.textSize * .12f) }
                    pixels.removeAll { (x, y) -> !frame.contains(x.toFloat(), y.toFloat()) }
                    ink.setEmpty()
                    pixels.forEach { (x, y) -> ink.union(x.toFloat(), y.toFloat(), x + 1f, y + 1f) }
                }
                val tolerance = view.textSize * .4f
                assertEquals("Highlight left must follow actual ink: ${span.drawable.destination}", ink.left, highlight.left, tolerance)
                assertEquals("Highlight right must follow actual ink", ink.right, highlight.right, tolerance)
                val selected = Region().apply { setPath(path, Region(0, 0, view.width, view.height)) }
                val outside = pixels.count { (x, y) -> !selected.contains(x, y) }
                // A boxed formula's decorative frame deliberately stays outside
                // its inner copy targets, just like quote rules do for prose.
                if (!span.drawable.destination.trim().startsWith("\\boxed"))
                    assertTrue("Selection must cover formula ink, missed $outside / ${pixels.size}", outside < pixels.size * .025f + 3)
                assertEquals(span.drawable.destination.trim(), selectedMarkdownText(text, start, end))
                val first = requireNotNull(selectionEndpoint(view, start, true))
                val last = requireNotNull(selectionEndpoint(view, end, false))
                assertEquals(highlight.left, first.x, .2f)
                assertEquals(highlight.right, last.x, .2f)
            }
        } finally { bitmap.recycle() }
    }

    @Test fun `real stable and streaming renderers align short formulas with selection and handles`() {
        for (streaming in listOf(false, true)) for (size in listOf(26f, 40f)) {
            val source = equations.joinToString("\n\n") { "\$\$\n$it\n\$\$" }
            verifyInk(render(source, streaming, size))
        }
    }

    @Test fun `partial bracket and frame selections hit and copy their visible mathematical item`() {
        for (streaming in listOf(false, true)) for (source in listOf(equations[1], equations.last())) {
            val view = render("\$\$\n$source\n\$\$", streaming, 32f)
            val text = view.text as Spanned
            val span = text.getSpans(0, text.length, BaselineLatexSpan::class.java).single()
            val expected = if (source.startsWith("A")) "\\alpha_3" else "\\frac{\\pi f}{B}"
            val part = formulaSelectionParts(view, span).single { selectedMarkdownText(text, it.start, it.end) == expected }
            val start = view.getOffsetForPosition(part.bounds.left + 1f, part.bounds.centerY())
            val end = view.getOffsetForPosition(part.bounds.right - 1f, part.bounds.centerY())
            assertEquals(expected, selectedMarkdownText(text, start, end))
            verifyInk(view)
        }
    }

    @Test fun `appending streamed content retains aligned ready formulas after a width change`() {
        val first = "\$\$\n${equations[1]}\n\$\$\n\n"
        val view = render(first, true, 32f)
        val old = (view.text as Spanned).getSpans(0, view.text.length, BaselineLatexSpan::class.java).first().drawable
        val next = first + "\$\$\n${equations[2]}\n\$\$"
        update(view, next, true, 740); measure(view, 740)
        assertSame(old, (view.text as Spanned).getSpans(0, view.text.length, BaselineLatexSpan::class.java)
            .single { it.drawable.destination == old.destination }.drawable)
        verifyInk(view)
        update(view, next, true, 520); measure(view, 520)
        verifyInk(view)
    }
}
