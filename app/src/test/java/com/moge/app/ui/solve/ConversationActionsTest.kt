package com.moge.app.ui.solve

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationActionsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `creating a conversation swaps history for new chat without hiding settings`() {
        var existing by mutableStateOf(false)
        var newChats = 0
        var history = 0
        var settings = 0
        compose.setContent {
            MogeTheme { ConversationActions(existing, { newChats++ }, { history++ }, { settings++ }) }
        }
        compose.onNodeWithContentDescription("历史对话").performClick()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithContentDescription("新对话").assertDoesNotExist()
        assertEquals(1, history)
        compose.runOnIdle { existing = true }
        compose.onNodeWithContentDescription("历史对话").assertDoesNotExist()
        compose.onNodeWithContentDescription("新对话").performClick()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithContentDescription("对话菜单").assertDoesNotExist()
        assertEquals(1, newChats)
        assertEquals(2, settings)
    }
}
