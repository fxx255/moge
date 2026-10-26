package com.moge.app.ui.export

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.moge.app.R
import com.moge.app.ui.components.PaperModalSheet
import com.moge.app.ui.localization.rememberAppStrings

@Composable
internal fun ExportPaperSheet(busy: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val strings = rememberAppStrings()
    PaperModalSheet(strings.text(R.string.ui_share_images_2a61d),
        strings.text(if (busy) R.string.ui_cancel_close_2475f else R.string.ui_close_sharing_59808),
        onDismiss, Modifier.testTag("answer-export-dialog"), content)
}
