package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.noties.markwon.core.spans.OrderedListItemSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.ceil

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingListMarkerTest {
    @Test fun `first answer number remains painted while subsequent answers are still being revealed`() {
        for ((ink, background) in listOf(Color.BLACK to Color.WHITE, Color.WHITE to Color.rgb(30, 43, 38))) {
            for (fontSize in listOf(28f, 44f)) {
                val reveal = StreamingInkReveal()
                val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), ink, ink,
                    fontSizePx = fontSize, inkReveal = reveal)
                view.layoutParams = ViewGroup.LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT)
                val tokenizer = StreamingMarkdownTokenizer()
                renderStreamingMarkdown(view, tokenizer.update("21. C"), 600, ink, ink)
                measure(view)
                reveal.update(view, catchingUp = false)
                reveal.enabled = true
                for (source in listOf("21. C 22. A 23. B", "21. C 22. A 23. B 24. D 25. D\n\n文章结构继续输出。")) {
                    // The first answer has appeared, while the new suffix has not yet animated in.
                    val snapshot = tokenizer.update(source).copy(blocks = emptyList(), tail = source, tailStart = 0,
                        visibleEnd = source.length, renderedPrefixEnd = source.length)
                    renderStreamingMarkdown(view, snapshot, 600, ink, ink)
                    measure(view)
                    reveal.update(view, catchingUp = false)
                    val text = view.text as Spanned
                    assertEquals(1, text.getSpans(0, text.length, OrderedListItemSpan::class.java).size)
                    assertTrue("The first number is a margin span, not literal text", !text.startsWith("21."))
                    val during = draw(view, background)
                    reveal.enabled = false
                    val complete = draw(view, background)
                    try {
                        val marginRight = view.totalPaddingLeft + ceil(view.layout.getPrimaryHorizontal(0)).toInt()
                        var markerPixels = 0
                        for (y in view.totalPaddingTop until view.totalPaddingTop + view.layout.getLineBottom(0)) {
                            for (x in view.totalPaddingLeft until marginRight) {
                                val pixel = complete.getPixel(x, y)
                                if (pixel != background) {
                                    markerPixels++
                                    assertEquals("First number disappeared during streaming at ($x,$y), size=$fontSize",
                                        pixel, during.getPixel(x, y))
                                }
                            }
                        }
                        assertTrue("The fixture must actually paint its first number", markerPixels > 10)
                        assertTrue("The unrevealed suffix must remain hidden", inkPixels(during, background) < inkPixels(complete, background))
                    } finally { during.recycle(); complete.recycle() }
                    reveal.enabled = true
                }
                reveal.finish(view)
            }
        }
    }

    private fun measure(view: TextView) {
        view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, 600, view.measuredHeight)
    }

    private fun draw(view: TextView, background: Int) =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
            Canvas(it).apply { drawColor(background); view.draw(this) }
        }

    private fun inkPixels(bitmap: Bitmap, background: Int): Int {
        var count = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) != background) count++
        }
        return count
    }
}
