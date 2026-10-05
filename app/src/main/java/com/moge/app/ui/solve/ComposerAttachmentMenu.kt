package com.moge.app.ui.solve

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.moge.app.ui.theme.MogeTheme
import kotlin.math.cos
import kotlin.math.sin

/** A separate window keeps the fan out of the composer's measurements and clipping. */
@Composable
internal fun ComposerAttachmentMenu(
    enabled: Boolean,
    photosEnabled: Boolean,
    onTakePhoto: () -> Unit,
    onPickPhotos: () -> Unit,
    onPickDocument: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    val motion = MogeTheme.motionEnabled
    LaunchedEffect(expanded, enabled, motion) {
        if (!enabled) expanded = false
        val opening = expanded && enabled
        if (opening) visible = true
        if (motion) progress.animateTo(if (opening) 1f else 0f,
            tween(if (opening) 320 else 180, easing = FastOutSlowInEasing))
        else progress.snapTo(if (opening) 1f else 0f)
        if (!opening) visible = false
    }
    Box(Modifier.size(48.dp)) {
        AttachmentToggle(expanded, enabled, progress, onClick = { expanded = !expanded },
            modifier = if (visible) Modifier.clearAndSetSemantics {} else Modifier)
        if (visible) Popup(
            popupPositionProvider = remember { AttachmentFanPositionProvider },
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = true, clippingEnabled = false),
        ) {
            Box(Modifier.size(184.dp, 176.dp).pointerInput(Unit) {
                detectTapGestures { expanded = false }
            }) {
                val actions = listOf(
                    Triple(Icons.Outlined.PhotoCamera, "拍照", onTakePhoto),
                    Triple(Icons.Outlined.PhotoLibrary, "从相册选图", onPickPhotos),
                    Triple(Icons.Outlined.AttachFile, "上传文档", onPickDocument),
                )
                actions.forEachIndexed { index, (icon, label, action) ->
                    val available = enabled && expanded && (index == 2 || photosEnabled)
                    Surface(onClick = { expanded = false; action() }, enabled = available,
                        shape = CircleShape, shadowElevation = 6.dp, tonalElevation = 3.dp,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(48.dp).graphicsLayer {
                            val fraction = progress.value
                            // Positive angular movement opens clockwise; reversing it closes counterclockwise.
                            val angle = Math.toRadians(-180.0 + (45.0 + index * 45.0) * fraction)
                            translationX = (92.dp.toPx() + cos(angle).toFloat() * 86.dp.toPx() * fraction) - size.width / 2f
                            translationY = (152.dp.toPx() + sin(angle).toFloat() * 86.dp.toPx() * fraction) - size.height / 2f
                            alpha = fraction
                            scaleX = 0.65f + 0.35f * fraction
                            scaleY = scaleX
                        },
                    ) {
                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Icon(icon, label, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface
                                .copy(alpha = if (available) 1f else 0.38f))
                        }
                    }
                }
                // Include the pivot in the popup window so orbiting icons are never cut off.
                AttachmentToggle(expanded, enabled, progress, onClick = { expanded = false },
                    modifier = Modifier.offset(68.dp, 128.dp))
            }
        }
    }
}

@Composable
private fun AttachmentToggle(expanded: Boolean, enabled: Boolean,
    progress: Animatable<Float, AnimationVector1D>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, enabled = enabled,
        modifier = modifier.size(48.dp).semantics { stateDescription = if (expanded) "已展开" else "已收起" }) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.size(34.dp)) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                Icon(Icons.Outlined.Add, if (expanded) "收起附件菜单" else "添加附件",
                    Modifier.size(22.dp).graphicsLayer { rotationZ = 45f * progress.value })
            }
        }
    }
}

private object AttachmentFanPositionProvider : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset(anchorBounds.center.x - popupContentSize.width / 2,
            anchorBounds.bottom - popupContentSize.height)
}
