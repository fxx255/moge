package com.moge.app.ui.solve

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.moge.app.data.prefs.Appearance
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
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnswerSelectionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val summary = "判卷概况：第（4）问正确，按常见分值约 6/14。"
    private val explanation = "这题的关键是两个匹配滤波器的能量为 1。\n\n先统一基本量。"

    @Test fun `end handle crosses from summary into explanation on paper`() = verify(false, Appearance.PAPER)
    @Test fun `start handle crosses from explanation into summary on blackboard`() = verify(true, Appearance.CHALK)

    private fun verify(upward: Boolean, appearance: Appearance) {
        compose.setContent { MogeTheme(appearance) {
            SolveList(SolveUiState(conversationId = "selection", items = listOf(
                SolveItem.Answer("answer", AnswerState.COMPLETED, explanation, finalAnswer = summary))),
                {}, {}, { _, _ -> }, {}, {})
        } }
        compose.waitForIdle()
        compose.runOnIdle {
            val body = views(compose.activity.window.decorView).single { it.text.contains("判卷概况") }
            assertTrue("One reply must allow selection across summary and explanation", body.text.contains("这题的关键"))
            val split = body.text.indexOf("这题的关键")
            val controller = requireNotNull(markdownSelectionController(body))
            val from = if (upward) split + 3 else 0
            val to = if (upward) split + 8 else 4
            controller.begin(from, to)
            val initial = requireNotNull(selectionEndpoint(body, if (upward) from else to, upward))
            val position = IntArray(2).also(body::getLocationOnScreen)
            controller.beginDrag(upward, position[0] + initial.x, position[1] + initial.bottom + 8f)
            val targetOffset = if (upward) 1 else split + 10
            val target = requireNotNull(selectionEndpoint(body, targetOffset, upward))
            controller.moveDrag(position[0] + target.x,
                position[1] + target.centerY + initial.bottom - initial.centerY + 8f)
            controller.endDrag()
            assertEquals(if (upward) targetOffset else from, body.selectionStart)
            assertEquals(if (upward) to else targetOffset, body.selectionEnd)
            val expected = body.text.subSequence(body.selectionStart, body.selectionEnd).toString()
            body.onTextContextMenuItem(android.R.id.copy)
            val clipboard = body.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
            assertTrue(expected.contains("6/14。\n\n这题"))
        }
    }

    @Test fun `streaming summary and explanation remain one reading surface on completion`() {
        val state = mutableStateOf(AnswerState.STREAMING)
        compose.setContent { MogeTheme {
            SolveList(SolveUiState(conversationId = "selection", items = listOf(
                SolveItem.Answer("answer", state.value, explanation, finalAnswer = summary))),
                {}, {}, { _, _ -> }, {}, {})
        } }
        compose.waitForIdle()
        lateinit var original: TextView
        compose.runOnIdle {
            original = views(compose.activity.window.decorView).single { it.text.contains("判卷概况") }
            assertTrue(original.text.contains("这题的关键"))
            state.value = AnswerState.COMPLETED
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val completed = views(compose.activity.window.decorView).single { it.text.contains("判卷概况") }
            assertSame("Completion must preserve the native reading view", original, completed)
            assertTrue(completed.text.contains("这题的关键"))
        }
    }

    private fun views(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
        else -> emptyList()
    }
}
