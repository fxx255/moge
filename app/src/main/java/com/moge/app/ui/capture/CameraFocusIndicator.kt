package com.moge.app.ui.capture

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.delay

/** A brief tap marker, rather than a permanent framing guide or a promise of successful AF. */
@Composable
internal fun CameraFocusIndicator(target: CameraMeteringTarget?, onFinished: (Long) -> Unit, modifier: Modifier = Modifier) {
    val motion = MogeTheme.motionEnabled
    val alpha = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }
    val finish by rememberUpdatedState(onFinished)
    LaunchedEffect(target?.id, motion) {
        val current = target ?: return@LaunchedEffect
        alpha.snapTo(1f)
        scale.snapTo(if (motion) 1.18f else 1f)
        if (motion) scale.animateTo(1f, tween(150))
        delay(1_100)
        if (motion) alpha.animateTo(0f, tween(200))
        finish(current.id)
    }
    if (target == null) return
    val color = MogeTheme.paper.highlighter.copy(alpha = 1f)
    Canvas(modifier.testTag("camera-focus-indicator")) {
        val radius = 32.dp.toPx() * scale.value
        val corner = 12.dp.toPx() * scale.value
        val center = target.position
        val path = Path().apply {
            for ((x, y) in listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
                val vertex = center + Offset(x * radius, y * radius)
                moveTo(vertex.x - x * corner, vertex.y)
                lineTo(vertex.x, vertex.y)
                lineTo(vertex.x, vertex.y - y * corner)
            }
        }
        drawPath(path, Color.Black.copy(alpha = .65f * alpha.value), style = Stroke(4.dp.toPx()))
        drawPath(path, color.copy(alpha = alpha.value), style = Stroke(2.dp.toPx()))
    }
}
