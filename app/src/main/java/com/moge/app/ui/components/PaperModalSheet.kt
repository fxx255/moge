package com.moge.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.moge.app.ui.theme.MogeTheme

/** Sharing and question selection own opaque paper, independent of the page below. */
@Composable
internal fun PaperModalSheet(
    title: String,
    closeLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val view = LocalView.current
    val lightPaper = !MogeTheme.paper.isChalk
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = lightPaper
                isAppearanceLightNavigationBars = lightPaper
            }
        }
    }
    CompositionLocalProvider(LocalPaperNavigationScope provides null,
        LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        GridPaper(modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 12.dp)) {
                PaperStripHeader(progress = { 0f }, showUnderlay = false, active = true) {
                    Row(Modifier.fillMaxWidth().heightIn(min = PaperHeaderMinHeight)
                        .padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        HighlightedTitle(title, modifier = Modifier.weight(1f),
                            style = paperHeaderTitleStyle(), maxLines = 1)
                        PaperHeaderIconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, closeLabel) }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}
