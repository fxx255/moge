package com.moge.app.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.moge.app.R
import com.moge.app.ui.components.GridPaper
import com.moge.app.ui.components.HighlightedTitle
import com.moge.app.ui.components.LocalPaperNavigationScope
import com.moge.app.ui.components.PaperHeaderIconButton
import com.moge.app.ui.components.PaperHeaderMinHeight
import com.moge.app.ui.components.PaperStripHeader
import com.moge.app.ui.components.paperHeaderTitleStyle
import com.moge.app.ui.localization.rememberAppStrings
import com.moge.app.ui.theme.MogeTheme

/** A modal owns its paper and ink; it must not register layers on the underlying navigation. */
@Composable
internal fun ExportPaperSheet(
    busy: Boolean,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val strings = rememberAppStrings()
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
    CompositionLocalProvider(
        LocalPaperNavigationScope provides null,
        LocalContentColor provides MaterialTheme.colorScheme.onBackground,
    ) {
        GridPaper(Modifier.fillMaxSize().testTag("answer-export-dialog")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(bottom = 12.dp)) {
                PaperStripHeader(progress = { 0f }, showUnderlay = false, active = true) {
                    Row(Modifier.fillMaxWidth().heightIn(min = PaperHeaderMinHeight)
                        .padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        HighlightedTitle(strings.text(R.string.ui_share_images_2a61d),
                            modifier = Modifier.weight(1f), style = paperHeaderTitleStyle(), maxLines = 1)
                        PaperHeaderIconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, if (busy) strings.text(R.string.ui_cancel_close_2475f)
                                else strings.text(R.string.ui_close_sharing_59808))
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}
