package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import com.moge.app.data.parse.splitFormula
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
class FunctionEquationRenderTest {
    private val equations = listOf(
        "M(x,y)=\\sin y-y\\sin x,",
        "N(x,y)=x\\cos y+\\cos x",
        "\\frac{\\partial M}{\\partial y}=\\cos y-\\sin x,",
        "\\frac{\\partial N}{\\partial x}=\\cos y-\\sin x",
    )
    private val answer = "$$\n${equations[0]}\\qquad ${equations[1]}\n$$\n\n" +
        "### 三、验证恰当性\n\n$$\n${equations[2]}\\qquad ${equations[3]}\n$$"

    @Test fun `a pair just beyond the available width breaks before N instead of inside its tuple`() {
        createMarkdownTextView(RuntimeEnvironment.getApplication(), Color.BLACK, Color.BLUE)
        for (size in listOf(26f, 36f)) {
            val measure = formulaWidthMeasurer(size)
            for (second in listOf(equations[1], equations[1].replace("(x,y)", "\\left(x,y\\right)"))) {
                val source = equations[0] + "\\qquad " + second
                val width = measure(source) - 8
                assertTrue(width >= maxOf(measure(equations[0]), measure(second)))
                assertEquals(listOf(equations[0], second), splitFormula(source, width, measure))
            }
        }
    }

    @Test fun `reported function and derivative pairs wrap as complete centered equations`() {
        for ((width, size) in listOf(360 to 26f, 480 to 36f)) {
            for (chalk in listOf(false, true)) for (streaming in listOf(false, true)) {
                val ink = if (chalk) Color.WHITE else Color.BLACK
                val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), ink, Color.BLUE, fontSizePx = size)
                view.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                try {
                    if (streaming) {
                        // The tail prefix uses the same normalization and wrapping as completed blocks.
                        renderStreamingMarkdown(view, StreamingMarkdownSnapshot(emptyList(), tail = answer,
                            renderedPrefixEnd = answer.length), width, ink, Color.BLUE)
                    } else renderMarkdown(view, answer, width, ink, Color.BLUE)
                    AsyncDrawableScheduler.unschedule(view)
                    val text = view.text as Spanned
                    val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java).sortedBy(text::getSpanStart)
                    assertEquals(equations, spans.map { it.drawable.destination.trim() })
                    spans.forEach { span ->
                        val native = JLatexMathDrawable.builder(span.drawable.destination).textSize(size).color(ink)
                            .align(JLatexMathDrawable.ALIGN_CENTER).padding(0).build()
                        assertTrue(span.drawable.destination, native.intrinsicWidth <= formulaMaxWidthPx(view, width))
                        span.drawable.initWithKnownDimensions(width - view.totalPaddingLeft - view.totalPaddingRight, size)
                        span.drawable.setResult(native)
                        assertEquals(span.drawable.destination.trim(), selectedMarkdownText(text,
                            text.getSpanStart(span), text.getSpanEnd(span)))
                    }
                    view.text = text
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, width, view.measuredHeight)
                    spans.forEach { span ->
                        val parts = formulaSelectionParts(view, span)
                        assertTrue(parts.isNotEmpty())
                        val center = (parts.minOf { it.bounds.left } + parts.maxOf { it.bounds.right }) / 2
                        assertEquals("Each equation must center independently: ${span.drawable.destination}",
                            width / 2f, center, size * .35f)
                    }
                    if (!streaming) {
                        val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
                        try {
                            val canvas = Canvas(bitmap)
                            canvas.drawColor(if (chalk) Color.rgb(36, 51, 45) else Color.rgb(250, 247, 234))
                            view.draw(canvas)
                            val file = File("build/ui-refinement-previews/function-equations-$width-${if (chalk) "chalk" else "paper"}.png")
                            file.parentFile!!.mkdirs()
                            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } finally { bitmap.recycle() }
                    }
                } finally { AsyncDrawableScheduler.unschedule(view) }
            }
        }
    }
}
