package com.moge.app.ui.capture

import android.view.Surface
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 把自然设备坐标映射到当前屏幕；平放时重力投影太小，不显示不稳定的角度。 */
internal fun screenRollDegrees(x: Float, y: Float, rotation: Int): Float? {
    if (!x.isFinite() || !y.isFinite() || hypot(x, y) < 1f) return null
    val (screenX, screenY) = when (rotation) {
        Surface.ROTATION_90 -> y to -x
        Surface.ROTATION_180 -> -x to -y
        Surface.ROTATION_270 -> -y to x
        else -> x to y
    }
    return Math.toDegrees(atan2(screenX.toDouble(), abs(screenY.toDouble()))).toFloat()
}

/**
 * Center-to-right-end vector of the level guide in Canvas coordinates.
 * Screen roll already describes the horizon relative to the phone. Canvas Y grows downward,
 * so positive roll moves the right end down (clockwise); negating it reverses the guide.
 */
internal fun levelGuideOffset(degrees: Float, halfLength: Float): Offset {
    val radians = Math.toRadians(degrees.toDouble())
    return Offset((cos(radians) * halfLength).toFloat(), (sin(radians) * halfLength).toFloat())
}
