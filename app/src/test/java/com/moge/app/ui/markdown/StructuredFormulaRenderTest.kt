package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.moge.app.core.LatexEngine
import com.moge.app.data.parse.prepareReplyMarkdown
import com.moge.app.data.parse.splitFormula
import com.moge.app.data.parse.wrapLongFormulas
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
class StructuredFormulaRenderTest {
    private val polynomial = "2r\\sin\\theta-r^3\\sin\\theta+4r^2\\cos^2\\theta" +
        "-2r^4\\cos^2\\theta+2r^3\\cos\\theta\\sin^2\\theta"
    private val integrand = "y(2-x^2-y^2)+(2-x^2-y^2)x\\cdot2x+xy\\cdot2y"
    private val set = "\\mathcal D_1=\\left\\{(x,y):y\\ge0,\\ \\sqrt3x+y\\ge3+\\sqrt3," +
        "\\ (1+\\sqrt3)x+(1-\\sqrt3)y\\ge1+\\sqrt3\\right\\}"
    private val formulas get() = listOf(
        "\\left[$polynomial\\right]r\\,dr",
        "\\iint_{D_{xy}}\\left[$integrand\\right]dxdy",
        "\\boxed{$set}.",
        "\\langle Ax,Ay\\rangle=(Ax)^T(Ay)=x^TA^TAy=x^Ty=\\langle x,y\\rangle",
    )

    @Before fun init() { LatexEngine.init(RuntimeEnvironment.getApplication()) }

    @Test fun `reported inner product derivation renders whole brackets across narrow widths`() {
        val first = "\\langle Ax,Ay\\rangle"
        val last = "\\langle x,y\\rangle"
        val source = "$first=(Ax)^T(Ay)=x^TA^TAy=x^Ty=$last"
        for (size in listOf(26f, 36f)) for (width in listOf(260, 400, 520)) {
            val rows = splitFormula(source, width, formulaWidthMeasurer(size))
            assertEquals(source.filterNot(Char::isWhitespace), rows.joinToString("").filterNot(Char::isWhitespace))
            assertTrue(rows.toString(), rows.any { first in it })
            assertTrue(rows.toString(), rows.any { last in it })
            rows.forEach { row ->
                val drawable = JLatexMathDrawable.builder(row).textSize(size).padding(0).build()
                assertTrue("$width / $size: $row", drawable.intrinsicWidth > 0 && drawable.intrinsicWidth <= width)
            }
        }
    }

    @Test fun `reported formulas wrap at normal text size without losing terms or growing past the viewport`() {
        for (size in listOf(26f, 36f)) for (width in listOf(400, 520, 740)) {
            val measure = formulaWidthMeasurer(size)
            for (source in formulas) {
                val wrapped = splitFormula(source, width, measure)
                if (measure(source) > width) {
                    assertTrue("Expected readable rows at $size / $width: $wrapped",
                        wrapped.size > 1 || wrapped.single().contains("\\begin{array}{l}"))
                } else assertEquals(listOf(source), wrapped)
                for (row in wrapped) {
                    val drawable = JLatexMathDrawable.builder(row).textSize(size).padding(0).build()
                    assertTrue("${drawable.intrinsicWidth}px > ${width}px at $size: $row", drawable.intrinsicWidth <= width)
                }
                assertEquals("All terms and signs must survive wrapping", content(source), content(wrapped.joinToString("")))
            }
        }
    }

    @Test fun `plain and sized brackets keep integral and differential outside and frame stays continuous`() {
        val measure = formulaWidthMeasurer(26f)
        for ((open, close) in listOf("[" to "]", "\\bigl[" to "\\bigr]", "\\left[" to "\\right]")) {
            val source = "\\iint_{D_{xy}}$open$integrand${close}dxdy"
            val wrapped = splitFormula(source, 520, measure).single()
            assertTrue(wrapped, wrapped.startsWith("\\iint_{D_{xy}}\\left[\\begin{array}"))
            assertTrue(wrapped, wrapped.endsWith("\\end{array}\\right]dxdy"))
            assertEquals(1, Regex("\\\\iint").findAll(wrapped).count())
        }
        for (frame in listOf("boxed", "fbox")) {
            val wrapped = splitFormula("\\$frame{$set}", 520, measure).single()
            assertTrue(wrapped, wrapped.startsWith("\\$frame{\\begin{array}"))
            assertTrue(wrapped, wrapped.endsWith("\\end{array}}"))
            assertEquals(1, Regex("\\\\$frame\\{").findAll(wrapped).count())
        }
        val fraction = "\\frac{$polynomial}{x+1}"
        assertEquals(listOf(fraction), splitFormula(fraction, 100, measure))
        val short = "\\boxed{x+1}"
        assertEquals(listOf(short), splitFormula(short, 520, measure))
    }

    @Test fun `full markdown pipeline renders multiline formulas in paper and chalk themes`() {
        renderPreview(560, 26f, "structured-formula-wrap.png")
        renderPreview(400, 36f, "structured-formula-narrow-wrap.png")
    }

    private fun renderPreview(width: Int, size: Float, filename: String) {
        val views = listOf(Color.BLACK to Color.rgb(250, 247, 234), Color.WHITE to Color.rgb(36, 51, 45))
            .map { (ink, background) ->
                val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), ink, Color.YELLOW, fontSizePx = size)
                view.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                val markdown = formulas.joinToString("\n\n") { "\$\$\n$it\n\$\$" }
                val prepared = wrapLongFormulas(prepareReplyMarkdown(markdown), formulaMaxWidthPx(view, width), formulaWidthMeasurer(size))
                val expectedBlocks = Regex("\\$\\$[\\s\\S]*?\\$\\$").findAll(prepared).count()
                renderMarkdown(view, markdown, width, ink, Color.YELLOW)
                measure(view, width)
                AsyncDrawableScheduler.unschedule(view)
                val text = view.text as Spanned
                val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java)
                assertTrue("All source formulas must remain present", expectedBlocks >= formulas.size)
                assertEquals(expectedBlocks, spans.size)
                for (span in spans) {
                    val drawable = JLatexMathDrawable.builder(span.drawable.destination).textSize(size).color(ink)
                        .align(JLatexMathDrawable.ALIGN_CENTER).padding(0).build()
                    assertTrue(span.drawable.destination, drawable.intrinsicWidth <= formulaMaxWidthPx(view, width))
                    span.drawable.initWithKnownDimensions(width - view.totalPaddingLeft - view.totalPaddingRight, size)
                    span.drawable.setResult(drawable)
                }
                view.text = text
                measure(view, width)
                view to background
            }
        val height = views.maxOf { it.first.height }
        val preview = Bitmap.createBitmap(width * views.size, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(preview)
            views.forEachIndexed { index, (view, background) ->
                canvas.save()
                canvas.translate(index * width.toFloat(), 0f)
                canvas.clipRect(0, 0, width, height)
                canvas.drawColor(background)
                view.draw(canvas)
                canvas.restore()
            }
            val file = File("build/ui-refinement-previews/$filename")
            file.parentFile!!.mkdirs()
            file.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { preview.recycle() }
    }

    private fun content(latex: String): String = latex
        .replace("\\begin{array}{l}", "").replace("\\end{array}", "").replace("\\\\", "")
        .replace("\\left.", "").replace("\\right.", "")
        .replace("\\left", "").replace("\\right", "")
        .replace("\\ ", "").filterNot { it.isWhitespace() }

    private fun measure(view: TextView, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }
}
