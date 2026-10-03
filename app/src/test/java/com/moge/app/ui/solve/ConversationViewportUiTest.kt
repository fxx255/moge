package com.moge.app.ui.solve

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalView
import com.moge.app.data.prefs.Appearance
import java.io.File
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
class ConversationViewportUiTest {
    @get:Rule val compose = createComposeRule()
    private val questions = (0..30).map { index ->
        SolveItem.Question("q$index", "第 $index 个问题：" + "这是需要保留阅读位置的题目。".repeat(24), emptyList(), "", false)
    }

    @Test fun `destroying page then asynchronously loading history restores exact message and pixel offset`() {
        val shown = mutableStateOf(true)
        val items = mutableStateOf<List<SolveItem>>(questions)
        var saved: ConversationViewport? = null
        compose.setContent { MogeTheme {
            if (shown.value) SolveList(SolveUiState(conversationId = "conversation", items = items.value), {}, {}, { _, _ -> }, {}, {},
                initialViewport = saved, saveViewport = { saved = it }, contentPadding = PaddingValues(16.dp))
        } }
        compose.onNodeWithTag("conversation-list").performScrollToIndex(12)
        compose.onNodeWithTag("conversation-list").performTouchInput {
            swipe(center + Offset(0f, 100f), center - Offset(0f, 70f), durationMillis = 500)
        }
        compose.waitForIdle()
        val before = requireNotNull(saved)
        assertTrue(before.index in 10..20)
        assertTrue(before.offset > 0)
        compose.runOnIdle { shown.value = false }
        compose.waitForIdle()
        compose.runOnIdle { items.value = emptyList(); shown.value = true }
        compose.waitForIdle()
        assertEquals(before, saved)
        compose.runOnIdle { items.value = questions }
        compose.waitForIdle()
        assertEquals(before, saved)
    }

    @Test fun `answer refresh does not scroll reader while new question still appears at bottom`() {
        val items = mutableStateOf<List<SolveItem>>(questions)
        var saved: ConversationViewport? = null
        compose.setContent { MogeTheme {
            SolveList(SolveUiState(conversationId = "conversation", items = items.value), {}, {}, { _, _ -> }, {}, {},
                initialViewport = ConversationViewport("q12", 12, 101), saveViewport = { saved = it })
        } }
        compose.waitForIdle()
        val before = requireNotNull(saved)
        compose.runOnIdle { items.value = questions + SolveItem.Answer("a", AnswerState.COMPLETED, "更新后的回答") }
        compose.waitForIdle()
        assertEquals(before, saved)
        compose.runOnIdle { items.value = items.value + SolveItem.Question("new", "本次新问题", emptyList(), "", false) }
        compose.waitForIdle()
        assertTrue(requireNotNull(saved).index > before.index)
    }

    @Test fun `list extends beneath header and floating composer and last question remains reachable`() {
        var view: View? = null
        compose.setContent { MogeTheme(Appearance.CHALK) {
            view = LocalView.current
            ConversationScaffold("标题", {}, {}, footer = {
                FollowUpBar(SolveUiState(), {}, {}, {}, {}, {}, {}, {})
            }) { padding ->
                SolveList(SolveUiState(items = questions), {}, {}, { _, _ -> }, {}, {}, contentPadding = padding)
            }
        } }
        val list = compose.onNodeWithTag("conversation-list").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("conversation-composer").fetchSemanticsNode().boundsInRoot
        assertTrue(list.top < header.bottom)
        assertTrue(list.bottom > footer.top)
        compose.onNodeWithTag("conversation-list").performScrollToIndex(30)
        compose.onNodeWithText(questions.last().text).assertIsDisplayed()
        compose.runOnIdle {
            val root = requireNotNull(view).rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val output = File("build/ui-refinement-previews/conversation.png")
                output.parentFile?.mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
}
