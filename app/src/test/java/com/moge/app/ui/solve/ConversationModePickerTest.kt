package com.moge.app.ui.solve

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.theme.MogeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationModePickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectionUpdatesGreetingAndRadioStateInBothThemes() {
        var mode by mutableStateOf(ConversationMode.SOLVE)
        var theme by mutableStateOf(Appearance.PAPER)
        compose.setContent { MogeTheme(theme) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                NewConversationPlaceholder(mode, true) { mode = it }
            }
        } }
        compose.onNodeWithText("解题").assertIsSelected()
        compose.onNodeWithText("对话").assertIsNotSelected().performClick()
        compose.onNodeWithText("今天聊点什么？").assertIsDisplayed()
        compose.onNodeWithText("对话").assertIsSelected()
        compose.runOnIdle { theme = Appearance.CHALK }
        compose.onNodeWithText("解题").performClick()
        compose.onNodeWithText("有什么题目要解？").assertIsDisplayed()
        compose.onNodeWithText("解题").assertIsSelected()
    }

    @Test fun submittingDisablesModeChange() {
        compose.setContent { MogeTheme { ConversationModePicker(ConversationMode.CHAT, false) { error("must not switch") } } }
        compose.onNodeWithText("解题").assertIsNotEnabled()
        compose.onNodeWithText("对话").assertIsSelected().assertIsNotEnabled()
    }
}
