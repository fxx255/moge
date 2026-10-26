package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Region
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.view.View
import android.widget.TextView
import io.noties.markwon.image.AsyncDrawableScheduler
import org.junit.Assert.*
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
class SelectionHighlightGeometryTest {
    private val equation = "N_0\\left[1+\\cos\\left(\\frac{\\pi f}{B}\\right)\\right]"

    private fun render(source: String, size: Float = 32f, chalk: Boolean = false): TextView {
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(),
            if (chalk) Color.WHITE else Color.BLACK, if (chalk) Color.YELLOW else Color.BLUE, fontSizePx = size)
        view.layoutParams = android.view.ViewGroup.LayoutParams(1000, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        renderMarkdown(view, source, 1000, view.currentTextColor, view.linkTextColors.defaultColor)
        fun measure() {
            view.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        measure()
        AsyncDrawableScheduler.unschedule(view)
        for (span in spans(view)) {
            span.drawable.initWithKnownDimensions(1000 - view.totalPaddingLeft - view.totalPaddingRight, size)
            span.drawable.setResult(JLatexMathDrawable.builder(span.drawable.destination).textSize(size)
                .color(view.currentTextColor).align(JLatexMathDrawable.ALIGN_CENTER).padding(0).build())
        }
        view.text = view.text
        measure()
        return view
    }

    private fun spans(view: TextView) = (view.text as Spanned)
        .getSpans(0, view.text.length, BaselineLatexSpan::class.java).sortedBy { (view.text as Spanned).getSpanStart(it) }

    private fun painted(view: TextView, from: Int, to: Int): Region = Region().apply {
        val path = Path()
        markdownSelectionPath(view, from, to, path)
        setPath(path, Region(0, 0, view.width, view.height))
    }

    @Test fun `multiline quote selections leave their decoration and empty rows unpainted`() {
        for (size in listOf(32f, 44f)) for (chalk in listOf(false, true)) {
            val view = render("> In a word, we should be more confident and optimistic, and worry less when facing challenges. " +
                "Only by cultivating self-confidence and taking practical action can we achieve our goals.", size, chalk)
            val region = painted(view, 0, view.text.length)
            val layout = view.layout
            assertTrue(layout.lineCount > 2)
            for (line in 0 until layout.lineCount) {
                val textX = view.totalPaddingLeft + layout.getPrimaryHorizontal(layout.getLineStart(line))
                val y = (view.totalPaddingTop + layout.getLineBaseline(line) - size / 3).toInt()
                assertFalse("Quote margin is not text: row $line, x=$textX", region.contains((textX - 6).toInt(), y))
                assertTrue("Selected characters still have a highlight", region.contains((textX + 4).toInt(), y))
            }
            snapshot(view, "quote-${size.toInt()}-$chalk", chalk)
        }
    }

    @Test fun `continuous formula selections use connected row highlights without separator bars`() {
        val equations = listOf("P_n(f)=\\frac{N_0}{2}|H(f)|^2", "=2N_0\\cos^2\\left(\\frac{\\pi f}{2B}\\right)", "=$equation")
        for (size in listOf(32f, 44f)) for (chalk in listOf(false, true)) {
            val view = render(equations.joinToString("\n\n") { "\$\$\n$it\n\$\$" }, size, chalk)
            val region = painted(view, 0, view.text.length)
            val formulas = spans(view)
            assertEquals(3, formulas.size)
            val rows = formulas.map { span -> formulaSelectionParts(view, span).also { assertTrue(it.isNotEmpty()) } }
            for (parts in rows) {
                val left = parts.minOf { it.bounds.left }.toInt()
                val right = parts.maxOf { it.bounds.right }.toInt()
                val top = parts.minOf { it.bounds.top }.toInt() + 2
                val bottom = parts.maxOf { it.bounds.bottom }.toInt() - 2
                for (x in left + 2 until right - 2) {
                    assertTrue("No gaps above small operators", region.contains(x, top))
                    assertTrue("No gaps below small operators", region.contains(x, bottom))
                }
                assertFalse("No full-page margin highlight", region.contains(20, (top + bottom) / 2))
            }
            for ((before, after) in rows.zipWithNext()) {
                val y = ((before.maxOf { it.bounds.bottom } + after.minOf { it.bounds.top }) / 2).toInt()
                assertFalse("Formula separators must not paint empty space", region.contains(view.width / 2, y))
            }
            snapshot(view, "formula-rows-${size.toInt()}-$chalk", chalk)
        }
    }

    @Test fun `nested bracket content can be hit selected and copied without its enclosing fences`() {
        val view = render("\$\$\n$equation\n\$\$")
        val span = spans(view).single()
        val text = view.text as Spannable
        val inner = formulaSelectionParts(view, span).single { selectedMarkdownText(text, it.start, it.end) == "\\frac{\\pi f}{B}" }
        Selection.setSelection(text, inner.start, inner.end)
        assertEquals("\\frac{\\pi f}{B}", selectedMarkdownText(text, view.selectionStart, view.selectionEnd))
        assertEquals(inner.start, view.getOffsetForPosition(inner.bounds.left + 1, inner.bounds.centerY()))
        assertEquals(inner.end, view.getOffsetForPosition(inner.bounds.right - 1, inner.bounds.centerY()))
        assertTrue("The selected fraction is only part of the equation", inner.bounds.width() < formulaSelectionParts(view, span)
            .let { parts -> parts.maxOf { it.bounds.right } - parts.minOf { it.bounds.left } } / 2)
        snapshot(view, "nested-fraction", false, selectAll = false)
    }

    @Test fun `invisible scalable delimiter does not restore a full width replacement span highlight`() {
        val view = render("\$\$\\left.x+y\\right]\$\$")
        val span = spans(view).single()
        val text = view.text as Spanned
        val hidden = formulaTokenOffsets(text, span).first().second
        assertTrue(painted(view, hidden.start, hidden.end).isEmpty)
        val firstInk = formulaSelectionParts(view, span).first()
        assertEquals(firstInk.bounds.left, requireNotNull(selectionEndpoint(view, text.getSpanStart(span), true)).x, .1f)
    }

    @Test fun `prose and inline formulas share one unbroken selection band on each reading line`() {
        for (size in listOf(32f, 44f)) for (chalk in listOf(false, true)) {
            val view = render("由第二个分量得 ${'$'}-k_2=0${'$'}，再由第一个分量得 ${'$'}k_1+k_2=0${'$'}，因此 ${'$'}k_1=k_2=0${'$'}。", size, chalk)
            val region = painted(view, 0, view.text.length)
            for (line in 0 until view.layout.lineCount) {
                val lineSpans = spans(view).filter { view.layout.getLineForOffset((view.text as Spanned).getSpanStart(it)) == line }
                if (lineSpans.isEmpty()) continue
                val top = view.totalPaddingTop + view.layout.getLineTop(line) + 2
                val bottom = view.totalPaddingTop + view.layout.getLineBottom(line) - 2
                val leadingFormula = lineSpans.firstOrNull {
                    (view.text as Spanned).getSpanStart(it) == view.layout.getLineStart(line)
                }
                // A wrapped line may begin with a centered formula placeholder.
                // Its empty leading gutter is deliberately outside the selection.
                val start = leadingFormula?.let { formulaSelectionParts(view, it).minOf { part -> part.bounds.left } }
                    ?: (view.totalPaddingLeft + view.layout.getPrimaryHorizontal(view.layout.getLineStart(line)))
                val end = view.totalPaddingLeft + view.layout.getPrimaryHorizontal(view.layout.getLineVisibleEnd(line))
                for (x in start.toInt() + 2 until end.toInt() - 2) {
                    assertTrue("Mixed row must have no gaps above math ($size, $line, $x)", region.contains(x, top))
                    assertTrue("Mixed row must have no gaps below math ($size, $line, $x)", region.contains(x, bottom))
                }
            }
            snapshot(view, "mixed-text-formulas-${size.toInt()}-$chalk", chalk)
        }
    }

    @Test fun `framed matrix equation exposes its variable separately and retains the real frame layout`() {
        val source = "\\boxed{A^{10}=\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}}"
        val view = render("${'$'}${'$'}$source${'$'}${'$'}")
        val span = spans(view).single()
        val text = view.text as Spannable
        val part = formulaSelectionParts(view, span).single { selectedMarkdownText(text, it.start, it.end) == "A^{10}" }
        assertTrue("The frame must not create empty copy targets", formulaSelectionParts(view, span).all {
            selectedMarkdownText(text, it.start, it.end).isNotBlank()
        })
        Selection.setSelection(text, part.start, part.end)
        assertEquals("A^{10}", selectedMarkdownText(text, view.selectionStart, view.selectionEnd))
        assertEquals(part.start, view.getOffsetForPosition(part.bounds.left + 1, part.bounds.centerY()))
        assertEquals(part.end, view.getOffsetForPosition(part.bounds.right - 1, part.bounds.centerY()))
        assertEquals(source, selectedMarkdownText(text, text.getSpanStart(span), text.getSpanEnd(span)))
        val original = JLatexMathDrawable.builder(source).textSize(view.textSize).padding(0).build()
        val measured = requireNotNull(com.moge.app.core.measureLatexSelection(source, view.textSize))
        assertEquals(original.intrinsicWidth, measured.width)
        assertEquals(original.intrinsicHeight, measured.height)
        snapshot(view, "framed-matrix-partial", false, selectAll = false)
    }

    private fun snapshot(view: TextView, name: String, chalk: Boolean, selectAll: Boolean = true) {
        if (selectAll) Selection.setSelection(view.text as Spannable, 0, view.text.length)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(if (chalk) Color.rgb(36, 51, 45) else Color.WHITE)
            view.draw(canvas)
            val file = File("build/selection-refinement/$name.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
