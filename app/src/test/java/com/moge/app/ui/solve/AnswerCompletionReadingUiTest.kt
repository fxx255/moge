package com.moge.app.ui.solve

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN-w393dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnswerCompletionReadingUiTest {
    @get:Rule val compose = createComposeRule()
    private val body = (0..65).joinToString("\n\n") {
        "正文第${it.toString().padStart(2, '0')}段：阅读内容 $it。这里继续说明第 $it 步的推导，以及这一步为什么成立。"
    }
    private val answer = mutableStateOf(SolveItem.Answer("a", AnswerState.STREAMING, body,
        reasoning = "草稿推理。".repeat(60)))
    private val extra = mutableStateOf<List<SolveItem>>(emptyList())
    private lateinit var root: View

    private fun native(view: View = root): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { native(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun render(answerFirst: Boolean = false, appearance: Appearance = Appearance.PAPER,
        viewport: ConversationViewport = ConversationViewport("a", 1, 1400)) {
        compose.setContent { MogeTheme(appearance) {
            root = LocalView.current.rootView
            SolveList(SolveUiState(conversationId = "c", answerFirst = answerFirst,
                items = listOf(SolveItem.Question("q", "原题", emptyList(), "", true), answer.value) + extra.value),
                {}, {}, { _, _ -> }, {}, {}, initialViewport = viewport,
                contentPadding = PaddingValues(top = 48.dp, bottom = 80.dp, start = 16.dp, end = 16.dp))
        } }
        compose.waitForIdle()
    }

    private data class Mark(val text: String, val y: Float)

    private fun mark(): Mark {
        val view = native().first { "正文第" in it.text }
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val line = view.layout.getLineForVertical((180 - location[1] - view.totalPaddingTop).coerceAtLeast(0))
        val offset = view.layout.getLineStart(line)
        assertTrue("must read inside the long answer", offset > 100)
        return Mark(view.text.substring(offset, (offset + 40).coerceAtMost(view.text.length)),
            location[1] + view.totalPaddingTop + view.layout.getLineTop(line).toFloat())
    }

    private fun y(mark: Mark): Float {
        val view = native().first { mark.text in it.text }
        val offset = view.text.indexOf(mark.text)
        val location = IntArray(2)
        view.getLocationInWindow(location)
        return location[1] + view.totalPaddingTop + view.layout.getLineTop(view.layout.getLineForOffset(offset)).toFloat()
    }

    private fun finish(state: AnswerState = AnswerState.COMPLETED, summary: String = "") {
        lateinit var before: Mark
        compose.runOnIdle { before = mark(); answer.value = answer.value.copy(state = state, reasoning = "", finalAnswer = summary) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("reading line moved on $state", before.y, y(before), 2f) }
        compose.onNodeWithTag("answer-scratch-pad").assertDoesNotExist()
    }

    @Test fun `removing collapsed draft keeps the visible line after completion`() {
        render()
        // End the navigation pin just as a real reader scrolling during streaming would.
        compose.onNodeWithTag("conversation-list").performTouchInput { swipeUp(durationMillis = 600) }
        finish()
    }

    @Test fun `removing expanded draft and prepending summary keeps the visible line`() {
        render()
        compose.onNodeWithTag("conversation-list").performScrollToIndex(1)
        compose.onNodeWithTag("answer-scratch-pad").performClick()
        repeat(3) { compose.onNodeWithTag("conversation-list").performTouchInput { swipeUp(durationMillis = 600) } }
        finish(summary = "这次回答的简明结论。".repeat(15))
    }

    @Test fun `restored reading position also survives completion in chalk theme`() {
        render(appearance = Appearance.CHALK)
        finish(summary = "简明答案：结论成立。")
    }

    @Test fun `interrupted stream preserves visible prose when scratch disappears`() {
        render()
        finish(state = AnswerState.FAILED)
    }

    @Test fun `summary refresh does not collapse an already expanded explanation`() {
        answer.value = answer.value.copy(finalAnswer = "初步结论。", reasoning = "")
        render(answerFirst = true)
        compose.onNodeWithText("展开解答").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-list").performTouchInput { swipeUp(durationMillis = 600) }
        finish(summary = "最终结论有所补充。")
        compose.onNodeWithText("收起解答").assertExists()
    }

    @Test fun `new question still takes precedence over finishing reading restoration`() {
        render()
        compose.runOnIdle {
            answer.value = answer.value.copy(state = AnswerState.COMPLETED, reasoning = "")
            extra.value = listOf(SolveItem.Question("next", "本次新问题", emptyList(), "", false))
        }
        compose.waitForIdle()
        compose.onNodeWithText("本次新问题").assertIsDisplayed()
    }

    @Test fun `visible header is not scrolled to pin prose below it`() {
        render(viewport = ConversationViewport("a", 1, 0))
        val before = compose.onNodeWithTag("answer-header").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { answer.value = answer.value.copy(state = AnswerState.COMPLETED, reasoning = "", finalAnswer = "结论。".repeat(30)) }
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("answer-header").fetchSemanticsNode().boundsInRoot.top, 1f)
    }
}
