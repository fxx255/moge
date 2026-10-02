package com.moge.app.ui.capture

import android.app.Application
import android.view.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/** 相机目标方向 → 操作图标角度的纯函数：四档方向、最短路径、动画落点。 */
class CameraOrientationMotionTest {
    @Test
    fun `surface rotation maps to a clockwise icon angle`() {
        assertEquals(0f, rotationAngleDegrees(Surface.ROTATION_0), 0.001f)
        assertEquals(90f, rotationAngleDegrees(Surface.ROTATION_90), 0.001f)
        assertEquals(180f, rotationAngleDegrees(Surface.ROTATION_180), 0.001f)
        assertEquals(270f, rotationAngleDegrees(Surface.ROTATION_270), 0.001f)
    }

    @Test
    fun `shortest delta never turns more than half a circle`() {
        assertEquals(90f, shortestAngleDelta(0f, 90f), 0.001f)
        assertEquals(-90f, shortestAngleDelta(0f, 270f), 0.001f)
        assertEquals(90f, shortestAngleDelta(270f, 0f), 0.001f)
        // 跨 0° 时抄近路，而不是退回 350°。
        assertEquals(10f, shortestAngleDelta(350f, 0f), 0.001f)
        assertEquals(-10f, shortestAngleDelta(0f, 350f), 0.001f)
        // 任意两档方向之间都不超过半圈。
        listOf(0f, 90f, 180f, 270f).forEach { from ->
            listOf(0f, 90f, 180f, 270f).forEach { to ->
                assertTrue("$from° -> $to°", abs(shortestAngleDelta(from, to)) <= 180.001f)
            }
        }
    }

    @Test
    fun `locked window compensates while auto rotate stays upright`() {
        // 自动旋转打开：窗口方向 == 相机目标方向 → 图标保持正向。
        listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270).forEach {
            assertEquals("display=$it", 0f, cameraIconRotationDegrees(it, it), 0.001f)
        }
        // 锁定竖屏：两侧横屏与倒置分别得到 -90° / 180° / 90°。
        assertEquals(-90f, cameraIconRotationDegrees(Surface.ROTATION_270, Surface.ROTATION_0), 0.001f)
        assertEquals(180f, cameraIconRotationDegrees(Surface.ROTATION_180, Surface.ROTATION_0), 0.001f)
        assertEquals(90f, cameraIconRotationDegrees(Surface.ROTATION_90, Surface.ROTATION_0), 0.001f)
        // 锁定横屏：竖屏手持时反向补 90°，仍走最短路径。
        assertEquals(90f, cameraIconRotationDegrees(Surface.ROTATION_0, Surface.ROTATION_270), 0.001f)
        assertEquals(-90f, cameraIconRotationDegrees(Surface.ROTATION_0, Surface.ROTATION_90), 0.001f)
    }

    @Test fun `sensor deadband prevents repeated flips around a quarter turn`() {
        assertEquals(Surface.ROTATION_0, stableCameraTargetRotation(50, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_270, stableCameraTargetRotation(56, Surface.ROTATION_0))
        assertEquals(Surface.ROTATION_270, stableCameraTargetRotation(40, Surface.ROTATION_270))
        assertEquals(Surface.ROTATION_0, stableCameraTargetRotation(34, Surface.ROTATION_270))
        assertEquals(null, stableCameraTargetRotation(-1, Surface.ROTATION_270))
        assertEquals(Surface.ROTATION_0, stableCameraTargetRotation(359, Surface.ROTATION_0))
    }

    @Test
    fun `animation target continues along the shortest path`() {
        // 从 -90° 去 180° 落在 -180°（同一视觉角度），不反向绕 270°。
        assertEquals(-180f, iconAnimationTarget(-90f, 180f), 0.001f)
        assertEquals(-90f, iconAnimationTarget(0f, 270f), 0.001f)
        assertEquals(0f, iconAnimationTarget(90f, 0f), 0.001f)
        assertEquals(-180f, iconAnimationTarget(-45f, 180f), 0.001f)
        // 动画每帧都不超过半圈，避免抖动时来回甩。
        listOf(0f, 45f, -30f, 200f).forEach { current ->
            listOf(0f, 90f, 180f, 270f).forEach { desired ->
                assertTrue("$current -> $desired", abs(iconAnimationTarget(current, desired) - current) <= 180.001f)
            }
        }
    }
}

/** 控件图标角度接进 Compose 后仍跟随实际目标方向；关闭动画时直接落位。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CameraIconRotationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `locked window icons follow every camera target direction`() {
        val target = mutableStateOf(Surface.ROTATION_0)
        var angle = Float.NaN
        compose.setContent {
            CompositionLocalProvider(LocalPaperMotionEnabled provides false) {
                angle = rememberCameraIconRotation(target.value, Surface.ROTATION_0)
            }
        }
        compose.waitForIdle()
        assertEquals(0f, angle, 0.001f)
        compose.runOnIdle { target.value = Surface.ROTATION_270 }
        compose.waitForIdle()
        assertEquals(-90f, angle, 0.001f)
        compose.runOnIdle { target.value = Surface.ROTATION_180 }
        compose.waitForIdle()
        assertEquals(180f, angle, 0.001f)
        compose.runOnIdle { target.value = Surface.ROTATION_90 }
        compose.waitForIdle()
        assertEquals(90f, angle, 0.001f)
    }

    @Test
    fun `fallback without a viewfinder keeps the control icons upright`() {
        val active = mutableStateOf(false)
        var angle = Float.NaN
        compose.setContent {
            CompositionLocalProvider(LocalPaperMotionEnabled provides false) {
                angle = rememberCameraIconRotation(Surface.ROTATION_270, Surface.ROTATION_0, active.value)
            }
        }
        compose.waitForIdle()
        assertEquals("没有取景框时不补偿窗口", 0f, angle, 0.001f)
        compose.runOnIdle { active.value = true }
        compose.waitForIdle()
        assertEquals("打开取景框后按目标方向补偿", -90f, angle, 0.001f)
    }
    @Test fun `automatic window rotation visibly eases icons back upright`() {
        compose.mainClock.autoAdvance = false
        val target = mutableStateOf(Surface.ROTATION_0)
        val display = mutableStateOf(Surface.ROTATION_0)
        var angle = Float.NaN
        compose.setContent {
            CompositionLocalProvider(LocalPaperMotionEnabled provides true) {
                angle = rememberCameraIconRotation(target.value, display.value)
            }
        }
        compose.runOnIdle { target.value = Surface.ROTATION_270; display.value = Surface.ROTATION_270 }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        assertTrue("The rotated canvas must expose a turn rather than snapping: $angle", abs(angle) > 0.5f && abs(angle) < 90f)
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        assertEquals(0f, angle, 0.001f)
    }

}
