package com.moge.app.ui.capture

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Only the sun and its open slider intercept touches; the preview keeps tap/pinch ownership. */
@Composable
internal fun CameraMeteringOverlay(target: CameraMeteringTarget?, value: Float, supported: Boolean,
    enabled: Boolean, onValue: (Float) -> Unit, onFinished: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (target == null) return
    val strings = rememberAppStrings()
    var expanded by remember(target.id) { mutableStateOf(false) }
    var dragging by remember(target.id) { mutableStateOf(false) }
    LaunchedEffect(target.id, expanded, value, dragging) {
        if (expanded && !dragging) { delay(3_500); expanded = false }
    }
    val yellow = Color(0xFFFFD54F)
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        CameraFocusIndicator(target, onFinished, Modifier.matchParentSize(), hold = expanded, timeoutMillis = 2_200)
        val sunSize = with(density) { 48.dp.toPx() }
        val gap = with(density) { 34.dp.toPx() }
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val preferredX = target.position.x + gap
        val sunX = (if (preferredX + sunSize <= width) preferredX else target.position.x - gap - sunSize)
            .coerceIn(0f, (width - sunSize).coerceAtLeast(0f))
        val sunY = (target.position.y - sunSize / 2).coerceIn(0f, (height - sunSize).coerceAtLeast(0f))
        IconButton(onClick = { expanded = !expanded }, enabled = enabled && supported,
            colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = .3f),
                contentColor = yellow, disabledContentColor = yellow.copy(alpha = .4f)),
            modifier = Modifier.offset { IntOffset(sunX.roundToInt(), sunY.roundToInt()) }
                .size(48.dp).testTag("camera-exposure-sun")) {
            Icon(Icons.Outlined.WbSunny, strings.text(if (supported) R.string.ui_camera_brightness
                else R.string.ui_camera_brightness_unavailable), Modifier.size(22.dp))
        }
        if (expanded) {
            val sliderWidth = minOf(180.dp, maxWidth)
            val sliderPixels = with(density) { sliderWidth.toPx() }
            val sliderHeight = with(density) { 56.dp.toPx() }
            val sliderX = (sunX + sunSize / 2 - sliderPixels / 2).coerceIn(0f, (width - sliderPixels).coerceAtLeast(0f))
            val sliderY = (if (sunY + sunSize + sliderHeight <= height) sunY + sunSize
                else sunY - sliderHeight).coerceIn(0f, (height - sliderHeight).coerceAtLeast(0f))
            Surface(shape = RoundedCornerShape(16.dp), color = Color.Black.copy(alpha = .8f),
                modifier = Modifier.offset { IntOffset(sliderX.roundToInt(), sliderY.roundToInt()) }
                    .width(sliderWidth).height(56.dp)) {
                Slider(value, onValueChange = { dragging = true; onValue(it) },
                    onValueChangeFinished = { dragging = false }, enabled = enabled && supported,
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("camera-exposure-slider")
                        .semantics { contentDescription = strings.text(R.string.ui_camera_brightness) },
                    colors = SliderDefaults.colors(thumbColor = yellow, activeTrackColor = yellow,
                        inactiveTrackColor = Color.White.copy(alpha = .3f)))
            }
        }
    }
}
