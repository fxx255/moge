package com.moge.app.ui.solve

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConversationHeaderInputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `header blocks covered photo taps while title buttons and visible photo remain usable`() {
        var photoClicks = 0
        var settingsClicks = 0
        compose.setContent { MogeTheme {
            ConversationScaffold("随机变量变换", {}, conversationMode = ConversationMode.SOLVE,
                actions = { IconButton(onClick = { settingsClicks++ }) { Icon(Icons.Outlined.Settings, "设置") } },
                footer = {}) {
                Box(Modifier.fillMaxSize().testTag("covered-photo").clickable { photoClicks++ })
            }
        } }
        compose.onNodeWithTag("conversation-header").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, photoClicks) }
        compose.onNodeWithContentDescription("设置").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, settingsClicks); assertEquals(0, photoClicks) }
        compose.onNodeWithTag("covered-photo").performTouchInput { click(Offset(centerX, height * .75f)) }
        compose.runOnIdle { assertEquals(1, photoClicks) }
    }
}
