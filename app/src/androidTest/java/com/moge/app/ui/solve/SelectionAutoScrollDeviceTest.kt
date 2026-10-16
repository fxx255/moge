package com.moge.app.ui.solve

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.ui.markdown.markdownSelectionController
import com.moge.app.ui.markdown.selectionEndpoint
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real PopupWindow handle events, rather than TextView/controller-only gesture simulation. */
@RunWith(AndroidJUnit4::class)
class SelectionAutoScrollDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var saved: ConversationViewport? = null
    private var displayId = 0

    /** Auto-scroll and the native floating toolbar intentionally keep the UI active. */
    private fun awaitFrame(view: View) {
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync { view.postOnAnimation { frame.countDown() } }
        assertTrue("The selection window must present a frame", frame.await(2, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { }
    }

    private fun views(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun inject(action: Int, x: Float, y: Float, downTime: Long) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        // Android 12 injects default-display events without a display setter.
        // Newer test runners may launch on a virtual display and need explicit routing.
        if (displayId != Display.DEFAULT_DISPLAY) {
            android.view.InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .invoke(event, displayId)
        }
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }
    private fun handle(view: TextView, start: Boolean): Pair<Float, Float> {
        val offset = if (start) view.selectionStart else view.selectionEnd
        val line = view.layout.getLineForOffset(offset)
        val position = IntArray(2).also { view.getLocationOnScreen(it) }
        val density = view.resources.displayMetrics.density
        return (position[0] + view.totalPaddingLeft + view.layout.getPrimaryHorizontal(offset)) to
            (position[1] + view.totalPaddingTop + view.layout.getLineBottom(line) + 14f * density)
    }

    @Test fun handlesExtendBeyondScreenAccelerateAndStopOnRelease() {
        val answer = (1..160).joinToString("\n\n") { "第 $it 段：long answer selection needs to reveal and copy text beyond the viewport." }
        compose.setContent { MogeTheme {
            SolveList(SolveUiState(conversationId = "test", items = listOf(
                SolveItem.Answer("answer", AnswerState.COMPLETED, answer))), {}, {}, { _, _ -> }, {}, {},
                initialViewport = ConversationViewport("answer", 0, 0), saveViewport = { saved = it },
                contentPadding = PaddingValues(16.dp))
        } }
        compose.waitForIdle()
        lateinit var view: TextView
        lateinit var root: ViewGroup
        var x = 0f
        var y = 0f
        var top = 0f
        var bottom = 0f
        compose.runOnIdle {
            root = compose.activity.findViewById(android.R.id.content)
            view = views(root).single { it.text.contains("第 160 段") }
            displayId = requireNotNull(view.display).displayId
            val position = IntArray(2).also { view.getLocationOnScreen(it) }
            x = position[0] + view.paddingLeft + 50f
            y = (position[1] + view.totalPaddingTop + view.layout.getLineBaseline(4)).toFloat()
            val rootPosition = IntArray(2).also { root.getLocationOnScreen(it) }
            val density = view.resources.displayMetrics.density
            top = rootPosition[1] + 16f * density
            bottom = rootPosition[1] + root.height - 16f * density
        }
        var time = SystemClock.uptimeMillis()
        inject(MotionEvent.ACTION_DOWN, x, y, time)
        SystemClock.sleep(800)
        inject(MotionEvent.ACTION_UP, x, y, time)
        compose.runOnIdle { assertTrue("Long press must show the conversation selection menu", view.hasSelection()) }

        var fixed = 0
        var initial = 0
        var point = 0f to 0f
        compose.runOnIdle {
            fixed = view.selectionStart
            initial = view.selectionEnd
            point = handle(view, false)
        }
        compose.mainClock.autoAdvance = false
        try {
            time = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, point.first, point.second, time)
            val density = view.resources.displayMetrics.density
            // The lens window stays with the finger at opposite screen edges,
            // while its enlarged content follows the selected row and boundary.
            for (fingerX in listOf(2f * density, root.width - 2f * density)) {
                val fingerY = (top + bottom) / 2f
                inject(MotionEvent.ACTION_MOVE, fingerX, fingerY, time)
                instrumentation.runOnMainSync {
                    val controller = requireNotNull(markdownSelectionController(view))
                    val caret = requireNotNull(selectionEndpoint(view, view.selectionEnd, start = false))
                    assertEquals(caret.x, controller.magnifier.sourcePoint.first, 1f)
                    assertEquals(caret.centerY, controller.magnifier.sourcePoint.second, 1f)
                    assertEquals(fingerX, controller.magnifier.screenBounds.centerX(), controller.magnifier.screenBounds.width() / 2f + 1f)
                }
            }
            inject(MotionEvent.ACTION_UP, point.first, point.second, time)
            instrumentation.runOnMainSync {
                requireNotNull(markdownSelectionController(view)).begin(fixed, initial)
            }
            awaitFrame(view)
            instrumentation.runOnMainSync { point = handle(view, false) }
            time = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, point.first, point.second, time)
            inject(MotionEvent.ACTION_MOVE, point.first, bottom - 40f * density, time)
            compose.mainClock.advanceTimeBy(700)
            awaitFrame(view)
            var slow = 0
            instrumentation.runOnMainSync { slow = view.selectionEnd }
            assertTrue("Handle popup must scroll and extend selection", slow > initial + 100)
            inject(MotionEvent.ACTION_MOVE, point.first, bottom - 2f * density, time)
            var fastStart = 0
            instrumentation.runOnMainSync { fastStart = view.selectionEnd }
            compose.mainClock.advanceTimeBy(700)
            awaitFrame(view)
            var fast = 0
            instrumentation.runOnMainSync {
                fast = view.selectionEnd
                assertEquals("The opposite endpoint remains anchored", fixed, view.selectionStart)
            }
            assertTrue("Closer to the bottom must scroll faster", fast - fastStart > slow - initial)
            inject(MotionEvent.ACTION_UP, point.first, bottom - 2f * density, time)
            var stopped: ConversationViewport? = null
            instrumentation.runOnMainSync { stopped = saved }
            compose.mainClock.advanceTimeBy(400)
            awaitFrame(view)
            instrumentation.runOnMainSync {
                assertEquals(stopped, saved)
                assertEquals(fast, view.selectionEnd)
                val selected = view.text.subSequence(fixed, fast).toString()
                view.onTextContextMenuItem(android.R.id.copy)
                val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(selected, clipboard.primaryClip!!.getItemAt(0).text.toString())
            }

            // Drag the start handle upward from the new viewport using actual popup events.
            var originalStart = 0
            var originalEnd = 0
            instrumentation.runOnMainSync {
                val visible = android.graphics.Rect()
                assertTrue(view.getLocalVisibleRect(visible))
                val offset = view.getOffsetForPosition(view.width / 2f, visible.centerY().toFloat())
                requireNotNull(markdownSelectionController(view)).begin(offset, offset + 5)
                originalStart = view.selectionStart
                originalEnd = view.selectionEnd
                point = handle(view, true)
            }
            compose.mainClock.advanceTimeBy(32)
            awaitFrame(view)
            instrumentation.runOnMainSync {
                assertTrue(requireNotNull(markdownSelectionController(view)).selecting)
                point = handle(view, true)
            }
            time = SystemClock.uptimeMillis()
            inject(MotionEvent.ACTION_DOWN, point.first, point.second, time)
            inject(MotionEvent.ACTION_MOVE, point.first, top + 2f * density, time)
            compose.mainClock.advanceTimeBy(700)
            awaitFrame(view)
            instrumentation.runOnMainSync {
                assertTrue("Top edge reveals earlier paragraphs", view.selectionStart < originalStart - 100)
                assertEquals(originalEnd, view.selectionEnd)
                val controller = requireNotNull(markdownSelectionController(view))
                assertTrue("The upper-edge magnifier must stay below the finger", controller.magnifier.screenBounds.top > top + 2f * density)
            }
            inject(MotionEvent.ACTION_CANCEL, point.first, top + 2f * density, time)

            var range = 0 to 0
            var beforeSwipe: ConversationViewport? = null
            instrumentation.runOnMainSync {
                range = view.selectionStart to view.selectionEnd
                beforeSwipe = saved
            }
            time = SystemClock.uptimeMillis()
            val swipeX = root.width * .65f
            val swipeY = bottom - 100f * density
            inject(MotionEvent.ACTION_DOWN, swipeX, swipeY, time)
            for (step in 1..8) {
                inject(MotionEvent.ACTION_MOVE, swipeX, swipeY - step * 18f * density, time)
                compose.mainClock.advanceTimeBy(16)
                awaitFrame(view)
            }
            inject(MotionEvent.ACTION_UP, swipeX, swipeY - 144f * density, time)
            compose.mainClock.advanceTimeBy(64)
            awaitFrame(view)
            instrumentation.runOnMainSync {
                assertNotEquals("A content swipe still scrolls the conversation", beforeSwipe, saved)
                assertEquals("Browsing must retain the selected range", range, view.selectionStart to view.selectionEnd)
                assertTrue(requireNotNull(markdownSelectionController(view)).selecting)
            }
        } finally {
            // Release native windows and the paused-clock content even if an assertion fails.
            instrumentation.runOnMainSync {
                markdownSelectionController(view)?.finish()
                compose.activity.setContent { }
            }
            compose.mainClock.autoAdvance = true
        }
    }
}
