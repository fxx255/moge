package com.moge.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme

/** Matches the quadratic top edge, so the transparent corners never arm deletion. */
internal fun curvedDeleteContains(bounds: Rect, point: Offset): Boolean {
    if (bounds.isEmpty || !bounds.contains(point)) return false
    val t = ((point.x - bounds.left) / bounds.width).coerceIn(0f, 1f)
    val top = bounds.top + bounds.height * 0.22f * (2f * t - 1f) * (2f * t - 1f)
    return point.y >= top
}

@Composable
internal fun PaperDeleteZone(armed: Boolean, label: String, modifier: Modifier = Modifier) {
    val red = lerp(MaterialTheme.colorScheme.error, Color(0xFFC62828), 0.7f)
    val error by animateColorAsState(if (armed) red else red.copy(alpha = 0.85f),
        tween(if (MogeTheme.motionEnabled) 160 else 0), label = "delete-zone-color")
    val ink = Color.White
    BoxWithConstraints(modifier.drawBehind {
        val path = Path().apply {
            moveTo(0f, size.height * 0.22f)
            quadraticTo(size.width / 2, -size.height * 0.22f, size.width, size.height * 0.22f)
            lineTo(size.width, size.height); lineTo(0f, size.height); close()
        }
        drawPath(path, Brush.verticalGradient(listOf(error, error.copy(alpha = 0.55f), error.copy(alpha = 0.12f))))
    }) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = maxHeight * 0.1f),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.DeleteOutline, "删除区域", Modifier.size(32.dp), tint = ink)
            Surface(color = error.copy(alpha = 0.9f), contentColor = ink, shape = MaterialTheme.shapes.small) {
                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
