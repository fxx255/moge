package com.moge.app.ui.solve

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.ui.markdown.markdownSelectionController
import com.moge.app.ui.markdown.selectionEndpoint
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionAutoScrollUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var saved: ConversationViewport? = null
    private val answer = (1..150).joinToString("\n\n") { "第 $it 段。长答案选择复制时，需要滚动到原先屏幕外的内容。" }

    private fun views(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun body() = views(compose.activity.findViewById(android.R.id.content)).single { it.text.contains("第 150 段") }
    private fun show(offset: Int = 0) {
        compose.setContent { MogeTheme {
            SolveList(SolveUiState(conversationId = "test", items = listOf(
                SolveItem.Answer("answer", AnswerState.COMPLETED, answer))), {}, {}, { _, _ -> }, {}, {},
                initialViewport = ConversationViewport("answer", 0, offset),
                saveViewport = { saved = it }, contentPadding = PaddingValues(16.dp))
        } }
        compose.waitForIdle()
    }
    private fun selectVisibleWord(view: TextView) {
        val visible = android.graphics.Rect()
        assertTrue(view.getLocalVisibleRect(visible))
        val offset = view.getOffsetForPosition(view.width / 2f, visible.centerY().toFloat())
        requireNotNull(markdownSelectionController(view)).begin(offset, offset + 4)
    }
    private fun endpoint(view: TextView, start: Boolean): Pair<Float, Float> {
        val offset = if (start) view.selectionStart else view.selectionEnd
        val line = view.layout.getLineForOffset(offset)
        val position = IntArray(2).also { view.getLocationOnScreen(it) }
        return (position[0] + view.totalPaddingLeft + view.layout.getPrimaryHorizontal(offset)) to
            (position[1] + view.totalPaddingTop + view.layout.getLineBottom(line) + 10f)
    }
    private fun edge(bottom: Boolean): Float {
        val root = compose.activity.findViewById<ViewGroup>(android.R.id.content)
        val position = IntArray(2).also { root.getLocationOnScreen(it) }
        return position[1] + if (bottom) root.height - 18f else 18f
    }

    @Test fun `stationary end handle scrolls down extends selection and stops immediately on release`() {
        show()
        lateinit var view: TextView
        var start = 0
        var originalEnd = 0
        compose.runOnIdle {
            view = body()
            selectVisibleWord(view)
            start = view.selectionStart
            originalEnd = view.selectionEnd
        }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            val controller = requireNotNull(markdownSelectionController(view))
            val point = endpoint(view, false)
            controller.beginDrag(false, point.first, point.second)
            assertTrue("Dragging must show the magnifier", controller.magnifier.isShowing)
            controller.moveDrag(point.first, edge(true))
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnUiThread {
            assertEquals(start, view.selectionStart)
            assertTrue("Stationary edge drag must keep adding offscreen paragraphs", view.selectionEnd > originalEnd + 300)
            requireNotNull(markdownSelectionController(view)).endDrag()
            assertFalse("Release must dismiss the magnifier", requireNotNull(markdownSelectionController(view)).magnifier.isShowing)
        }
        val after = saved
        var selectedEnd = 0
        compose.runOnUiThread { selectedEnd = view.selectionEnd }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread {
            assertEquals("Releasing must stop scrolling", after, saved)
            assertEquals(selectedEnd, view.selectionEnd)
            val expected = view.text.subSequence(start, selectedEnd).toString()
            view.onTextContextMenuItem(android.R.id.copy)
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertFalse(requireNotNull(markdownSelectionController(view)).selecting)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun `start handle reveals previous paragraphs and leaving edge stops further scrolling`() {
        show(offset = 2600)
        lateinit var view: TextView
        var originalStart = 0
        var end = 0
        compose.runOnIdle {
            view = body()
            selectVisibleWord(view)
            originalStart = view.selectionStart
            end = view.selectionEnd
        }
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            val point = endpoint(view, true)
            val controller = requireNotNull(markdownSelectionController(view))
            controller.beginDrag(true, point.first, point.second)
            controller.moveDrag(point.first, edge(false))
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.runOnUiThread {
            assertTrue("Top edge must reveal and select earlier paragraphs", view.selectionStart < originalStart - 100)
            assertEquals(end, view.selectionEnd)
            val controller = requireNotNull(markdownSelectionController(view))
            controller.moveDrag(endpoint(view, true).first, (edge(false) + edge(true)) / 2)
            assertTrue(controller.magnifier.isShowing)
        }
        // Allow the last already-dispatched scroll frame to complete.
        compose.mainClock.advanceTimeBy(32)
        val after = saved
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread {
            assertEquals("Moving back to the middle must stop scrolling", after, saved)
            requireNotNull(markdownSelectionController(view)).endDrag()
            requireNotNull(markdownSelectionController(view)).finish()
            assertFalse(requireNotNull(markdownSelectionController(view)).magnifier.isShowing)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun `ending selection during a drag removes the magnifier and stops edge scrolling`() {
        show()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            val view = body()
            selectVisibleWord(view)
            val controller = requireNotNull(markdownSelectionController(view))
            val point = endpoint(view, false)
            controller.beginDrag(false, point.first, point.second)
            controller.moveDrag(point.first + 8f, point.second + 3f)
            assertSamplesEndpoint(view, start = false)
            controller.moveDrag(point.first, edge(true))
            assertTrue(controller.magnifier.isShowing)
            controller.finish()
            assertFalse(controller.selecting)
            assertFalse(controller.dragging)
            assertFalse(controller.magnifier.isShowing)
        }
        compose.mainClock.advanceTimeBy(32)
        val after = saved
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread { assertEquals(after, saved) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun `crossing the start handle past the end continues selecting and scrolling down`() {
        show()
        compose.mainClock.autoAdvance = false
        var anchor = 0
        var crossedEnd = 0
        compose.runOnUiThread {
            val view = body()
            selectVisibleWord(view)
            anchor = view.selectionEnd
            val controller = requireNotNull(markdownSelectionController(view))
            val point = endpoint(view, true)
            controller.beginDrag(true, point.first, point.second)
            controller.moveDrag(point.first, edge(true))
            assertEquals(anchor, view.selectionStart)
            assertTrue(view.selectionEnd > anchor)
            crossedEnd = view.selectionEnd
            assertSamplesEndpoint(view, start = false)
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.runOnUiThread {
            val view = body()
            val controller = requireNotNull(markdownSelectionController(view))
            assertEquals(anchor, view.selectionStart)
            assertTrue("The exchanged end handle must continue selecting offscreen text", view.selectionEnd > crossedEnd + 300)
            assertSamplesEndpoint(view, start = false)
            controller.endDrag()
            assertTrue(controller.selecting)
            assertFalse(controller.magnifier.isShowing)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun `lens stays with the finger at both horizontal edges and below the upper scroll edge`() {
        show(offset = 2600)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            val view = body()
            selectVisibleWord(view)
            val controller = requireNotNull(markdownSelectionController(view))
            val point = endpoint(view, true)
            val anchor = view.selectionEnd
            // Grab away from the anchor: only window placement follows the finger.
            controller.beginDrag(true, point.first - 17f, point.second + 12f)
            val y = (edge(false) + edge(true)) / 2f
            for (x in listOf(1f, view.rootView.width - 1f)) {
                controller.moveDrag(x, y)
                assertSamplesEndpoint(view, start = view.selectionEnd == anchor)
                if (x < view.rootView.width / 2f) assertTrue(controller.magnifier.screenBounds.centerX() < view.rootView.width / 2f)
                else assertTrue(controller.magnifier.screenBounds.centerX() > view.rootView.width / 2f)
            }
            controller.moveDrag(point.first, edge(false))
            assertTrue(controller.magnifier.screenBounds.top > edge(false))
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnUiThread {
            val view = body()
            val controller = requireNotNull(markdownSelectionController(view))
            assertSamplesEndpoint(view, start = true)
            assertTrue(controller.magnifier.screenBounds.top > edge(false))
            controller.endDrag()
        }
        compose.mainClock.autoAdvance = true
    }

    private fun assertSamplesEndpoint(view: TextView, start: Boolean) {
        val offset = if (start) view.selectionStart else view.selectionEnd
        val caret = requireNotNull(selectionEndpoint(view, offset, start))
        val sample = requireNotNull(markdownSelectionController(view)).magnifier.sourcePoint
        assertEquals("Magnified boundary must follow the active endpoint", caret.x, sample.first, .01f)
        assertEquals("Magnified row must stay vertically centered during scroll", caret.centerY, sample.second, .01f)
    }
}
