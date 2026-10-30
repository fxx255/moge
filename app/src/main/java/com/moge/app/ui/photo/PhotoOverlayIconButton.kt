package com.moge.app.ui.photo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** A small opaque badge over a photo, with a full sized touch target around it. */
@Composable
internal fun PhotoOverlayIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    badgeAlignment: Alignment = Alignment.TopEnd,
) {
    val colors = MaterialTheme.colorScheme
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Box(Modifier.fillMaxSize().padding(2.dp), contentAlignment = badgeAlignment) {
            Surface(
                modifier = Modifier.size(26.dp),
                shape = CircleShape,
                color = colors.surface,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, colors.outline),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription, Modifier.size(16.dp))
                }
            }
        }
    }
}
