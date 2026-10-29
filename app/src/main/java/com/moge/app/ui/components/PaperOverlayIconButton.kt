package com.moge.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Opaque controls stay legible over photos, messages and the editing scrim. */
@Composable
internal fun PaperOverlayIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    OutlinedIconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        enabled = enabled,
        border = BorderStroke(1.dp, colors.outline),
        colors = IconButtonDefaults.outlinedIconButtonColors(
            containerColor = colors.surface,
            contentColor = colors.onSurface,
            disabledContainerColor = colors.surface,
            disabledContentColor = colors.onSurface.copy(alpha = 0.38f),
        ),
        content = content,
    )
}
