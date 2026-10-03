package com.moge.app.ui.solve

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.moge.app.ui.components.GridPaper
import com.moge.app.ui.components.HighlightedTitle

/** The list continues beneath both overlays; padding keeps its first and last items reachable. */
@Composable
internal fun ConversationScaffold(
    title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit,
    footer: @Composable () -> Unit, content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    var headerPixels by remember { mutableIntStateOf(0) }
    var footerPixels by remember { mutableIntStateOf(0) }
    val background = MaterialTheme.colorScheme.background
    GridPaper(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                content(PaddingValues(start = 16.dp, end = 16.dp,
                    top = with(density) { headerPixels.toDp() } + 20.dp,
                    bottom = with(density) { footerPixels.toDp() } + 20.dp))
                // Blur only the paper backdrop, leaving title and controls crisp. The alpha mask
                // dissolves the backdrop into the answer instead of ending it at a hard edge.
                Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .height(with(density) { headerPixels.toDp() } + 40.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.White, 0.55f to Color.White,
                            1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    }) {
                    GridPaper(Modifier.matchParentSize().blur(12.dp, BlurredEdgeTreatment.Unbounded)) {}
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                        listOf(background.copy(alpha = 0.96f), background.copy(alpha = 0.5f), Color.Transparent))))
                }
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().testTag("conversation-header")
                    .onSizeChanged { headerPixels = it.height }
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    } else Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) { HighlightedTitle(title, maxLines = 1) }
                    actions()
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("conversation-composer")
                    .onSizeChanged { footerPixels = it.height }) { footer() }
            }
        }
    }
}
