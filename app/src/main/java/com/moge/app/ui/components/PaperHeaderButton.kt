package com.moge.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.moge.app.ui.theme.MogeTheme

/** A quiet press highlight shared by paper headers, with Material touch targets and semantics. */
@Composable
internal fun PaperHeaderIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled,
        interactionSource = interaction,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = paperHeaderPressColor(interaction, enabled)),
        content = content)
}

/** Use the control's own interaction source so release, cancellation and disabling all clear it. */
@Composable
internal fun paperHeaderPressColor(
    interactionSource: MutableInteractionSource,
    enabled: Boolean = true,
): Color {
    val pressed by interactionSource.collectIsPressedAsState()
    val highlighted = enabled && pressed
    val color by animateColorAsState(
        targetValue = MaterialTheme.colorScheme.onBackground.copy(alpha = if (highlighted) 0.14f else 0f),
        animationSpec = tween(if (!MogeTheme.motionEnabled) 0 else if (highlighted) 70 else 160),
        label = "paper-header-press",
    )
    return color
}
