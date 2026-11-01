package com.moge.app.ui.markdown

import android.app.Application
import android.text.Spannable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownNativeScrollTest {
    @Test fun `native movement cannot retain an offset from a temporarily smaller view slot`() {
        val view = createMarkdownTextView(RuntimeEnvironment.getApplication(), 0xff000000.toInt(), 0xff000000.toInt())
        view.layoutParams = ViewGroup.LayoutParams(900, ViewGroup.LayoutParams.WRAP_CONTENT)
        view.text = (0..70).joinToString("\n") { "第 $it 行：公式和文字重新排版后，纵向滚动仍由对话列表持有。" }
        view.measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val fullHeight = view.measuredHeight
        // During an interop reflow, the new text layout can precede its larger native slot.
        view.layout(0, 0, 900, 100)
        assertTrue(view.layout.height > view.height)
        val text = view.text as Spannable
        for ((action, y, time) in listOf(
            Triple(MotionEvent.ACTION_DOWN, 85f, 0L),
            Triple(MotionEvent.ACTION_MOVE, 10f, 100L),
            Triple(MotionEvent.ACTION_UP, 10f, 150L),
        )) {
            val event = MotionEvent.obtain(0L, time, action, 100f, y, 0)
            try { view.movementMethod.onTouchEvent(view, text, event) } finally { event.recycle() }
        }
        view.layout(0, 0, 900, fullHeight)
        assertEquals("Native movement left the first answer lines hidden after relayout", 0, view.scrollY)
        assertEquals(0, view.scrollX)
    }
}
