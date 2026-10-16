package com.moge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Softens the paper behind page headers while keeping titles and controls crisp. */
@Composable
internal fun PaperHeaderBackdrop(modifier: Modifier, offsetY: () -> Float = { 0f }) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.White, 0.55f to Color.White,
                1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }) {
        GridPaper(Modifier.matchParentSize().blur(12.dp, BlurredEdgeTreatment.Unbounded), offsetY = offsetY) {}
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            listOf(background.copy(alpha = 0.96f), background.copy(alpha = 0.5f), Color.Transparent))))
    }
}
