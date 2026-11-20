package com.moge.app.ui.markdown

import android.app.Activity
import android.app.Application
import android.graphics.RectF
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ReadingDuringReflowTest {
    @Test fun `scrolling to the next block during settling makes its line the completion anchor`() {
        Robolectric.buildActivity(Activity::class.java).setup().use { activity ->
            val root = FrameLayout(activity.get())
            activity.get().setContentView(root)
            fun paragraph(label: String): TextView = TextView(activity.get()).also {
                it.text = (1..30).joinToString("\n") { i -> "$label $i" }
                root.addView(it)
                it.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            }
            val first = paragraph("First")
            val next = paragraph("Next")
            fun place(firstY: Int, nextY: Int) {
                first.layout(0, firstY, 300, firstY + 500)
                next.layout(0, nextY, 300, nextY + 500)
            }
            place(0, 500)
            val corrections = mutableListOf<Float>()
            val host = ReadingScrollHost { corrections += it; it }
            val origin = IntArray(2).also(root::getLocationInWindow)
            host.viewport = RectF(origin[0].toFloat(), origin[1] + 10f, origin[0] + 300f, origin[1] + 300f)
            val position = MarkdownReadingPosition(host, root)
            position.blocks += listOf(first, next).map { ReadingBlock().apply { view = it } }
            position.update(true, "")
            assertTrue(position.onPreDraw())
            position.update(true, "summary")
            assertTrue(position.onPreDraw())
            host.readerScrollY -= 550f
            place(-550, -50)
            assertTrue(position.onPreDraw())
            assertTrue(corrections.isEmpty())
            position.update(false, "summary")
            // A figure between the blocks grows during the final layout. Tracking the
            // earlier block plus scroll distance would miss this shift entirely.
            place(-550, 40)
            assertFalse(position.onPreDraw())
            assertEquals(listOf(90f), corrections)
        }
    }
}
