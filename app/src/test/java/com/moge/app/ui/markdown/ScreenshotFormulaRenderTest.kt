package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Spanned
import android.view.View
import android.widget.TextView
import com.moge.app.ui.solve.userMathSource
import io.noties.markwon.image.AsyncDrawableScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.scilab.forge.jlatexmath.ParseException
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotFormulaRenderTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val bare = """=\arcsin\Bigl(2\sqrt{(1-x)-(1-x)^{2}}\Bigr)"""
    private val probability = """P_{0|1}=P(\text{判0}\mid\text{发1})"""

    @Before
    fun setUp() { JLatexMathAndroid.init(context) }

    private fun newView() = createMarkdownTextView(context, Color.BLACK, Color.BLUE, fontSizePx = 22f)

    private fun spans(view: TextView): List<BaselineLatexSpan> {
        AsyncDrawableScheduler.unschedule(view)
        val text = view.text as Spanned
        assertFalse(text.toString(), text.contains('$'))
        assertFalse(text.toString(), text.contains("[部分内容无法渲染"))
        return text.getSpans(0, text.length, BaselineLatexSpan::class.java).toList()
            .sortedBy { text.getSpanStart(it) }
    }

    /** Build the exact Markwon destination, including block LF, without trimming or error fallback. */
    private fun assertSpanDraws(view: TextView, span: BaselineLatexSpan, width: Int) {
        val native = JLatexMathDrawable.builder(span.drawable.destination).textSize(view.textSize).build()
        assertTrue(native.intrinsicWidth > 0 && native.intrinsicHeight > 0)
        span.drawable.initWithKnownDimensions(width, view.textSize)
        span.drawable.setResult(native)
        view.text = view.text
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)

        val text = view.text as Spanned
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        assertTrue(start >= 0 && end > start)
        val metrics = Paint.FontMetricsInt()
        assertEquals(span.drawable.bounds.width(), span.getSize(view.paint, text, start, end, metrics))
        assertTrue(metrics.descent - metrics.ascent >= span.drawable.bounds.height())
        val line = view.layout.getLineForOffset(start)
        val baseline = view.totalPaddingTop + view.layout.getLineBaseline(line)
        val top = baseline - span.baselineOffset(view.paint)
        assertTrue("Formula top clipped: $top", top >= 0f)
        assertTrue("Formula bottom clipped", top + span.drawable.bounds.height() <= view.height)

        val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
        try {
            // Draw the actual replacement span alone, so surrounding prose cannot satisfy the ink assertion.
            span.draw(Canvas(bitmap), text, start, end,
                view.totalPaddingLeft + view.layout.getPrimaryHorizontal(start),
                view.layout.getLineTop(line), baseline, view.layout.getLineBottom(line), view.paint)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            assertTrue("Formula span must draw visible ink", pixels.any { it ushr 24 != 0 })
        } finally { bitmap.recycle() }
    }

    @Test
    fun `exact bare arcsin source renders as a native span at phone and tablet widths`() {
        assertEquals(listOf(bare), userMathSource(bare)!!.formulas)
        for (width in listOf(320, 900)) {
            val view = newView()
            renderMarkdown(view, bare, width, Color.BLACK, Color.BLUE)
            val span = spans(view).single()
            // The block parser preserves boundary newlines. Exercise the exact destination.
            assertEquals("\n$bare\n", span.drawable.destination)
            assertSpanDraws(view, span, width)
        }
    }

    @Test
    fun `exact inline probability renders with both Chinese labels and conditional notation`() {
        val source = "误判概率为 ${'$'}$probability${'$'}。"
        assertEquals(listOf(probability), userMathSource(source)!!.formulas)
        for (width in listOf(320, 900)) {
            val view = newView()
            renderMarkdown(view, source, width, Color.BLACK, Color.BLUE)
            val span = spans(view).single()
            assertEquals(probability, span.drawable.destination)
            assertTrue(view.text.startsWith("误判概率为 "))
            assertTrue(view.text.endsWith("。"))
            assertSpanDraws(view, span, width)
        }
    }

    @Test
    fun `closed streaming probability prefix uses the same native formula span`() {
        val source = "误判概率为 ${'$'}$probability${'$'}。继续讲解"
        val view = newView()
        val snapshot = StreamingMarkdownTokenizer().update(source)
        renderStreamingMarkdown(view, snapshot, 900, Color.BLACK, Color.BLUE)
        val span = spans(view).single()
        assertEquals(probability, span.drawable.destination)
        assertTrue(view.text.endsWith("。继续讲解"))
        assertSpanDraws(view, span, 900)
    }

    @Test
    fun `failed formula fallback lays out complete TeX including Chinese and the final line`() {
        val latex = "\\unknownMogeCommand{1}+" + "x_{1}+".repeat(30) + probability + "\n判0发1"
        val error = runCatching { JLatexMathDrawable.builder(latex).textSize(22f).build() }.exceptionOrNull()
        assertTrue("Exercise a real TeX parse failure", error is ParseException)
        val fallback = LatexFallbackDrawable(Color.BLACK, 22f, latex, error!!.javaClass.simpleName, maxWidthPx = 280)
        assertEquals(latex, fallback.rawLatex)
        assertEquals(latex, fallback.sourceLayout.text.toString())
        assertTrue(fallback.intrinsicWidth <= 280)
        val layout = fallback.sourceLayout
        assertTrue("Long source must wrap", layout.lineCount > 2)
        assertEquals(latex.length, layout.getLineEnd(layout.lineCount - 1))

        // Draw the actual fallback, cropped to its final CJK line. The title is outside the crop.
        val finalLine = layout.lineCount - 1
        fallback.setBounds(0, 0, fallback.intrinsicWidth, fallback.intrinsicHeight)
        val sourceTop = fallback.intrinsicHeight - layout.height - 10
        val bitmap = Bitmap.createBitmap(fallback.intrinsicWidth, layout.getLineBottom(finalLine) - layout.getLineTop(finalLine),
            Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.translate(0f, -(sourceTop + layout.getLineTop(finalLine)).toFloat())
            fallback.draw(canvas)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            // The gray background has alpha 0x1F; only source ink can exceed it here.
            assertTrue("Complete CJK source must reach Android drawing", pixels.any { it ushr 24 > 0x1F })
        } finally { bitmap.recycle() }
    }

    @Test
    fun `failure diagnostics keep complete original input and attempted TeX beyond old limits`() {
        val original = "${'$'}\\cancel{x}+\\frac12+$probability${'$'}\n" + "原始推导".repeat(500) + "原文末尾"
        val attempted = "\\unknownMogeCommand{1}+" + "x+".repeat(900) + probability
        val entry = formatRenderErrorEntry("LATEX-PIECE:\n$attempted\n---", IllegalArgumentException("parse failed"), original)
        assertTrue(entry.contains("ORIGINAL-MARKDOWN:\n$original\n"))
        assertTrue(entry.contains("LATEX-PIECE:\n$attempted\n---\n"))
        assertTrue(entry.contains("原文末尾"))
        assertFalse("Source must not be replaced by an abbreviated snippet", entry.contains('…'))
    }
}
