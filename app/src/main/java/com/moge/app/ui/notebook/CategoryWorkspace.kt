package com.moge.app.ui.notebook

import com.moge.app.R
import com.moge.app.ui.localization.appString

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.moge.app.ui.components.GridPaper
import com.moge.app.ui.components.HighlightedTitle
import com.moge.app.ui.components.LocalPaperNavigationScope
import com.moge.app.ui.theme.MogeTheme

/** Shared paper/blackboard surface for category management and destination picking. */
@Composable
internal fun CategoryWorkspace(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    CompositionLocalProvider(LocalPaperNavigationScope provides null,
        LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Surface(modifier.safeDrawingPadding().padding(horizontal = 12.dp, vertical = 16.dp)
            .widthIn(max = 600.dp).fillMaxSize().testTag("category-workspace"),
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shadowElevation = 10.dp) {
            GridPaper(Modifier.fillMaxSize()) {
                val margin = MogeTheme.paper.marginLine
                Column(Modifier.fillMaxSize().drawBehind {
                    val x = 28.dp.toPx()
                    drawLine(margin.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }, content = content)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CategoryWorkspaceHeader(title: String, onDismiss: () -> Unit, enabled: Boolean = true,
    actionLabel: String = appString(R.string.ui_done_33246), action: @Composable RowScope.() -> Unit = {}) {
    FlowRow(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
            HighlightedTitle(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            action()
            TextButton(onDismiss, enabled = enabled) { Text(actionLabel) }
        }
    }
}
