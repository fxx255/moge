package com.moge.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme

/** A narrow paper band whose cut ends stay outside the screen, with a reversible underlay. */
@Composable
internal fun StackedPaperHeader(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val paper = MogeTheme.paper
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val shadow = Color.Black.copy(alpha = if (paper.isChalk) 0.18f else 0.1f)
    Box(modifier.padding(top = 12.dp)) {
        Box(Modifier.matchParentSize().drawBehind {
            val overhang = 64.dp.toPx()
            val top = 4.dp.toPx()
            val start = -overhang
            val end = size.width + overhang
            rotate(-2f + 4f * progress().coerceIn(0f, 1f)) {
                drawRect(paper.headerUnderlay, Offset(start, top), Size(end - start, size.height))
                drawLine(line, Offset(start, top), Offset(end, top), 1.dp.toPx())
                drawLine(line, Offset(start, top + size.height), Offset(end, top + size.height), 1.dp.toPx())
            }
        })
        Box(Modifier.graphicsLayer { translationY = -4.dp.toPx() * progress().coerceIn(0f, 1f) }
            .drawBehind {
                val overhang = 64.dp.toPx()
                val width = size.width + 2 * overhang
                val depth = (5f + 3f * progress().coerceIn(0f, 1f)).dp.toPx()
                drawRect(Brush.verticalGradient(listOf(shadow, Color.Transparent),
                    startY = size.height, endY = size.height + depth),
                    Offset(-overhang, size.height), Size(width, depth))
                drawRect(paper.headerPaper, Offset(-overhang, 0f), Size(width, size.height))
                drawLine(line, Offset(-overhang, 0f), Offset(size.width + overhang, 0f), 1.dp.toPx())
                drawLine(line, Offset(-overhang, size.height), Offset(size.width + overhang, size.height), 1.dp.toPx())
            }) { content() }
    }
}
