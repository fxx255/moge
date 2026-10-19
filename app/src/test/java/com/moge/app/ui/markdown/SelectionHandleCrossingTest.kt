package com.moge.app.ui.markdown

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionHandleCrossingTest {
    @Test fun `either handle crosses its anchor and crosses back in the same touch gesture`() {
        for (start in listOf(true, false)) withSelection("0123456789abcdefghijklmnopqrstuvwxyz", 4, 10) { view, controller ->
            val anchor = if (start) 10 else 4
            val draggedHandle = handle(start)
            val drag = HandleDrag(view, draggedHandle, if (start) 4 else 10, start)
            drag.down()
            val targets = if (start) listOf(anchor, 14, 17, anchor, 7) else listOf(anchor, 1, 0, anchor, 12)
            var movingStart = start
            for (target in targets) {
                drag.moveTo(target)
                if (target != anchor) movingStart = target < anchor
                val moving = if (target == anchor) anchor + (if (movingStart) -1 else 1) else target
                assertEquals(minOf(anchor, moving), view.selectionStart)
                assertEquals(maxOf(anchor, moving), view.selectionEnd)
                assertTrue("Crossing must retain the active gesture", controller.dragging)
                assertTrue("The popup receiving the gesture must stay attached", draggedHandle.isAttachedToWindow)
                assertSame(draggedHandle, handle(movingStart))
                assertLensAtEndpoint(view, controller, movingStart)
            }
            drag.up()
            assertFalse(controller.dragging)
            assertTrue(controller.selecting)
            assertFalse(controller.magnifier.isShowing)
            assertCopiesSelection(view)
        }
    }

    @Test fun `crossing across rows and document edges keeps the same handle and magnified row`() {
        val content = "First row of text.\nSecond row of text.\nThird row of text."
        val from = content.indexOf("Second") + 2
        val anchor = from + 5
        withSelection(content, from, anchor) { view, controller ->
            val draggedHandle = handle(true)
            val drag = HandleDrag(view, draggedHandle, from, true)
            drag.down()
            for (target in listOf(content.indexOf("Third") + 5, 3, content.length, 0)) {
                drag.moveTo(target)
                val movingStart = target < anchor
                assertEquals(minOf(anchor, target), view.selectionStart)
                assertEquals(maxOf(anchor, target), view.selectionEnd)
                assertSame(draggedHandle, handle(movingStart))
                assertLensAtEndpoint(view, controller, movingStart)
            }
            drag.up()
            assertCopiesSelection(view)
        }
    }

    private fun withSelection(content: String, from: Int, to: Int,
        check: (TextView, MarkdownSelectionController) -> Unit) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val scope = CoroutineScope(Dispatchers.Main)
        val host = SelectionScrollHost(scope, 1f) { _, _ -> }
        try {
            val view = createMarkdownTextView(activity.get(), Color.BLACK, Color.BLUE,
                fontSizePx = 26f, selectionScrollHost = host)
            view.text = SpannableString(content)
            activity.get().setContentView(view)
            view.measure(View.MeasureSpec.makeMeasureSpec(760, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 760, 520)
            val position = IntArray(2).also(view::getLocationOnScreen)
            host.viewport = RectF(position[0].toFloat(), position[1].toFloat(), position[0] + 760f, position[1] + 520f)
            val controller = requireNotNull(markdownSelectionController(view))
            controller.begin(from, to)
            shadowOf(Looper.getMainLooper()).idle()
            check(view, controller)
        } finally {
            host.dispose()
            scope.cancel()
            activity.pause().stop().destroy()
        }
    }

    private fun handle(start: Boolean): View = WindowInspector.getGlobalWindowViews()
        .flatMap(::descendants).single { it.contentDescription == if (start) "选区起点" else "选区终点" }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
    } else emptyList()

    private fun assertLensAtEndpoint(view: TextView, controller: MarkdownSelectionController, start: Boolean) {
        view.viewTreeObserver.dispatchOnPreDraw()
        val offset = if (start) view.selectionStart else view.selectionEnd
        val caret = requireNotNull(selectionEndpoint(view, offset, start))
        assertTrue(controller.magnifier.isShowing)
        assertEquals(caret.x, controller.magnifier.sourcePoint.first, .01f)
        assertEquals(caret.centerY, controller.magnifier.sourcePoint.second, .01f)
    }

    private fun assertCopiesSelection(view: TextView) {
        val expected = view.text.subSequence(view.selectionStart, view.selectionEnd).toString()
        view.onTextContextMenuItem(android.R.id.copy)
        val clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    /** Deliver every event to the original popup, even after its role and position change. */
    private class HandleDrag(private val source: TextView, private val handle: View, offset: Int, start: Boolean) {
        private val downTime = SystemClock.uptimeMillis()
        private var eventTime = downTime
        private val caret = requireNotNull(selectionEndpoint(source, offset, start))
        private val grabY = caret.bottom - caret.centerY + 8f
        private var offset = offset
        fun down() = dispatch(MotionEvent.ACTION_DOWN)
        fun up() = dispatch(MotionEvent.ACTION_UP)
        fun moveTo(offset: Int) {
            this.offset = offset
            dispatch(MotionEvent.ACTION_MOVE)
        }
        private fun dispatch(action: Int) {
            val target = requireNotNull(selectionEndpoint(source, offset, true))
            val sourcePosition = IntArray(2).also(source::getLocationOnScreen)
            val handlePosition = IntArray(2).also(handle::getLocationOnScreen)
            eventTime += 16
            val event = MotionEvent.obtain(downTime, eventTime, action,
                sourcePosition[0] + target.x, sourcePosition[1] + target.centerY + grabY, 0)
            event.offsetLocation(-handlePosition[0].toFloat(), -handlePosition[1].toFloat())
            try { assertTrue(handle.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }
}
