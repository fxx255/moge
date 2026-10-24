package com.moge.app.ui.solve

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.domain.ConversationMode
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        val header = compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { click(Offset(header.center.x, header.top / 2)) }
        compose.runOnIdle { assertEquals(0, photoClicks) }
        compose.onNodeWithContentDescription("设置").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, settingsClicks); assertEquals(0, photoClicks) }
        compose.onNodeWithTag("covered-photo").performTouchInput { click(Offset(centerX, height * .75f)) }
        compose.runOnIdle { assertEquals(1, photoClicks) }
    }

    @Test fun `upper gap shows theme background with answers clipped below the strip in both themes and states`() {
        var appearance by mutableStateOf(Appearance.PAPER)
        var existing by mutableStateOf(false)
        lateinit var view: View
        var background = 0
        compose.setContent { MogeTheme(appearance) {
            view = LocalView.current
            background = androidx.compose.material3.MaterialTheme.colorScheme.background.toArgb()
            ConversationScaffold("已有对话", null, {}, footer = {},
                newConversationMode = ConversationMode.SOLVE.takeUnless { existing }) {
                Box(Modifier.fillMaxSize().background(Color.Magenta))
            }
        } }
        listOf(Appearance.PAPER, Appearance.CHALK).forEach { theme ->
            listOf(false, true).forEach { saved ->
                compose.runOnIdle { appearance = theme; existing = saved }
                val header = compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
                compose.runOnIdle {
                    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(Canvas(bitmap))
                        listOf(.13f, .47f, .83f).forEach { fraction ->
                            assertEquals("The upper gap must retain the theme background: $theme, saved=$saved",
                                background, bitmap.getPixel((header.width * fraction).toInt(), (header.top / 2).toInt()))
                        }
                        assertEquals("The answer must remain visible below the strip", Color.Magenta.toArgb(),
                            bitmap.getPixel(header.center.x.toInt(), (header.bottom + 40).toInt()))
                    } finally { bitmap.recycle() }
                }
            }
        }
    }
}
