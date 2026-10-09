package com.moge.app.ui.settings

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.prefs.UserSettings
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HistoryRetentionSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun `disabled switch locks duration editor without losing saved duration`() {
        var prefs by mutableStateOf(UserSettings(historyRetentionDays = 30))
        compose.setContent {
            MogeTheme(Appearance.CHALK) {
                Column { HistoryRetentionControls(prefs,
                    onAutoCleanup = { prefs = prefs.copy(historyAutoCleanupEnabled = it) },
                    onRetentionDays = { prefs = prefs.copy(historyRetentionDays = it) }) }
            }
        }
        compose.onNodeWithText("自动清理历史对话").assertIsOn().performClick()
        compose.onNodeWithText("自动清理历史对话").assertIsOff()
        compose.onNodeWithText("30 天").assertIsNotEnabled()
        compose.onNodeWithText("自动清理历史对话").performClick()
        compose.onNodeWithText("30 天").assertIsEnabled()
        compose.runOnIdle { assertEquals(30, prefs.historyRetentionDays) }
    }

    @Test fun `custom duration validates bounds and cancel does not change policy`() {
        var prefs by mutableStateOf(UserSettings())
        compose.setContent {
            MogeTheme(Appearance.PAPER) {
                Column { HistoryRetentionControls(prefs, onAutoCleanup = {}, onRetentionDays = { prefs = prefs.copy(historyRetentionDays = it) }) }
            }
        }
        compose.onNodeWithText("7 天").performClick()
        compose.onNodeWithText("保留天数").performTextReplacement("0")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("保留天数").performTextReplacement("366")
        compose.onNodeWithText("保存").assertIsNotEnabled()
        compose.onNodeWithText("保留天数").performTextReplacement("45")
        compose.onNodeWithText("保存").assertIsEnabled().performClick()
        compose.onNodeWithText("45 天").performClick()
        compose.onNodeWithText("保留天数").performTextReplacement("3")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("45 天").assertIsEnabled()
        compose.runOnIdle { assertEquals(45, prefs.historyRetentionDays) }
    }
}
