package com.moge.app.ui.solve

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.ui.markdown.StreamingInkReveal
import com.moge.app.ui.markdown.StreamingMarkdownTokenizer
import com.moge.app.ui.markdown.createMarkdownTextView
import com.moge.app.ui.markdown.renderStreamingMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil

/** The marker lives outside glyph selection bounds, so checking text/spans alone misses this bug. */
@RunWith(AndroidJUnit4::class)
class StreamingListMarkerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun firstNumberIsVisibleDuringEveryAppendInBothThemesAtLargeFont() {
        compose.runOnUiThread {
            for ((name, ink, background) in listOf(Triple("paper", Color.BLACK, Color.WHITE),
                Triple("chalk", Color.WHITE, Color.rgb(30, 43, 38)))) {
                for (size in listOf(30f, 48f)) {
                    val reveal = StreamingInkReveal()
                    val view = createMarkdownTextView(compose.activity, ink, ink, fontSizePx = size, inkReveal = reveal)
                    view.layoutParams = ViewGroup.LayoutParams(600, ViewGroup.LayoutParams.WRAP_CONTENT)
                    val tokenizer = StreamingMarkdownTokenizer()
                    renderStreamingMarkdown(view, tokenizer.update("21. C"), 600, ink, ink)
                    measure(view)
                    reveal.update(view, false)
                    reveal.enabled = true
                    for ((index, source) in listOf("21. C 22. A 23. B", "21. C 22. A 23. B 24. D 25. D\n\n文章结构继续输出。").withIndex()) {
                        val snapshot = tokenizer.update(source).copy(blocks = emptyList(), tail = source, tailStart = 0,
                            visibleEnd = source.length, renderedPrefixEnd = source.length)
                        renderStreamingMarkdown(view, snapshot, 600, ink, ink)
                        measure(view)
                        reveal.update(view, false)
                        val during = draw(view, background)
                        reveal.enabled = false
                        val complete = draw(view, background)
                        try {
                            val right = view.totalPaddingLeft + ceil(view.layout.getPrimaryHorizontal(0)).toInt()
                            var markerPixels = 0
                            for (y in view.totalPaddingTop until view.totalPaddingTop + view.layout.getLineBottom(0)) {
                                for (x in view.totalPaddingLeft until right) {
                                    val expected = complete.getPixel(x, y)
                                    if (expected != background) {
                                        markerPixels++
                                        assertEquals("First number clipped in $name at ($x,$y), font=$size", expected, during.getPixel(x, y))
                                    }
                                }
                            }
                            assertTrue("A numbered marker must actually be painted", markerPixels > 10)
                            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
                                "streaming-marker-$name-${size.toInt()}-$index.png").outputStream().use {
                                during.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                        } finally { during.recycle(); complete.recycle() }
                        reveal.enabled = true
                    }
                    reveal.finish(view)
                }
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
}
