package com.moge.app.ui.home

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 取景页降级：拒绝相机权限后给「去授权」和「系统相机」两个入口；
 * CameraX 打不开（部分 ROM 黑屏）时只给「系统相机」。相册与返回对话入口仍可使用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CameraFallbackTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `denied permission offers grant and system camera`() {
        var grants = 0
        var systemShots = 0
        compose.setContent {
            MogeTheme {
                CameraUnavailable(cameraFailed = false, onGrant = { grants++ }, onSystemCamera = { systemShots++ })
            }
        }
        compose.onNodeWithText("没有相机权限").assertExists()
        compose.onNodeWithText("去授权相机").performClick()
        compose.onNodeWithText("用系统相机拍").performClick()
        assertEquals(1, grants)
        assertEquals(1, systemShots)
    }

    @Test
    fun `broken camera only offers the system camera`() {
        var systemShots = 0
        compose.setContent {
            MogeTheme {
                CameraUnavailable(cameraFailed = true, onGrant = {}, onSystemCamera = { systemShots++ })
            }
        }
        compose.onNodeWithText("取景框打不开").assertExists()
        compose.onNodeWithText("去授权相机").assertDoesNotExist()
        compose.onNodeWithText("用系统相机拍").performClick()
        assertEquals(1, systemShots)
    }
}
