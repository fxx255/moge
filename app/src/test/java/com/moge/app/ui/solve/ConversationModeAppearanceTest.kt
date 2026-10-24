package com.moge.app.ui.solve

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationModeAppearanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paperChatKeepsOnlyHeaderGreetingAndComposer() = preview(Appearance.PAPER, "chat-paper")
    @Test fun chalkChatKeepsOnlyHeaderGreetingAndComposer() = preview(Appearance.CHALK, "chat-chalk")
    @Test fun largeFontKeepsTitleSwitchAndComposerReachable() = preview(Appearance.PAPER, "chat-large-font", 1.6f)

    @Test
    @Config(qualifiers = "w698dp-h360dp-land")
    fun landscapeAttachmentsKeepModeControlsAboveComposer() {
        compose.setContent { MogeTheme(Appearance.CHALK) {
            val state = SolveUiState(conversationMode = ConversationMode.CHAT, photos = listOf("/one", "/two", "/three"))
            ConversationScaffold("", null, newConversationMode = state.conversationMode,
                actions = { ConversationActions(false, {}, {}, {}) }, footer = {
                FollowUpBar(state, {}, {}, {}, {}, {}, {}, {})
            }) { padding -> SolveList(state, {}, {}, { _, _ -> }, {}, {}, contentPadding = padding) }
        } }
        compose.onNodeWithText("新对话").assertIsDisplayed()
        compose.onNodeWithContentDescription("对话模式").assertIsDisplayed()
        compose.onNodeWithContentDescription("设置").assertIsDisplayed()
        compose.onNodeWithContentDescription("历史对话").assertIsDisplayed()
        val picker = compose.onNodeWithTag("conversation-mode-switch").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("conversation-composer").fetchSemanticsNode().boundsInRoot
        assertTrue("Mode controls must fit above the attachment composer", picker.bottom <= composer.top)
        val label = compose.onNodeWithText("新对话").fetchSemanticsNode().boundsInRoot
        assertTrue("The label must retain its full line height", label.height >= 18f)
    }

    private fun preview(appearance: Appearance, name: String, fontScale: Float = 1f) {
        var view: View? = null
        var mode by mutableStateOf(ConversationMode.CHAT)
        compose.setContent { MogeTheme(appearance) {
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                val state = SolveUiState(conversationMode = mode)
                ConversationScaffold("", null, newConversationMode = mode, onSelectMode = { mode = it },
                    actions = { ConversationActions(false, {}, {}, {}) }, footer = {
                    FollowUpBar(state, {}, {}, {}, {}, {}, {}, {})
                }) { padding -> SolveList(state, {}, {}, { _, _ -> }, {}, {}, contentPadding = padding) }
            }
        } }
        compose.onNodeWithText("今天聊点什么？").assertIsDisplayed()
        compose.onNodeWithText("新对话").assertIsDisplayed()
        compose.onNodeWithTag("conversation-mode-switch").assertIsEnabled()
        compose.onNodeWithText("解题").assertDoesNotExist()
        compose.onNodeWithText("对话").assertDoesNotExist()
        compose.onNodeWithText("发送消息").assertIsDisplayed()
        compose.onNodeWithText("输入问题，或拍照、选图开始对话").assertDoesNotExist()
        compose.runOnIdle {
            val root = requireNotNull(view).rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val output = File("build/ui-preview/$name.png")
                output.parentFile?.mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        compose.onNodeWithTag("conversation-mode-switch").performClick()
        compose.onNodeWithText("新题目").assertIsDisplayed()
        compose.onNodeWithContentDescription("解题模式").assertIsDisplayed()
        compose.onNodeWithText("有什么题目要解？").assertIsDisplayed()
        compose.onNodeWithText("输入问题").assertIsDisplayed()
    }

    @Test fun chatStreamsBodyEvenWhenSolvingAnswerFirstPreferenceIsEnabled() {
        var view: View? = null
        val state = SolveUiState(conversationId = "chat", conversationMode = ConversationMode.CHAT,
            answerFirst = true, generating = true, items = listOf(
                SolveItem.Question("q", "你好", emptyList(), "", true),
                SolveItem.Answer("a", AnswerState.STREAMING, "正文已经开始输出。")))
        compose.setContent { MogeTheme {
            view = LocalView.current
            SolveList(state, {}, {}, { _, _ -> }, {}, {})
        } }
        compose.onNodeWithText("题目").assertDoesNotExist()
        compose.onNodeWithText("回答").assertIsDisplayed()
        compose.onNodeWithText("正在生成答案…").assertDoesNotExist()
        compose.runOnIdle {
            fun containsBody(node: View): Boolean = when (node) {
                is TextView -> node.text.contains("正文已经开始输出")
                is ViewGroup -> (0 until node.childCount).any { containsBody(node.getChildAt(it)) }
                else -> false
            }
            assertTrue(containsBody(requireNotNull(view).rootView))
        }
    }
}
