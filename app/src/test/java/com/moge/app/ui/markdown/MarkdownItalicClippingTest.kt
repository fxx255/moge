package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Layout
import android.text.Spanned
import android.view.View
import io.noties.markwon.Markwon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownItalicClippingTest {
    @Before fun setup() { JLatexMathAndroid.init(RuntimeEnvironment.getApplication()) }

    private fun assertOverhangVisible(textColor: Int, background: Int, fontSize: Float, streaming: Boolean = false) {
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), textColor, textColor, fontSizePx = fontSize)
        val source = if (streaming) "*if*  \n\\(x\\)" else "*if*"
        if (streaming) {
            renderStreamingMarkdown(view, StreamingMarkdownTokenizer().update(source), 300, textColor, textColor)
        } else (view.tag as Markwon).setMarkdown(view, source)
        val rendered = view.text as Spanned
        val emphasis = rendered.getSpans(0, rendered.length, Class.forName("io.noties.markwon.core.spans.EmphasisSpan"))
        assertEquals("Fixture must use the real Markdown italic renderer", 1, emphasis.size)
        val lineEnd = rendered.indexOf('\n').takeIf { it >= 0 } ?: rendered.length
        val advance = ceil(Layout.getDesiredWidth(rendered, 0, lineEnd, view.paint)).toInt()
        val width = advance + view.totalPaddingLeft + view.totalPaddingRight
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
        assertTrue("Italic word must fit on its first line", view.layout.getLineEnd(0) >= 2)

        // Drawing Layout directly provides the uncropped glyph reference, with identical spans/font.
        val expected = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
        view.paint.color = textColor
        val expectedCanvas = Canvas(expected)
        expectedCanvas.drawColor(background)
        expectedCanvas.translate(view.totalPaddingLeft.toFloat(), view.totalPaddingTop.toFloat())
        view.layout.draw(expectedCanvas)
        val actual = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
        Canvas(actual).also { it.drawColor(background); view.draw(it) }
        var overhangPixels = 0
        for (y in view.totalPaddingTop until view.totalPaddingTop + view.layout.getLineBottom(0)) {
            for (x in view.totalPaddingLeft + advance until width) {
                val pixel = expected.getPixel(x, y)
                if (pixel != background) {
                    overhangPixels++
                    assertTrue("Italic overhang was cropped at ($x,$y), font=$fontSize", actual.getPixel(x, y) != background)
                }
            }
        }
        assertTrue("Fixture must really paint past its measured advance", overhangPixels > 0)
    }

    @Test fun `italic right edge is intact on paper and chalk with enlarged fonts`() {
        for (size in listOf(32f, 68f)) {
            assertOverhangVisible(Color.BLACK, Color.WHITE, size)
            assertOverhangVisible(Color.WHITE, Color.rgb(30, 49, 41), size)
        }
    }

    @Test fun `streaming parsed prefix keeps italic overhang visible`() {
        assertOverhangVisible(Color.WHITE, Color.rgb(30, 49, 41), 68f, streaming = true)
    }
}
