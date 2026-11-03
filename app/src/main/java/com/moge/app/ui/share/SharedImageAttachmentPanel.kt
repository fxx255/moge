package com.moge.app.ui.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.moge.app.R
import com.moge.app.domain.ConversationMode
import com.moge.app.runtime.SharedImages
import com.moge.app.ui.components.HighlightedTitle
import com.moge.app.ui.components.excludePageSwipe
import com.moge.app.ui.localization.rememberAppStrings
import com.moge.app.ui.photo.PhotoThumb
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.viewer.PhotoViewer

/** Each destination starts the existing crop-and-attach flow directly. No intermediate mode choice. */
@Composable
internal fun SharedImageAttachmentPanel(
    share: SharedImages,
    currentMode: ConversationMode?,
    currentTitle: String?,
    currentEnabled: Boolean,
    busy: Boolean,
    error: String?,
    onConfirm: (ConversationMode?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberAppStrings()
    val colors = MaterialTheme.colorScheme
    val title = strings.text(R.string.share_images_title)
    var previewIndex by rememberSaveable(share.id) { mutableStateOf<Int?>(null) }
    Surface(modifier.excludePageSwipe().testTag("shared-image-panel").semantics { paneTitle = title },
        shape = RoundedCornerShape(12.dp), color = colors.background,
        border = BorderStroke(1.dp, MogeTheme.paper.cardStroke)) {
        BoxWithConstraints {
            val shortWindow = maxHeight < 300.dp
            val horizontalActions = maxWidth >= 520.dp && shortWindow
            Column {
                Row(Modifier.fillMaxWidth().background(MogeTheme.paper.headerPaper)
                    .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.AttachFile, null, Modifier.size(20.dp), tint = colors.onBackground)
                    HighlightedTitle(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    IconButton(onClick = onDismiss, enabled = !busy) {
                        Icon(Icons.Outlined.Close, strings.text(R.string.ui_cancel_4d0b4))
                    }
                }
                HorizontalDivider(color = colors.outlineVariant)
                Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SharedImagePreviews(share.photoPaths, { previewIndex = it })
                    error?.let { message ->
                        Text(message, color = colors.error, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics { error(message); liveRegion = LiveRegionMode.Polite })
                    }
                }
                SharedImageDestinationActions(currentAvailable = currentMode != null && currentEnabled,
                    currentTitle = currentTitle.takeUnless { shortWindow }, busy = busy,
                    horizontal = horizontalActions, onConfirm = onConfirm,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
    previewIndex?.let { PhotoViewer(share.photoPaths, it, { previewIndex = null }, preferProcessed = false) }
}

@Composable
private fun SharedImagePreviews(paths: List<String>, onOpen: (Int) -> Unit) {
    val strings = rememberAppStrings()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            paths.forEachIndexed { index, path ->
                PhotoThumb(path, strings.text(R.string.ui_photo_1_s_6ec17, index + 1),
                    { onOpen(index) }, Modifier.size(56.dp, 64.dp))
            }
        }
        Text(strings.text(R.string.share_image_count, paths.size),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
