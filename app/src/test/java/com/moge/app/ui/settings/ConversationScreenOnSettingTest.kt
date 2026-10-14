package com.moge.app.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.prefs.UserSettings
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationScreenOnSettingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun `one accessible row toggles the default off setting in both themes`() {
        var checked by mutableStateOf(UserSettings().keepScreenOnInConversation)
        var theme by mutableStateOf(Appearance.PAPER)
        compose.setContent { MogeTheme(theme) { ConversationScreenOnSetting(checked) { checked = it } } }
        compose.onNodeWithText("对话中屏幕常亮").assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(checked); theme = Appearance.CHALK }
        compose.onNodeWithText("对话中屏幕常亮").assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { assertFalse(checked) }
    }
}
