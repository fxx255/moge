package com.moge.app.ui.markdown

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Full answers must survive LazyColumn premeasurement, detachment and repeated scrolling. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownScrollLayoutTest {
    @get:Rule val compose = createComposeRule()

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun assertFullFirstPlacement(answer: String, streaming: Boolean) {
        lateinit var root: View
        var firstHeight: Int? = null
        compose.setContent {
            root = LocalView.current
            MaterialTheme {
                LazyColumn(Modifier.width(300.dp).height(380.dp)) {
                    item {
                        Box(Modifier.onGloballyPositioned { if (firstHeight == null) firstHeight = it.size.height }) {
                            if (streaming) StreamingMarkdownChunk(StreamingMarkdownTokenizer().update(answer))
                            else MarkdownChunk(answer, null)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val host = textViews(root.rootView).single()
            assertEquals(answer, host.text.toString())
            println("FIRST_PLACEMENT streaming=$streaming first=$firstHeight native=${host.height} lines=${host.layout.lineCount}")
            assertTrue("Test answer must span several lines", host.layout.lineCount > 10)
            assertEquals("First placement used empty text height", host.height, firstHeight)
        }
    }

    @Test
    fun `first placement already reserves full answer height`() {
        val answer = (1..24).joinToString("\n\n") { "完整回答第 $it 段，首次显示与离屏返回时必须立即按完整内容测量。" }
        assertFullFirstPlacement(answer, streaming = false)
    }

    @Test
    fun `streaming tail is also populated before its first placement`() {
        assertFullFirstPlacement("Still generating a long answer with all text visible. ".repeat(72), streaming = true)
    }

    @Test
    fun `content growth shrink and width changes keep native and compose sizes aligned`() {
        lateinit var root: View
        var wide by mutableStateOf(false)
        val longAnswer = "A complete answer with many words that must wrap correctly at each actual width. ".repeat(72)
        var answer by mutableStateOf(longAnswer)
        var bubbleHeight = 0
        compose.setContent {
            root = LocalView.current
            MaterialTheme {
                LazyColumn(Modifier.width(if (wide) 480.dp else 300.dp).height(380.dp)) {
                    item {
                        Box(Modifier.onGloballyPositioned { bubbleHeight = it.size.height }) {
                            MarkdownChunk(answer, null)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        val original = textViews(root.rootView).single()
        val narrowLines = original.layout.lineCount
        fun assertLayout() = compose.runOnIdle {
            val host = textViews(root.rootView).single()
            assertSame("Reuse the same native View when content or width changes", original, host)
            assertEquals(answer.trimEnd(), host.text.toString())
            assertEquals(host.width - host.totalPaddingLeft - host.totalPaddingRight, host.layout.width)
            assertEquals(host.height, bubbleHeight)
            assertTrue(host.height >= host.layout.height)
            assertEquals(host.text.length, host.layout.getLineEnd(host.layout.lineCount - 1))
        }
        compose.runOnIdle { wide = true }
        compose.waitForIdle()
        assertLayout()
        assertTrue("Wider viewport must rewrap the full text", original.layout.lineCount < narrowLines)
        compose.runOnIdle { answer = "短答案。" }
        compose.waitForIdle()
        assertLayout()
        assertEquals(1, original.layout.lineCount)
        compose.runOnIdle { answer = longAnswer; wide = false }
        compose.waitForIdle()
        assertLayout()
        assertEquals(narrowLines, original.layout.lineCount)
    }

    @Test
    fun `two long answers retain full layouts through repeated offscreen scrolling`() {
        lateinit var root: View
        val heights = mutableMapOf<Int, Int>()
        val answers = listOf(
            (1..24).joinToString("\n\n") { "第一条回答第 $it 段：这是一段需要完整显示的解答，滚动后仍需保留全部文字。" },
            (1..24).joinToString("\n\n") { "Second answer paragraph $it: All words must remain visible after scrolling away and back." },
        )
        compose.setContent {
            root = LocalView.current
            MaterialTheme {
                LazyColumn(Modifier.width(300.dp).height(380.dp).testTag("answers")) {
                    itemsIndexed(listOf("question-0", "answer-0", "question-1", "answer-1"), key = { _, it -> it }) { index, _ ->
                        if (index % 2 == 0) Text("题目 ${index / 2}")
                        else Box(Modifier.onGloballyPositioned { heights[index / 2] = it.size.height }) {
                            MarkdownChunk(answers[index / 2], null)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        repeat(6) { cycle ->
            for (answerIndex in listOf(1, 0)) {
                compose.onNodeWithTag("answers").performScrollToIndex(answerIndex * 2 + 1)
                compose.waitForIdle()
                fun checkLayout(requireTarget: Boolean = false) = compose.runOnIdle {
                    val candidates = textViews(root.rootView)
                    if (requireTarget) assertTrue("Answer $answerIndex missing at cycle $cycle", candidates.any {
                        it.text.toString().startsWith(answers[answerIndex].substringBefore('\n'))
                    })
                    assertTrue("All answer views disappeared", candidates.isNotEmpty())
                    for (host in candidates) {
                        val actualIndex = answers.indexOfFirst { host.text.toString().startsWith(it.substringBefore('\n')) }
                        assertTrue("Unknown or truncated answer: ${host.text}", actualIndex >= 0)
                        assertTrue(host.text.toString(), host.text.toString().contains(answers[actualIndex].substringAfterLast('\n')))
                        val layout = requireNotNull(host.layout)
                        val diagnostic = "cycle=$cycle answer=$answerIndex width=${host.width} measured=${host.measuredHeight} " +
                            "height=${host.height} layout=${layout.width}x${layout.height} lines=${layout.lineCount} scroll=${host.scrollX},${host.scrollY}"
                        assertEquals(diagnostic, host.width - host.totalPaddingLeft - host.totalPaddingRight, layout.width)
                        assertEquals(diagnostic, host.text.length, layout.getLineEnd(layout.lineCount - 1))
                        assertTrue(diagnostic, layout.lineCount >= 24)
                        assertTrue(diagnostic, host.height >= layout.height)
                        assertEquals(diagnostic, host.height, heights[actualIndex])
                        assertEquals(diagnostic, 0, host.scrollX)
                        assertEquals(diagnostic, 0, host.scrollY)
                    }
                }
                checkLayout(requireTarget = true)
                compose.onNodeWithTag("answers").performTouchInput { swipeUp(durationMillis = 160) }
                compose.waitForIdle()
                checkLayout()
                compose.onNodeWithTag("answers").performTouchInput { swipeDown(durationMillis = 160) }
                compose.waitForIdle()
                checkLayout()
            }
        }
    }
}
