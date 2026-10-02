package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexAsyncDrawableSpan
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.image.AsyncDrawableSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LatexSpanTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val formula = "\\frac{N_0}{2}\\int h_1(u)h_2(u+\\tau)du"

    @Before
    fun setUp() {
        JLatexMathAndroid.init(context)
    }

    private fun newView(): TextView =
        createMarkdownTextView(context, 0xFF000000.toInt(), 0xFF0000FF.toInt()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            textSize = 36f
        }

    private fun render(view: TextView, markdown: String): List<BaselineLatexSpan> {
        (view.tag as Markwon).setMarkdown(view, markdown)
        AsyncDrawableScheduler.unschedule(view)
        val text = view.text as Spanned
        return text.getSpans(0, text.length, BaselineLatexSpan::class.java).toList()
    }

    private fun load(view: TextView, span: BaselineLatexSpan, width: Int = 1100): JLatexMathDrawable {
        val result = JLatexMathDrawable.builder(span.drawable.destination)
            .textSize(view.textSize).build()
        span.drawable.initWithKnownDimensions(width, view.textSize)
        span.drawable.setResult(result)
        view.text = view.text
        return result
    }

    private fun measure(view: TextView, width: Int = 1100) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    @Test
    fun `text setter preserves formula ranges flags and asynchronous drawable`() {
        val view = newView()
        val renderer = view.tag as Markwon
        val markdown = renderer.toMarkdown("中文 \$\$x^2\$\$ 后文")
        val original = markdown.getSpans(0, markdown.length, JLatexAsyncDrawableSpan::class.java).single()
        renderer.setParsedMarkdown(view, markdown)
        AsyncDrawableScheduler.unschedule(view)
        val actual = view.text as Spanned
        val span = actual.getSpans(0, actual.length, BaselineLatexSpan::class.java).single()
        assertSame(original.drawable, span.drawable)
        assertEquals(markdown.toString(), actual.toString())
        assertEquals(markdown.getSpanStart(original), actual.getSpanStart(span))
        assertEquals(markdown.getSpanEnd(original), actual.getSpanEnd(span))
        assertEquals(markdown.getSpanFlags(original), actual.getSpanFlags(span))
        assertTrue(actual.getSpans(0, actual.length, JLatexAsyncDrawableSpan::class.java).isEmpty())
        assertSame(span, actual.getSpans(0, actual.length, AsyncDrawableSpan::class.java).single())
    }

    @Test
    fun `unloaded formula keeps placeholder width and font metrics`() {
        val view = newView()
        val renderer = view.tag as Markwon
        val markdown = renderer.toMarkdown("中文 \$\$x^2\$\$ 后文")
        val original = markdown.getSpans(0, markdown.length, JLatexAsyncDrawableSpan::class.java).single()
        val span = BaselineLatexSpan(renderer.configuration().theme(), original)
        val start = markdown.getSpanStart(original)
        val end = markdown.getSpanEnd(original)
        val expected = view.paint.fontMetricsInt
        val actual = view.paint.fontMetricsInt
        assertEquals(
            original.getSize(view.paint, markdown, start, end, expected),
            span.getSize(view.paint, markdown, start, end, actual),
        )
        assertEquals(expected.ascent, actual.ascent)
        assertEquals(expected.descent, actual.descent)
        assertEquals(expected.top, actual.top)
        assertEquals(expected.bottom, actual.bottom)
    }

    @Test
    fun `formula reserves both ascent and descent without shrinking Chinese text metrics`() {
        val view = newView()
        for (latex in listOf("x", "\\frac{1}{2}", formula, "\\sum_{n=0}^{\\infty} x_n")) {
            val span = render(view, "中文 $$$latex$$ 后文").single()
            load(view, span)
            val metrics = Paint.FontMetricsInt()
            span.getSize(view.paint, view.text, 0, view.text.length, metrics)
            val baseline = span.baselineOffset(view.paint)
            val font = view.paint.fontMetricsInt
            assertTrue("$latex top", metrics.ascent <= -baseline)
            assertTrue("$latex bottom", metrics.descent >= span.drawable.bounds.height() - baseline)
            assertTrue(metrics.top <= metrics.ascent)
            assertTrue(metrics.bottom >= metrics.descent)
            assertTrue(metrics.top <= font.top && metrics.bottom >= font.bottom)
            assertTrue(metrics.ascent <= font.ascent && metrics.descent >= font.descent)
        }
    }

    @Test
    fun `first and last line formulas fit fully inside TextView including block formulas`() {
        val view = newView()
        for (markdown in listOf(
            "代入得 $$\\frac{1}{2}\\cos(2\\pi t)$$ 得证。",
            "中文 $$$formula$$ 后文\n下一行",
            "第一行\n中文 $$$formula$$",
            "$$\n$formula\n$$",
        )) {
            val spans = render(view, markdown)
            assertFalse(markdown, spans.isEmpty())
            spans.forEach { load(view, it) }
            measure(view)
            val text = view.text as Spanned
            for (span in spans) {
                val line = view.layout.getLineForOffset(text.getSpanStart(span))
                val baseline = view.totalPaddingTop + view.layout.getLineBaseline(line)
                val top = baseline - span.baselineOffset(view.paint)
                val bottom = top + span.drawable.bounds.height()
                assertTrue("$markdown clipped at top: $top", top >= 0f)
                assertTrue("$markdown clipped at bottom: $bottom / ${view.height}", bottom <= view.height)
                assertTrue(top >= view.totalPaddingTop + view.layout.getLineTop(line))
                assertTrue(bottom <= view.totalPaddingTop + view.layout.getLineBottom(line))
            }
        }
    }

    @Test
    fun `scaled formula uses scaled TeX baseline rather than its rectangle center`() {
        val view = newView()
        val span = render(view, "中文 $$$formula$$ 后文").single()
        val result = load(view, span, 120)
        val scale = minOf(
            span.drawable.bounds.width().toFloat() / result.intrinsicWidth,
            span.drawable.bounds.height().toFloat() / result.intrinsicHeight,
        )
        assertTrue(scale < 1f)
        val offset = (span.drawable.bounds.height() - (result.intrinsicHeight * scale + 0.5f).toInt()) / 2
        assertEquals(
            offset + (result.intrinsicHeight - result.icon().iconDepth) * scale,
            span.baselineOffset(view.paint),
            0.01f,
        )
    }

    @Test
    fun `drawing follows text baseline regardless of neighboring line height`() {
        val view = newView()
        val span = render(view, "中文 $$$formula$$ 后文").single()
        load(view, span)
        fun pixels(top: Int, bottom: Int): IntArray {
            val bitmap = Bitmap.createBitmap(1100, 300, Bitmap.Config.ARGB_8888)
            span.draw(Canvas(bitmap), view.text, 0, view.text.length, 20f, top, 160, bottom, view.paint)
            return IntArray(bitmap.width * bitmap.height).also {
                bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                bitmap.recycle()
            }
        }
        val compact = pixels(40, 220)
        val expanded = pixels(0, 290)
        assertTrue("Formula must actually draw ink", compact.any { it != 0 })
        assertTrue("Formula baseline moved with neighboring height", compact.contentEquals(expanded))
    }

    @Test
    fun `odd height error drawable also reserves its complete bottom edge`() {
        val view = newView()
        val span = render(view, "中文 \$\$x\$\$ 后文").single()
        span.drawable.initWithKnownDimensions(1100, view.textSize)
        span.drawable.setResult(ColorDrawable(0xFF000000.toInt()).apply { setBounds(0, 0, 80, 151) })
        val metrics = Paint.FontMetricsInt()
        span.getSize(view.paint, view.text, 0, view.text.length, metrics)
        assertTrue(metrics.descent - metrics.ascent >= 151)
        assertTrue(metrics.bottom >= metrics.descent)
    }

    @Test
    fun `late drawable arrival expands measured text height`() {
        val view = newView()
        val span = render(view, "中文 \$\$x\$\$ 后文").single()
        measure(view)
        val placeholderHeight = view.height
        span.drawable.initWithKnownDimensions(1100, view.textSize)
        span.drawable.setResult(ColorDrawable(0xFF000000.toInt()).apply { setBounds(0, 0, 80, 241) })
        view.text = view.text
        measure(view)
        assertTrue(view.height >= 241)
        assertTrue(view.height > placeholderHeight)
        assertSame(span, (view.text as Spanned).getSpans(0, view.text.length, BaselineLatexSpan::class.java).single())
    }
}
