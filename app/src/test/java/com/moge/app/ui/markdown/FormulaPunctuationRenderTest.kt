package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import com.moge.app.core.LatexEngine
import com.moge.app.data.parse.splitFormula
import io.noties.markwon.image.AsyncDrawableScheduler
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormulaPunctuationRenderTest {
    private val leadingSeparator = Regex("""^(?:\s|\\(?:quad|qquad)\b|\\[,;:! ])*[,;，；]""")

    @Before fun prepare() { LatexEngine.init(RuntimeEnvironment.getApplication()) }

    @Test fun `reported formulas keep separators on the previous row at normal and larger sizes`() {
        val formulas = listOf(
            "l=a+n_l,\\qquad n_l=\\frac{2}{T_s}\\int_0^{T_s} n_w(t)\\cos(2\\pi f_ct)dt,",
            "E[l\\mid a=-1]\\approx-1,\\qquad \\operatorname{Var}(l)=\\frac{N_0}{T_s}.",
        )
        // The integral product itself cannot split at a safe relation/sign.
        // Check row widths where that product fits; the full pipeline below
        // also covers the existing drawable fitting at 400px / 36px text.
        for ((width, size) in listOf(400 to 26f, 560 to 26f, 560 to 36f)) {
            val measure = formulaWidthMeasurer(size)
            for (source in formulas) {
                val rows = splitFormula(source, width, measure)
                assertEquals(content(source), content(rows.joinToString("")))
                assertTrue(rows.toString(), rows.none(leadingSeparator::containsMatchIn))
                assertTrue(rows.toString(), rows.none { it.trim().all { c -> c in ",;，；" } })
                rows.forEach { row ->
                    val drawable = JLatexMathDrawable.builder(row).textSize(size).padding(0).build()
                    assertTrue("$width / $size: $row", drawable.intrinsicWidth in 1..width)
                }
            }
        }
    }

    @Test fun `full answer renders punctuation inside formulas in both themes and remains copyable`() {
        val source = javaClass.getResource("/fixtures/formula-punctuation.md")!!.readText().trimEnd()
        val themes = listOf(Color.WHITE to Color.rgb(36, 51, 45), Color.BLACK to Color.rgb(250, 247, 234))
        for ((width, size) in listOf(560 to 26f, 400 to 36f)) for ((ink, background) in themes) {
            val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), ink, Color.YELLOW, fontSizePx = size)
            view.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            try {
                renderMarkdown(view, source, width, ink, Color.YELLOW)
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, width, view.measuredHeight)
                AsyncDrawableScheduler.unschedule(view)
                val text = view.text as Spanned
                val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java).sortedBy(text::getSpanStart)
                assertTrue(spans.size > 4)
                assertFalse(text.toString(), text.contains('$'))
                for (span in spans) {
                    val latex = span.drawable.destination.trim()
                    assertFalse(latex, leadingSeparator.containsMatchIn(latex))
                    val drawable = JLatexMathDrawable.builder(latex).textSize(size).color(ink).padding(0).build()
                    assertTrue(latex, drawable.intrinsicWidth > 0)
                    assertEquals(latex, selectedMarkdownText(text, text.getSpanStart(span), text.getSpanEnd(span)))
                    span.drawable.initWithKnownDimensions(formulaMaxWidthPx(view, width), size)
                    span.drawable.setResult(drawable)
                    assertTrue(latex, span.drawable.bounds.width() in 1..formulaMaxWidthPx(view, width))
                }
                val withoutMath = StringBuilder(text)
                spans.asReversed().forEach { withoutMath.delete(text.getSpanStart(it), text.getSpanEnd(it)) }
                assertFalse("No standalone comma prose", withoutMath.lineSequence().any { it.trim() in listOf(",", ";", "，", "；") })
                view.text = text
                view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                view.layout(0, 0, width, view.measuredHeight)
                val image = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(image)
                    canvas.drawColor(background)
                    view.draw(canvas)
                    val suffix = if (ink == Color.WHITE) "chalk" else "paper"
                    val file = File("build/ui-refinement-previews/formula-punctuation-$width-$suffix.png")
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { image.recycle() }
            } finally { AsyncDrawableScheduler.unschedule(view) }
        }
    }

    @Test fun `punctuation outside an expanded formula renders with its final row`() {
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.WHITE, Color.YELLOW, fontSizePx = 26f)
        val formula = "a+b+c+d+e+f+g+h+i+j+k+l+m+n+o+p"
        try {
            renderMarkdown(view, "判决量为 \$\$$formula\$\$，因此成立。", 260, Color.WHITE, Color.YELLOW)
            val text = view.text as Spanned
            val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java).sortedBy(text::getSpanStart)
            assertTrue(spans.size > 1)
            val last = spans.last()
            assertTrue(last.drawable.destination, last.drawable.destination.trimEnd().endsWith('，'))
            JLatexMathDrawable.builder(last.drawable.destination).textSize(26f).padding(0).build()
            assertEquals("因此成立。", text.subSequence(text.getSpanEnd(last), text.length).trim().toString())
        } finally { AsyncDrawableScheduler.unschedule(view) }
    }

    private fun content(latex: String): String = latex
        .replace("\\begin{array}{l}", "").replace("\\end{array}", "").replace("\\\\", "")
        .replace("\\left.", "").replace("\\right.", "")
        .replace("\\left", "").replace("\\right", "").filterNot(Char::isWhitespace)
}
