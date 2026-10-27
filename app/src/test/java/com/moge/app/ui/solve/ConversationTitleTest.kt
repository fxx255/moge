package com.moge.app.ui.solve

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationTitleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun titleIsTheOnlyModeControlAndUpdatesGreetingInBothThemes() {
        var mode by mutableStateOf(ConversationMode.SOLVE)
        var theme by mutableStateOf(Appearance.PAPER)
        compose.setContent { MogeTheme(theme) {
            val state = SolveUiState(conversationMode = mode)
            ConversationScaffold("", null, newConversationMode = mode, onSelectMode = { mode = it },
                actions = {}, footer = {}) { padding ->
                SolveList(state, {}, {}, { _, _ -> }, {}, {}, contentPadding = padding)
            }
        } }
        compose.onNodeWithText("新题目").assertIsDisplayed().performClick()
        compose.onNodeWithText("新对话").assertIsDisplayed()
        compose.onNodeWithText("今天聊点什么？").assertIsDisplayed()
        compose.onNodeWithTag("conversation-mode-switch").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "对话模式"))
        compose.onNodeWithText("解题").assertDoesNotExist()
        compose.onNodeWithText("对话").assertDoesNotExist()
        compose.runOnIdle { theme = Appearance.CHALK }
        compose.onNodeWithText("新对话").performClick()
        compose.onNodeWithText("新题目").assertIsDisplayed()
        compose.onNodeWithText("有什么题目要解？").assertIsDisplayed()
    }

    @Test fun loadingDraftOrSubmittingDisablesModeChange() {
        compose.setContent { MogeTheme {
            ConversationTitle("", ConversationMode.CHAT, false) { error("must not switch") }
        } }
        compose.onNodeWithTag("conversation-mode-switch").assertIsNotEnabled()
        compose.onNodeWithText("新对话").assertIsDisplayed()
    }

    @Test fun modeSwitchIncludesIconAndKeepsBackAndDisabledStatesIndependent() {
        var mode by mutableStateOf(ConversationMode.SOLVE)
        var enabled by mutableStateOf(true)
        var theme by mutableStateOf(Appearance.PAPER)
        var backs = 0
        compose.setContent { MogeTheme(theme) {
            ConversationScaffold("", { backs++ }, {}, {}, newConversationMode = mode,
                modeSelectable = enabled, onSelectMode = { mode = it }) {}
        } }
        for (appearance in listOf(Appearance.PAPER, Appearance.CHALK)) {
            compose.runOnIdle { theme = appearance }
            compose.onNodeWithTag("conversation-mode-icon", useUnmergedTree = true).performTouchInput { click() }
            compose.onNodeWithText("新对话").assertIsDisplayed()
            compose.onNodeWithTag("conversation-mode-switch").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "对话模式"))
            compose.runOnIdle { enabled = false }
            compose.onNodeWithTag("conversation-mode-icon", useUnmergedTree = true).performTouchInput { click() }
            assertEquals(ConversationMode.CHAT, mode)
            compose.runOnIdle { enabled = true }
            compose.onNodeWithText("新对话").performClick()
            assertEquals(ConversationMode.SOLVE, mode)
        }
        assertEquals(0, backs)
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1, backs)
    }

    @Test fun rapidSwitchingEndsOnTheLatestMode() {
        var mode by mutableStateOf(ConversationMode.SOLVE)
        compose.setContent { MogeTheme { ConversationTitle("", mode, onSelectMode = { mode = it }) } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("conversation-mode-switch").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("conversation-mode-switch").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("新题目").assertIsDisplayed()
        compose.onNodeWithText("新对话").assertDoesNotExist()
        assertEquals(ConversationMode.SOLVE, mode)
    }

    @Test fun titleChangesKeepFullAccessibleTextAndActionsAnchoredWithMotionDisabled() {
        var title by mutableStateOf("图片题目")
        compose.setContent { MogeTheme {
            CompositionLocalProvider(LocalPaperMotionEnabled provides false) {
                ConversationScaffold(title, null,
                    actions = { ConversationActions(false, {}, {}, {}) }, footer = {}) {}
            }
        } }
        val before = compose.onNodeWithContentDescription("设置").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { title = "一道关于定积分的题目" }
        compose.onNodeWithText("一道关于定积分的题目").assertIsDisplayed()
        compose.onNodeWithText("图片题目").assertDoesNotExist()
        compose.onNodeWithTag("conversation-mode-switch").assertDoesNotExist()
        assertEquals(before, compose.onNodeWithContentDescription("设置").fetchSemanticsNode().boundsInRoot)
    }
}
