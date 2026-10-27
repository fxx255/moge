package com.moge.app.ui.solve

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.moge.app.ui.markdown.markdownSelectionController
import com.moge.app.ui.markdown.selectionEndpoint
import com.moge.app.ui.theme.MogeTheme
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuestionSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun initializeMath() { JLatexMathAndroid.init(compose.activity) }
    private fun clipboard() = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun views(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `long press selects question text without starting edit and copies selected word`() {
        var edits = 0
        val raw = "original chosen question"
        compose.setContent { MogeTheme {
            SolveList(SolveUiState(conversationId = "q", items = listOf(
                SolveItem.Question("q", raw, emptyList(), "", false))),
                {}, {}, { _, _ -> }, {}, {}, onEdit = { edits++ })
        } }
        compose.runOnIdle {
            val view = views(compose.activity.window.decorView).single { it.text.toString() == raw }
            val caret = requireNotNull(selectionEndpoint(view, raw.indexOf("chosen") + 2, true))
            val now = SystemClock.uptimeMillis()
            fun dispatch(action: Int) {
                val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, caret.x, caret.centerY, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            dispatch(MotionEvent.ACTION_DOWN)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            dispatch(MotionEvent.ACTION_UP)
            assertEquals(0, edits)
            assertTrue(requireNotNull(markdownSelectionController(view)).selecting)
            assertEquals("chosen", view.text.subSequence(view.selectionStart, view.selectionEnd).toString())
            view.onTextContextMenuItem(android.R.id.copy)
            assertEquals("chosen", clipboard().primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun `question end handle shrinks by characters and stays put after release and recomposition`() {
        val raw = "帮我纠错，详细讲讲，注重原理和一般结论。"
        val version = mutableStateOf(0)
        var edits = 0
        compose.setContent { MogeTheme {
            val current = version.value
            SolveList(SolveUiState(conversationId = "q", items = listOf(
                SolveItem.Question("q", raw, emptyList(), "", true))),
                {}, {}, { _, _ -> }, {}, {}, onEdit = { edits += current + 1 })
        } }
        lateinit var body: TextView
        lateinit var selectedText: CharSequence
        compose.runOnIdle {
            body = views(compose.activity.window.decorView).single { it.text.toString() == raw }
            selectedText = body.text
            val controller = requireNotNull(markdownSelectionController(body))
            controller.begin(0, raw.length)
            val caret = requireNotNull(selectionEndpoint(body, raw.length, false))
            val position = IntArray(2).also(body::getLocationOnScreen)
            controller.beginDrag(false, position[0] + caret.x, position[1] + caret.bottom + 10f)
            for (offset in raw.lastIndex downTo 2) {
                // At a wrapped row boundary, move to the next row's first
                // character rather than Android's preceding-row caret alias.
                val target = requireNotNull(selectionEndpoint(body, offset, true))
                controller.moveDrag(position[0] + target.x,
                    position[1] + target.centerY + caret.bottom - caret.centerY + 10f)
                assertEquals("The end handle must follow every character", offset, body.selectionEnd)
            }
            controller.endDrag()
            version.value++
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertSame("Unrelated updates must preserve the selected Spannable", selectedText, body.text)
            assertEquals(0, body.selectionStart)
            assertEquals("Release must not restore the original full-line range", 2, body.selectionEnd)
            assertEquals(0, edits)
            body.onTextContextMenuItem(android.R.id.copy)
            assertEquals(raw.take(2), clipboard().primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun `literal question retains markdown currency paths and unfinished formulas through updates`() {
        val raw = mutableStateOf("  **原文** $5 到 $10，C:\\temp\\file.txt\n下一行  ")
        compose.setContent { MogeTheme {
            UserQuestionText(raw.value)
        } }
        fun assertLiteral() = compose.runOnIdle {
            val body = views(compose.activity.window.decorView).single { it.text.toString() == raw.value }
            assertTrue(nativeMathSpans(body).isEmpty())
            Selection.setSelection(body.text as Spannable, 0, body.text.length)
            body.onTextContextMenuItem(android.R.id.copy)
            assertEquals(raw.value, clipboard().primaryClip!!.getItemAt(0).text.toString())
        }
        assertLiteral()
        compose.runOnIdle { raw.value = "\\(\\frac{1}{" }
        assertLiteral()
    }

    @Test fun `math question native long press retains selection and does not edit`() {
        var edits = 0
        compose.setContent { MogeTheme {
            FollowUpNote(SolveItem.Question("q", "前文 \$\$x^2+y^2\$\$ 后文", emptyList(), "", false),
                onEdit = { edits++ }) { _, _ -> }
        } }
        compose.runOnIdle {
            val root = compose.activity.findViewById<ViewGroup>(android.R.id.content)
            val view = nativeMathViews(root).single()
            assertTrue(view.isTextSelectable)
            val span = nativeMathSpans(view).single()
            val text = view.text as Spannable
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            val line = view.layout.getLineForOffset(start)
            val x = view.layout.getPrimaryHorizontal(start) + 1f
            val y = (view.layout.getLineTop(line) + view.layout.getLineBottom(line)) / 2f
            var downTime = SystemClock.uptimeMillis()
            fun dispatch(action: Int) {
                val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
                try { view.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            dispatch(MotionEvent.ACTION_DOWN)
            dispatch(MotionEvent.ACTION_UP)
            assertEquals("An ordinary native text tap must edit exactly once", 1, edits)
            edits = 0
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
            downTime = SystemClock.uptimeMillis()
            dispatch(MotionEvent.ACTION_DOWN)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            dispatch(MotionEvent.ACTION_UP)
            assertEquals(0, edits)
            assertTrue("Native long press must select text", view.hasSelection())
            // Exact formula selection must also suppress the input's tap-to-edit listener.
            Selection.setSelection(text, start + 1, end - 1)
            view.performClick()
            assertEquals(0, edits)
            view.onTextContextMenuItem(android.R.id.copy)
            assertEquals("\$\$x^2+y^2\$\$", clipboard().primaryClip!!.getItemAt(0).text.toString())
        }
    }
}
