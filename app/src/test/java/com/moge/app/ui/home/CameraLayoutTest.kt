package com.moge.app.ui.home

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.moge.app.ui.capture.ViewfinderController
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w1000dp-h1000dp", application = Application::class)
class CameraLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `rotation and short windows move controls without recreating the preview`() {
        val window = mutableStateOf(400.dp to 800.dp)
        var mounts = 0
        var disposals = 0
        var sideControls = false
        compose.setContent {
            CameraLayout(
                modifier = Modifier.requiredSize(window.value.first, window.value.second),
                preview = {
                    DisposableEffect(Unit) { mounts++; onDispose { disposals++ } }
                    Box(Modifier.fillMaxSize().testTag("preview"))
                },
                controls = { side ->
                    sideControls = side
                    Box(Modifier.fillMaxSize().testTag("controls"))
                },
            )
        }
        val portraitPreview = compose.onNodeWithTag("preview").fetchSemanticsNode().boundsInRoot
        val portraitControls = compose.onNodeWithTag("controls").fetchSemanticsNode().boundsInRoot
        assertEquals(portraitPreview.bottom, portraitControls.top, 0.1f)
        assertTrue(!sideControls)

        compose.runOnIdle { window.value = 800.dp to 360.dp }
        val landscapePreview = compose.onNodeWithTag("preview").fetchSemanticsNode().boundsInRoot
        val landscapeControls = compose.onNodeWithTag("controls").fetchSemanticsNode().boundsInRoot
        assertEquals(landscapePreview.right, landscapeControls.left, 0.1f)
        assertEquals(landscapePreview.height, landscapeControls.height, 0.1f)
        assertTrue(landscapePreview.width > landscapeControls.width * 3)
        assertTrue(sideControls)

        compose.runOnIdle { window.value = 400.dp to 420.dp }
        compose.waitForIdle()
        assertTrue("矮分屏窗口也采用侧边操作区", sideControls)
        assertEquals(1, mounts)
        assertEquals(0, disposals)
    }

    @Test
    fun `shutter is an accessible click target without the old text stamp`() {
        var shots = 0
        val enabled = mutableStateOf(true)
        compose.setContent { MogeTheme { CameraShutter(enabled.value, Color.Black) { shots++ } } }
        compose.onNodeWithContentDescription("拍照").assertHasClickAction().assertIsEnabled().performClick()
        compose.onNodeWithText("拍").assertDoesNotExist()
        assertEquals(1, shots)
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithContentDescription("拍照").assertIsNotEnabled()
    }

    @Test
    fun `side controls expose gallery flash shutter and photo confirmation`() {
        var gallery = 0
        var confirmation = 0
        val controller = ViewfinderController().apply { ready = true }
        compose.setContent {
            MogeTheme {
                Box(Modifier.requiredSize(120.dp, 360.dp)) {
                    CameraControls(
                        count = 2, controller = controller, onDark = true, sideControls = true,
                        levelEnabled = false, onLevel = {}, shutterEnabled = true,
                        onGallery = { gallery++ }, onShutter = {}, onOpenStack = { confirmation++ },
                    )
                }
            }
        }
        compose.onNodeWithContentDescription("相册").performClick()
        compose.onNodeWithContentDescription("拍照").assertIsEnabled()
        compose.onNodeWithContentDescription("打开闪光灯").performClick()
        compose.onNodeWithContentDescription("关闭闪光灯").assertExists()
        compose.onNodeWithContentDescription("查看已选照片").performClick()
        assertEquals(1, gallery)
        assertEquals(1, confirmation)
        compose.onNodeWithText("详细解答").assertDoesNotExist()
        compose.onNodeWithText("举一反三").assertDoesNotExist()
    }
}
