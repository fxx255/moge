package com.moge.app.ui.capture

import android.view.Surface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import com.moge.app.ui.theme.MogeTheme
import kotlin.math.abs

/** 一次 90° 转动的时长；四档方向之间都用同一条时间曲线。 */
private const val IconRotationMillis = 240

/** 小于这个角度视为已经到位，避免传感器在临界点反复起动画。 */
private const val IconRotationThresholdDegrees = 0.5f

/**
 * Surface 的四个旋转值映射成顺时针角度。
 *
 * [cameraTargetRotation] 已经把传感器角度离散成这四档，所以图标角度天然只有
 * 0/90/180/270；监听器另有临界角度的回差。
 */
internal fun rotationAngleDegrees(rotation: Int): Float = when (rotation) {
    Surface.ROTATION_90 -> 90f
    Surface.ROTATION_180 -> 180f
    Surface.ROTATION_270 -> 270f
    else -> 0f
}

/**
 * [from] 到 [to] 的最短角度差，落在 [-180, 180]。
 * 例如 350° → 10° 得 +20°，270° ← 0° 得 -90°，不会绕 270° 的远路。
 */
internal fun shortestAngleDelta(from: Float, to: Float): Float {
    val delta = (to - from) % 360f
    return when {
        delta > 180f -> delta - 360f
        delta < -180f -> delta + 360f
        else -> delta
    }
}

/**
 * 控件图标在当前画布坐标里需要额外转过的角度（顺时针为正，取最短路径）。
 *
 * [targetRotation] 是相机实际生效的目标方向（[androidx.camera.core.Preview] 与
 * [androidx.camera.core.ImageCapture] 同源），[displayRotation] 是窗口当前方向：
 * - 屏幕自动旋转打开：窗口已经跟着设备转，两者相等 → 0°，图标保持正向；
 * - 方向被锁定（自动旋转关闭 / 平放回退）：用目标方向补偿窗口 → 图标仍朝用户上方。
 *
 * 竖屏、两侧横屏、倒置分别得到 0° / ±90° / 180°。
 */
internal fun cameraIconRotationDegrees(targetRotation: Int, displayRotation: Int): Float =
    shortestAngleDelta(rotationAngleDegrees(displayRotation), rotationAngleDegrees(targetRotation))

/**
 * 动画的实际落点：在当前角度上继续走最短路径，而不是直接跳到离散角度。
 * 例如当前 -90°、目标 180° 时落在 -180°（视觉相同），不会反向绕 270°。
 */
internal fun iconAnimationTarget(current: Float, desired: Float): Float =
    current + shortestAngleDelta(current, desired)

/** 给非对称图标加转动；圆形快门不需要旋转。 */
internal fun Modifier.cameraIconRotation(degrees: Float): Modifier = graphicsLayer { rotationZ = degrees }

/**
 * 当前窗口方向。Activity 声明了 configChanges，旋转不重建，但配置变化后
 * [LocalConfiguration] 会更新，据此重新取 `display.rotation`。
 */
@Composable
internal fun rememberDisplayRotation(): Int {
    val configuration = LocalConfiguration.current
    val view = LocalView.current
    return remember(configuration) { view.display?.rotation ?: Surface.ROTATION_0 }
}

/** A ten-degree deadband around each quarter-turn boundary prevents repeated icon flips. */
internal fun stableCameraTargetRotation(orientation: Int, previous: Int): Int? {
    val candidate = cameraTargetRotation(orientation) ?: return null
    val center = (360f - rotationAngleDegrees(previous)) % 360f
    return if (abs(shortestAngleDelta(center, orientation.toFloat())) <= 55f) previous else candidate
}

/**
 * Follow CameraX's target in the current screen coordinates. When the window itself rotates,
 * compensate the old canvas angle first, then ease upright. This keeps a visible, continuous
 * turn even when sensor and display update in the same frame, without adding a second full turn.
 */
@Composable
internal fun rememberCameraIconRotation(
    targetRotation: Int,
    displayRotation: Int,
    enabled: Boolean = true,
): Float {
    val desired = if (enabled) cameraIconRotationDegrees(targetRotation, displayRotation) else 0f
    val motion = MogeTheme.motionEnabled
    val angle = remember { Animatable(desired) }
    val previousDisplay = remember { intArrayOf(displayRotation) }
    LaunchedEffect(targetRotation, displayRotation, enabled, motion) {
        val displayDelta = shortestAngleDelta(
            rotationAngleDegrees(previousDisplay[0]), rotationAngleDegrees(displayRotation),
        )
        previousDisplay[0] = displayRotation
        if (motion && enabled && displayDelta != 0f) angle.snapTo(angle.value - displayDelta)
        val target = iconAnimationTarget(angle.value, desired)
        when {
            !motion || !enabled -> angle.snapTo(desired)
            abs(target - angle.value) < IconRotationThresholdDegrees -> angle.snapTo(target)
            else -> angle.animateTo(target, tween(IconRotationMillis, easing = FastOutSlowInEasing))
        }
    }
    return angle.value
}
