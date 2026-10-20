package com.moge.app.ui.markdown

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.view.View
import android.widget.TextView
import com.moge.app.core.LatexEngine
import com.moge.app.core.measureLatexSelection
import com.moge.app.data.parse.splitFormula
import io.noties.markwon.image.AsyncDrawableScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FormulaSelectionGeometryTest {
    private val formula = "\\lim_{\\sigma\\to\\infty}p_5=\\frac{75^\\circ}{360^\\circ}=\\frac5{24},"
    private val set = "\\mathcal D_1\n\n=\\left\\{(x,y):y\\ge0,\\ \\sqrt3x+y\\ge3+\\sqrt3," +
        "\\ (1+\\sqrt3)x+(1-\\sqrt3)y\\ge1+\\sqrt3\\right\\}."

    private fun measure(view: TextView, width: Int = 740) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun load(view: TextView, width: Int = 740) {
        AsyncDrawableScheduler.unschedule(view)
        val text = view.text as Spanned
        for (span in text.getSpans(0, text.length, BaselineLatexSpan::class.java)) {
            span.drawable.initWithKnownDimensions(width - view.totalPaddingLeft - view.totalPaddingRight, view.textSize)
            span.drawable.setResult(JLatexMathDrawable.builder(span.drawable.destination).textSize(view.textSize)
                .color(view.currentTextColor).align(JLatexMathDrawable.ALIGN_CENTER).padding(0).build())
        }
        view.text = text
        measure(view, width)
    }

    @Test fun `blank row between formulas places handles on the two highlight edges`() {
        for (size in listOf(26f, 40f)) {
            val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, fontSizePx = size)
            view.layoutParams = android.view.ViewGroup.LayoutParams(740, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            renderMarkdown(view, "\$\$\nb^2=4\n\$\$\n\n\$\$\n,b=2.\n\$\$", 740, Color.BLACK, Color.BLUE)
            measure(view); load(view)
            val from = view.text.indexOf("\n\n") + 1
            assertTrue("The rendered formulas must have a blank row between them", from > 0)
            val to = from + 1
            val path = android.graphics.Path()
            markdownSelectionPath(view, from, to, path)
            val highlight = RectF().also { path.computeBounds(it, true) }
            val start = requireNotNull(selectionEndpoint(view, from, true))
            val end = requireNotNull(selectionEndpoint(view, to, false))
            assertEquals(highlight.left, start.x, .1f)
            assertEquals(highlight.right, end.x, .1f)
            assertTrue(end.x - start.x > view.width * .8f)
            assertEquals(start.centerY, end.centerY, .1f)
            assertEquals("\n", selectedMarkdownText(view.text, from, to))
        }
    }

    @Test fun `zero width separator after a display formula cannot leave touching handles`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        val host = SelectionScrollHost(scope, 1f) { _, _ -> }
        try {
            val view = createMarkdownTextView(activity.get(), Color.BLACK, Color.BLUE, fontSizePx = 26f,
                selectionScrollHost = host)
            activity.get().setContentView(view)
            renderMarkdown(view, "\$\$\n=\\sum_{n=0}^{\\infty}(-1)^n\\frac{(2n)!}{4^n(n!)^2}u^n\n\$\$\n\n这一步", 740, Color.BLACK, Color.BLUE)
            measure(view); load(view)
            host.viewport = RectF(0f, 0f, 740f, 1000f)
            val text = view.text as Spanned
            val formula = text.getSpans(0, text.length, BaselineLatexSpan::class.java).single()
            val from = text.getSpanEnd(formula)
            assertEquals('\n', text[from])
            val path = android.graphics.Path()
            markdownSelectionPath(view, from, from + 1, path)
            val highlight = RectF().also { path.computeBounds(it, true) }
            val painted = android.graphics.Region().apply {
                setPath(path, android.graphics.Region(-1000, -1000, 2000, 2000))
            }
            assertTrue("The separator must reproduce the invisible selection: $highlight, $painted", painted.isEmpty)
            val selection = requireNotNull(markdownSelectionController(view))
            selection.begin(from, from + 1)
            assertFalse("An invisible separator must not open handles or a floating menu", selection.selecting)
            assertFalse(view.hasSelection())

            selection.begin(text.getSpanStart(formula), from + 1)
            val position = IntArray(2).also(view::getLocationOnScreen)
            val initial = requireNotNull(selectionEndpoint(view, view.selectionStart, true))
            selection.beginDrag(true, position[0] + initial.x, position[1] + initial.centerY)
            val separator = requireNotNull(selectionEndpoint(view, from, true))
            selection.moveDrag(position[0] + separator.x, position[1] + separator.centerY)
            assertEquals(from, view.selectionStart)
            assertEquals(from + 1, view.selectionEnd)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertTrue("Dragging across a separator must retain the gesture", selection.dragging)
            selection.endDrag()
            assertFalse("Releasing over an invisible range must dismiss its handles", selection.selecting)
            assertFalse(view.hasSelection())

            selection.begin(text.getSpanStart(formula), from)
            Selection.setSelection(view.text as Spannable, from, from + 1)
            view.viewTreeObserver.dispatchOnPreDraw()
            assertFalse("A range that becomes invisible must not leave stale popups", selection.selecting)
            assertFalse(view.hasSelection())
        } finally { host.dispose(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); activity.pause().stop().destroy() }
    }

    @Test fun `escaped step break renders before a wrapped equation without literal escapes`() {
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, fontSizePx = 26f)
        view.layoutParams = android.view.ViewGroup.LayoutParams(320, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        val formula = "E_s=\\frac{4b^2+4\\cdot 4b^2}{8}=\\frac{5}{2}b^2=10\\Rightarrow b^2\n\n=4"
        renderMarkdown(view, "**分步解答**：\\n(1)\n\n\$\$\n$formula\n\$\$", 320, Color.BLACK, Color.BLUE)
        measure(view, 320); load(view, 320)
        val text = view.text as Spanned
        assertFalse(text.contains("\\n(1)"))
        val headingEnd = text.indexOf('：') + 1
        val step = text.indexOf("(1)")
        assertTrue(text.subSequence(headingEnd, step).contains('\n'))
        assertTrue(view.layout.getLineForOffset(step) > view.layout.getLineForOffset(headingEnd - 1))
        val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java)
        assertTrue("The equation must retain readable separately rendered rows", spans.size > 1)
        assertTrue(spans.all { it.drawable.hasResult() })
        assertTrue(spans.any { it.drawable.destination.contains("=4") })
    }

    @Test fun `selection probe preserves real formula dimensions and measures individual math items`() {
        LatexEngine.init(RuntimeEnvironment.getApplication())
        for (source in listOf(formula, "a+b=c-\\sqrt{x^2+1}", "\\mathcal D_1=x_1+x_2")) {
            val original = JLatexMathDrawable.builder(source).textSize(32f).padding(0).build()
            val probe = requireNotNull(measureLatexSelection(source, 32f))
            assertEquals(source, original.intrinsicWidth, probe.width)
            assertEquals(source, original.intrinsicHeight, probe.height)
            assertTrue(source, probe.items.size >= 5)
            assertTrue(probe.items.toString(), probe.items.all { it.bounds.width() > 0 && it.bounds.height() > 0 })
        }
    }

    @Test fun `actual set conditions fit readable rows without unmatched delimiters`() {
        LatexEngine.init(RuntimeEnvironment.getApplication())
        val width = formulaWidthMeasurer(26f)
        val rows = splitFormula(set, 520, width)
        assertTrue(rows.toString(), rows.size > 1)
        assertTrue(rows.toString(), rows.all { width(it) <= 520 })
        assertTrue(rows.first(), rows.first().contains("D_1") && rows.first().contains("y\\ge0"))
        rows.forEach { JLatexMathDrawable.builder(it).textSize(26f).build() }
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.WHITE, Color.YELLOW, fontSizePx = 26f)
        view.layoutParams = android.view.ViewGroup.LayoutParams(560, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        renderMarkdown(view, "$$\n$set\n$$", 560, Color.WHITE, Color.YELLOW)
        measure(view, 560); load(view, 560)
        val text = view.text as Spanned
        val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java).sortedBy { text.getSpanStart(it) }
        assertTrue("Full normalization and Markwon rendering must retain multiple readable rows", spans.size > 1)
        val preview = Bitmap.createBitmap(560, view.height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(preview)
            canvas.drawColor(Color.rgb(36, 51, 45))
            view.draw(canvas)
            val file = File("build/ui-refinement-previews/formula-set-wrap.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { preview.recycle() }
        assertTrue(spans.joinToString("\n") { it.drawable.destination },
            Regex("y\\s*\\\\geq?\\s*0").containsMatchIn(spans.first().drawable.destination))
    }

    @Test fun `partial formula selection anchors to ink and lens shows math plus highlight`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        val host = SelectionScrollHost(scope, 1f) { _, _ -> }
        val accent = Color.rgb(242, 214, 117)
        val surface = Color.rgb(36, 51, 45)
        val view = createMarkdownTextView(activity.get(), Color.WHITE, accent, fontSizePx = 32f,
            selectionScrollHost = host, selectionSurfaceColor = surface)
        host.viewport = RectF(0f, 0f, 740f, 520f)
        val magnifier = SelectionMagnifier(view, accent, surface)
        try {
            view.setBackgroundColor(surface)
            activity.get().setContentView(view)
            renderMarkdown(view, "$$\n$formula\n$$", 740, Color.WHITE, accent)
            measure(view); load(view)
            val text = view.text as Spanned
            val span = text.getSpans(0, text.length, BaselineLatexSpan::class.java).single()
            val parts = formulaSelectionParts(view, span)
            assertTrue(parts.toString(), parts.size >= 7)
            val fraction = parts[3]
            val selection = requireNotNull(markdownSelectionController(view))
            selection.begin(fraction.start, fraction.end)
            assertEquals("\\frac{75^\\circ}{360^\\circ}", selectedMarkdownText(view.text, view.selectionStart, view.selectionEnd))
            val a = requireNotNull(selectionEndpoint(view, fraction.start, true))
            val b = requireNotNull(selectionEndpoint(view, fraction.end, false))
            assertEquals(fraction.bounds.left, a.x, .1f)
            assertEquals(fraction.bounds.right, b.x, .1f)
            assertTrue("Centered formula handle must be inside its visible ink, not at the page edge", a.x > 30)
            assertEquals(fraction.start, view.getOffsetForPosition(fraction.bounds.left + 1, a.centerY))
            assertEquals(fraction.end, view.getOffsetForPosition(fraction.bounds.right - 1, b.centerY))
            val inkSample = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(inkSample))
                val inkRows = (0 until inkSample.height).filter { y ->
                    (fraction.bounds.left.toInt() until fraction.bounds.right.toInt()).any { x ->
                        inkSample.getPixel(x, y) == Color.WHITE
                    }
                }
                assertTrue("Measured math boxes must overlap the actual drawn glyphs", inkRows.isNotEmpty())
                assertTrue("Highlight must cover the visible numerator: ${fraction.bounds} / ${inkRows.first()}..${inkRows.last()}", fraction.bounds.top <= inkRows.first() + 2)
                assertTrue("Highlight must cover the visible denominator: ${fraction.bounds} / ${inkRows.first()}..${inkRows.last()}", fraction.bounds.bottom >= inkRows.last() - 2)
                assertTrue("Vertical anchor must stay near the actual glyphs", kotlin.math.abs(a.centerY - (inkRows.first() + inkRows.last()) / 2f) < 8)
            } finally { inkSample.recycle() }
            selection.begin(text.getSpanStart(span), text.getSpanEnd(span))
            val last = requireNotNull(selectionEndpoint(view, text.getSpanEnd(span), false))
            selection.beginDrag(false, last.x, last.bottom)
            selection.moveDrag(b.x, b.centerY + (last.bottom - last.centerY))
            assertEquals("Dragging must stop inside the formula instead of snapping to the whole span", fraction.end, view.selectionEnd)
            selection.endDrag()
            selection.begin(fraction.start, fraction.end)
            magnifier.show(28f, 300f, host.viewport, fraction.end, false)
            assertEquals(b.x, magnifier.sourcePoint.first, .1f)
            val equationRow = requireNotNull(formulaSelectionEndpoint(view, fraction.end, false, wholeRow = true))
            assertEquals(equationRow.centerY, magnifier.sourcePoint.second, .1f)
            val popup = requireNotNull(shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow)
            val lens = popup.contentView
            lens.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(popup.height, View.MeasureSpec.EXACTLY))
            lens.layout(0, 0, popup.width, popup.height)
            val image = Bitmap.createBitmap(popup.width, popup.height, Bitmap.Config.ARGB_8888)
            val unselected = Bitmap.createBitmap(popup.width, popup.height, Bitmap.Config.ARGB_8888)
            try {
                lens.draw(Canvas(image))
                Selection.removeSelection(view.text as Spannable)
                lens.draw(Canvas(unselected))
                var ink = 0
                var highlight = 0
                for (y in 6 until image.height - 6) for (x in 6 until image.width - 6) {
                    if (image.getPixel(x, y) == Color.WHITE) ink++
                    if (image.getPixel(x, y) != unselected.getPixel(x, y)) highlight++
                }
                assertTrue("Magnified boundary must contain actual formula ink", ink > 15)
                assertTrue("Magnifier must contain selected and unselected areas", highlight > 30 && highlight < image.width * image.height * .8f)
                Selection.setSelection(view.text as Spannable, fraction.start, fraction.end)
                magnifier.show(700f, 300f, host.viewport, parts[2].end, false)
                assertEquals("A short equals sign must not crop neighboring numerator and denominator",
                    equationRow.centerY, magnifier.sourcePoint.second, .1f)
                magnifier.show(28f, 300f, host.viewport, fraction.end, false)
                val preview = Bitmap.createBitmap(740, maxOf(300, view.height + 100), Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(preview)
                    canvas.drawColor(surface)
                    canvas.save(); canvas.translate(0f, 100f); view.draw(canvas); canvas.restore()
                    canvas.drawBitmap(image, b.x - image.width / 2f, 0f, null)
                    val file = File("build/ui-refinement-previews/formula-selection.png")
                    file.parentFile!!.mkdirs()
                    file.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { preview.recycle() }
            } finally { image.recycle(); unselected.recycle() }
        } finally { magnifier.dismiss(); host.dispose(); activity.pause().stop().destroy() }
    }

    @Test fun `prose between inline formulas retains native character hit testing`() {
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE, fontSizePx = 26f)
        view.layoutParams = android.view.ViewGroup.LayoutParams(740, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        renderMarkdown(view, "前 \$\$x_1\$\$ 中间文字 \$\$y_2\$\$ 后", 740, Color.BLACK, Color.BLUE)
        measure(view); load(view)
        val index = view.text.indexOf("间")
        val x = view.totalPaddingLeft + view.layout.getPrimaryHorizontal(index) + 2
        val line = view.layout.getLineForOffset(index)
        val y = view.totalPaddingTop + (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f
        assertNull("Prose between formulas must not snap to either neighboring formula", formulaPartForPosition(view, x, y))
        assertEquals(index, view.getOffsetForPosition(x, y))
    }
}
