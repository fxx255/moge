package com.moge.app.ui.markdown

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.view.View
import io.noties.markwon.image.AsyncDrawableScheduler
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathDrawable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionMagnifierMathTest {
    @Test fun `single math letter and CJK prose keep their complete shared highlight height in the lens`() {
        for (size in listOf(32f, 44f)) for (chalk in listOf(false, true)) {
            for (prefix in listOf("", "交点是 ${'$'}${'$'}\\frac{\\sqrt{2}}{2}${'$'}${'$'}，")) {
                val activity = Robolectric.buildActivity(Activity::class.java).setup()
                val accent = if (chalk) Color.YELLOW else Color.BLUE
                val surface = if (chalk) Color.rgb(36, 51, 45) else Color.WHITE
                val body = createMarkdownTextView(activity.get(), if (chalk) Color.WHITE else Color.BLACK,
                    accent, fontSizePx = size, selectionSurfaceColor = surface)
                activity.get().setContentView(body)
                fun measure() {
                    body.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY))
                    body.layout(0, 0, 1000, 520)
                }
                renderMarkdown(body, prefix + "圆与 ${'$'}${'$'}x${'$'}${'$'} 轴的右交点。\n\n下一段。", 1000,
                    body.currentTextColor, accent)
                measure()
                AsyncDrawableScheduler.unschedule(body)
                val text = body.text as Spanned
                val formulas = text.getSpans(0, text.length, BaselineLatexSpan::class.java)
                for (span in formulas) {
                    span.drawable.initWithKnownDimensions(1000, size)
                    span.drawable.setResult(JLatexMathDrawable.builder(span.drawable.destination)
                        .textSize(size).color(body.currentTextColor).align(JLatexMathDrawable.ALIGN_LEFT).padding(0).build())
                }
                body.text = body.text
                measure()
                val letter = formulas.single { it.drawable.destination == "x" }
                val from = text.getSpanStart(letter)
                val to = text.toString().indexOf("右交") + 2
                body.requestFocus()
                Selection.setSelection(body.text as Spannable, from, to)
                val band = RectF().also { bounds ->
                    Path().also { markdownSelectionPath(body, from, to, it) }.computeBounds(bounds, true)
                }
                val formulaHeight = formulaSelectionParts(body, letter).single().bounds.height()
                assertTrue("The regression requires a math letter shorter than its shared selection band",
                    band.height() > formulaHeight + 4f)
                val magnifier = SelectionMagnifier(body, accent, surface)
                try {
                    for ((offset, start) in listOf(from to true, to to false)) {
                        val caret = requireNotNull(selectionEndpoint(body, offset, start))
                        val sampled = requireNotNull(magnifierSelectionEndpoint(body, offset, start))
                        assertEquals("Keep the correctly positioned caret", caret.x, sampled.x, .01f)
                        assertEquals(band.top, sampled.top, .01f)
                        assertEquals(band.bottom, sampled.bottom, .01f)
                        magnifier.show(caret.x, 300f, RectF(0f, 0f, 1000f, 520f), offset, start)
                        assertTrue(magnifier.isShowing)
                        assertEquals(band.centerY(), magnifier.sourcePoint.second, .01f)
                    }
                } finally {
                    magnifier.dismiss()
                    activity.pause().stop().destroy()
                }
            }
        }
    }
}
