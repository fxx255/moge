package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.moge.app.data.prefs.ListMarkerSettings
import com.moge.app.data.prefs.ListMarkerStyle
import io.noties.markwon.core.spans.BulletListItemSpan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListMarkerRenderingTest {
    @Test fun allShapesScaleWithTextAndKeepTheirColorsWithoutTouchingTheBody() {
        for ((background, ink, accent) in listOf(Triple(Color.WHITE, Color.BLACK, 0xFF71867E.toInt()),
            Triple(0xFF24332D.toInt(), 0xFFECEDE6.toInt(), 0xFF9DB9AE.toInt()))) {
            for (style in ListMarkerStyle.entries) {
                var small: Rect? = null
                for (size in listOf(28f, 56f)) {
                    val view = view(ListMarkerSettings(style), size, ink, accent)
                    renderMarkdown(view, "- First item with wrapped text that continues on another line.", 460, ink, ink)
                    measure(view)
                    val bitmap = draw(view, background)
                    try {
                        val bounds = markerBounds(view, bitmap, background)
                        assertTrue("$style must draw an actual marker", bounds.width() > 2 && bounds.height() > 2)
                        val expected = if (style == ListMarkerStyle.CLASSIC_DOT) ink else accent
                        assertTrue("$style must use the correct theme ink",
                            (bounds.left until bounds.right).any { x -> (bounds.top until bounds.bottom).any { y -> bitmap.getPixel(x, y) == expected } })
                        if (small == null) small = bounds else {
                            assertEquals("$style width must grow with body size", small.width() * 2f, bounds.width().toFloat(), 3f)
                            assertEquals("$style height must grow with body size", small.height() * 2f, bounds.height().toFloat(), 3f)
                        }
                    } finally { bitmap.recycle() }
                }
                val largest = view(ListMarkerSettings(style, 160, 160, 75), 56f, ink, accent)
                renderMarkdown(largest, "- Largest marker with minimum indentation.", 460, ink, ink)
                measure(largest)
                val bitmap = draw(largest, background)
                try {
                    val bounds = markerBounds(largest, bitmap, background)
                    assertTrue("$style must not be clipped at the left edge", bounds.left > largest.totalPaddingLeft)
                    assertTrue("$style must leave space before the first letter",
                        bounds.right < largest.totalPaddingLeft + largest.layout.getPrimaryHorizontal(0) - 1)
                } finally { bitmap.recycle() }
            }
        }
    }

    @Test fun configuredNestedListsMatchTheirCompletedLayoutWhileStreaming() {
        val source = "- **First item** with enough words to wrap across lines and retain its indentation.\n  - Nested item.\n- Second item."
        for (style in ListMarkerStyle.entries) {
            val settings = ListMarkerSettings(style, 130, 120, 115)
            val live = view(settings, 36f, Color.BLACK, 0xFF71867E.toInt())
            val complete = view(settings, 36f, Color.BLACK, 0xFF71867E.toInt())
            val snapshot = StreamingMarkdownTokenizer().update(source, flush = true)
                .copy(blocks = emptyList(), tail = source, tailStart = 0, renderedPrefixEnd = source.length, visibleEnd = source.length)
            renderStreamingMarkdown(live, snapshot, 460, Color.BLACK, Color.BLACK)
            renderMarkdown(complete, source, 460, Color.BLACK, Color.BLACK)
            measure(live); measure(complete)
            assertEquals(complete.height, live.height)
            val a = draw(live, Color.WHITE)
            val b = draw(complete, Color.WHITE)
            try { assertTrue("$style must render identically at completion", a.sameAs(b)) }
            finally { a.recycle(); b.recycle() }
        }
    }

    private fun view(settings: ListMarkerSettings, size: Float, ink: Int, accent: Int) =
        createMarkdownTextView(RuntimeEnvironment.getApplication(), ink, ink, selectable = false,
            markerColor = accent, fontSizePx = size, listMarkers = settings).apply {
            layoutParams = ViewGroup.LayoutParams(460, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    private fun measure(view: TextView) {
        view.measure(View.MeasureSpec.makeMeasureSpec(460, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun draw(view: TextView, background: Int) = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        Canvas(it).apply { drawColor(background); view.draw(this) }
    }

    private fun markerBounds(view: TextView, bitmap: Bitmap, background: Int): Rect {
        val text = view.text as Spanned
        val span = text.getSpans(0, text.length, BulletListItemSpan::class.java).first()
        val bounds = Rect(bitmap.width, bitmap.height, -1, -1)
        for (y in 0 until view.layout.getLineBottom(0)) {
            for (x in view.totalPaddingLeft until view.totalPaddingLeft + span.getLeadingMargin(true)) {
                if (bitmap.getPixel(x, y) != background) {
                    bounds.left = minOf(bounds.left, x); bounds.top = minOf(bounds.top, y)
                    bounds.right = maxOf(bounds.right, x + 1); bounds.bottom = maxOf(bounds.bottom, y + 1)
                }
            }
        }
        return bounds
    }
}
