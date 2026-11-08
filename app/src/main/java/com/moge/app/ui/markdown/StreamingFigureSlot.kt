package com.moge.app.ui.markdown

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings
import com.moge.app.ui.theme.MogeTheme

/** A figure keeps its identity while its protocol anchor becomes a generated image. */
@Composable
internal fun StreamingFigureSlot(index: Int, path: String?, pending: Boolean,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val strings = rememberAppStrings()
    val motion = MogeTheme.motionEnabled
    Column(modifier.fillMaxWidth().testTag("figure-slot-$index")
        .animateContentSize(tween(if (motion) 180 else 0))) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val placeholderHeight = maxWidth * FIGURE_PLACEHOLDER_HEIGHT_RATIO
        when {
            !path.isNullOrBlank() -> InlineFigure(path, onClick, index + 1, placeholderHeight = placeholderHeight)
            pending -> {
                val label = strings.text(R.string.ui_figure_generating)
                Column(Modifier.fillMaxWidth().height(placeholderHeight).clip(MaterialTheme.shapes.small)
                    .background(MogeTheme.paper.scratch).testTag("figure-pending-$index")
                    .semantics { contentDescription = label },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                    if (motion) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else CircularProgressIndicator(progress = { .65f }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> FailedFigureHint(index)
        }
    }
    }
}

/** 未知几何尺寸的生成图使用与常用曲线图相同的高宽比，随回答栏宽度变化。 */
private const val FIGURE_PLACEHOLDER_HEIGHT_RATIO = 0.66f
