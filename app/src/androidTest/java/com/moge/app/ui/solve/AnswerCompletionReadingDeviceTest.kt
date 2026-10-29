package com.moge.app.ui.solve

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4



@RunWith(AndroidJUnit4::class)


class AnswerCompletionReadingDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
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

    private fun render(answerFirst: Boolean = false, appearance: Appearance = Appearance.PAPER) {
        compose.setContent { MogeTheme(appearance) {
            root = LocalView.current.rootView
            SolveList(SolveUiState(conversationId = "c", answerFirst = answerFirst,
                items = listOf(SolveItem.Question("q", "原题", emptyList(), "", true), answer.value) + extra.value),
                {}, {}, { _, _ -> }, {}, {}, initialViewport = ConversationViewport("a", 1, 1400),
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

    private fun finish(state: AnswerState = AnswerState.COMPLETED, summary: String = "",
        paths: List<String> = answer.value.figurePaths) {
        lateinit var before: Mark
        val frames = mutableListOf<Float>()
        var checking = true
        val listener = ViewTreeObserver.OnDrawListener {
            // Native AndroidView placement happens during drawing; sample after that frame.
            root.post { if (checking) frames += y(before) }
        }
        compose.runOnIdle {
            before = mark()
            root.viewTreeObserver.addOnDrawListener(listener)
            answer.value = answer.value.copy(state = state, reasoning = "", finalAnswer = summary, figurePaths = paths)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            checking = false
            root.viewTreeObserver.removeOnDrawListener(listener)
            assertEquals("reading line moved on $state", before.y, y(before), 2f)
            assertTrue("must observe completion frames", frames.isNotEmpty())
            frames.forEach { assertEquals("intermediate completion frame jumped", before.y, it, 2f) }
        }
        compose.onNodeWithTag("answer-scratch-pad").assertDoesNotExist()
    }

    @Test fun collapsedDraftRemovalKeepsVisibleLineAfterCompletion() {
        render()
        // End the navigation pin just as a real reader scrolling during streaming would.
        compose.onNodeWithTag("conversation-list").performTouchInput { swipeUp(durationMillis = 600) }
        finish()
    }

    @Test fun expandedDraftRemovalAndSummaryInsertionKeepVisibleLine() {
        render()
        compose.onNodeWithTag("conversation-list").performScrollToIndex(1)
        compose.onNodeWithTag("answer-scratch-pad").performClick()
        repeat(3) { compose.onNodeWithTag("conversation-list").performTouchInput { swipeUp(durationMillis = 600) } }
        finish(summary = "这次回答的简明结论。".repeat(15))
    }

    @Test fun restoredPositionSurvivesCompletionInChalkTheme() {
        render(appearance = Appearance.CHALK)
        finish(summary = "简明答案：结论成立。")
    }

    @Test fun interruptedStreamPreservesVisibleProse() {
        render()
        finish(state = AnswerState.FAILED)
    }

    @Test fun summaryRefreshKeepsExplanationExpanded() {
        answer.value = answer.value.copy(finalAnswer = "初步结论。", reasoning = "")
        render(answerFirst = true)
        compose.onNodeWithText("展开解答").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("conversation-list").performTouchInput { swipeUp(durationMillis = 600) }
        finish(summary = "最终结论有所补充。")
        compose.onNodeWithText("收起解答").assertExists()
    }

    @Test fun newQuestionTakesPrecedenceOverReadingRestoration() {
        render()
        compose.runOnIdle {
            answer.value = answer.value.copy(state = AnswerState.COMPLETED, reasoning = "")
            extra.value = listOf(SolveItem.Question("next", "本次新问题", emptyList(), "", false))
        }
        compose.waitForIdle()
        compose.onNodeWithText("本次新问题").assertIsDisplayed()
    }

    @Test fun finishingDuringDragAllowsContinuedBrowsing() {
        render()
        val list = compose.onNodeWithTag("conversation-list")
        list.performTouchInput {
            down(center)
            moveBy(Offset(0f, -100f), delayMillis = 32)
        }
        finish(summary = "回答的最终结论。".repeat(8))
        lateinit var before: Mark
        compose.runOnIdle { before = mark() }
        list.performTouchInput {
            moveBy(Offset(0f, -110f), delayMillis = 200)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("drag should continue after completion", y(before) < before.y - 60f) }
    }

    @Test fun tallFormulaSummaryKeepsBodyInPlace() {
        render()
        finish(summary = "简明答案如下：\n\n$$\n\\begin{pmatrix}1&0&1\\\\0&1&1\\\\1&1&0\\end{pmatrix}\n$$\n\n以上是最终结论。")
    }

    @Test fun figureLoadingAboveVisibleProseKeepsReadingPosition() {
        answer.value = answer.value.copy(text = "图示如下：\n\n[[FIGURE:1]]\n\n$body")
        val file = File(compose.activity.cacheDir, "reading-position-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(400, 900, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        render()
        finish(paths = listOf(file.absolutePath))
    }

    @Test fun summaryInsertionKeepsTheVisibleTableRowInPlace() {
        answer.value = answer.value.copy(text = "| 序号 | 内容 |\n| --- | --- |\n" +
            (0..80).joinToString("\n") { "| | 表格行 ${it.toString().padStart(2, '0')} 的说明 |" } + "\n\n表格结束。")
        render()
        var text = ""
        var beforeY = 0
        fun top(view: TextView): Int = IntArray(2).also(view::getLocationInWindow)[1]
        compose.runOnIdle {
            val cell = native().first { it.text.startsWith("表格行") && top(it) in 180..350 }
            text = cell.text.toString()
            beforeY = top(cell)
            answer.value = answer.value.copy(state = AnswerState.COMPLETED, reasoning = "", finalAnswer = "最终结论。".repeat(20))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("visible table row moved", beforeY, top(native().first { it.text.toString() == text }))
        }
    }
}
