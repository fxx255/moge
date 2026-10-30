package com.moge.app.ui.share

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moge.app.R
import com.moge.app.domain.ConversationMode
import com.moge.app.runtime.SharedImages
import com.moge.app.ui.components.HighlightedTitle
import com.moge.app.ui.components.excludePageSwipe
import com.moge.app.ui.localization.label
import com.moge.app.ui.localization.rememberAppStrings
import com.moge.app.ui.photo.PhotoThumb
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.viewer.PhotoViewer

/** A paper attachment tray; navigation and draft delivery remain with the host. */
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
    var newConversation by rememberSaveable(share.id) { mutableStateOf(currentMode == null) }
    var modeName by rememberSaveable(share.id) { mutableStateOf((currentMode ?: ConversationMode.SOLVE).name) }
    var expanded by rememberSaveable(share.id) { mutableStateOf(false) }
    var previewIndex by rememberSaveable(share.id) { mutableStateOf<Int?>(null) }
    val useNew = newConversation || currentMode == null
    val scroll = rememberScrollState()
    LaunchedEffect(share.id, useNew, expanded) { if (!expanded) scroll.scrollTo(0) }
    Surface(modifier.excludePageSwipe().testTag("shared-image-panel").semantics { paneTitle = title },
        shape = RoundedCornerShape(12.dp), color = colors.background,
        border = BorderStroke(1.dp, MogeTheme.paper.cardStroke)) {
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
            BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                // Short, wide windows keep the destination beside the images, so an
                // existing attachment tray cannot push the choice out of view.
                val besideImages = maxWidth >= 520.dp && maxHeight < 240.dp
                val choices: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column {
                            HorizontalDivider(color = colors.outlineVariant)
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .testTag("share-destination-picker")
                                .clickable(enabled = !busy, role = Role.Button) { expanded = !expanded }
                                .semantics { stateDescription = strings.text(if (expanded) R.string.ui_expanded_fa83a else R.string.ui_collapsed_5dd9d) }
                                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(strings.text(R.string.share_destination), style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant)
                                Text(strings.text(if (useNew) R.string.share_new_conversation else R.string.share_current_conversation),
                                    modifier = Modifier.weight(1f), color = colors.primary, style = MaterialTheme.typography.bodyLarge)
                                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                                    Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                            }
                            if (expanded) {
                                Column(Modifier.fillMaxWidth().selectableGroup().background(colors.surface)) {
                                    ShareDestinationOption(strings.text(R.string.share_current_conversation), !useNew,
                                        !busy && currentMode != null && currentEnabled,
                                        detail = currentTitle,
                                        onClick = { newConversation = false; expanded = false })
                                    ShareDestinationOption(strings.text(R.string.share_new_conversation), useNew, !busy,
                                        onClick = { newConversation = true; expanded = false })
                                }
                            }
                            HorizontalDivider(color = colors.outlineVariant)
                        }
                        if (useNew) {
                            Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ConversationMode.entries.forEach { mode ->
                                    ShareModeOption(mode, modeName == mode.name, !busy,
                                        onClick = { modeName = mode.name }, modifier = Modifier.weight(1f))
                                }
                            }
                        } else if (besideImages) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                currentTitle?.takeIf(String::isNotBlank)?.let {
                                    Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text(strings.text(R.string.share_current_type, strings.label(currentMode!!)),
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                currentTitle?.takeIf(String::isNotBlank)?.let {
                                    Text(it, style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                Text(strings.text(R.string.share_current_type, strings.label(currentMode!!)),
                                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        error?.let { message ->
                            Text(message, color = colors.error, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.semantics { error(message); liveRegion = LiveRegionMode.Polite })
                        }
                    }
                }
                val content = Modifier.fillMaxWidth().verticalScroll(scroll)
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                if (besideImages) {
                    Row(content, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        SharedImagePreviews(share.photoPaths, { previewIndex = it },
                            Modifier.width(136.dp), stackedCount = true)
                        Box(Modifier.weight(1f)) { choices() }
                    }
                } else {
                    Column(content, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SharedImagePreviews(share.photoPaths, { previewIndex = it })
                        choices()
                    }
                }
            }
            Button(onClick = { onConfirm(if (useNew) ConversationMode.fromName(modeName) else null) },
                enabled = !busy && (useNew || currentEnabled),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    .fillMaxWidth().heightIn(min = 48.dp).testTag("share-add-to-input")) {
                Text(strings.text(R.string.share_add_to_input), Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
    previewIndex?.let { PhotoViewer(share.photoPaths, it, { previewIndex = null }, preferProcessed = false) }
}

@Composable
private fun SharedImagePreviews(paths: List<String>, onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier, stackedCount: Boolean = false) {
    val strings = rememberAppStrings()
    val previews: @Composable (Modifier) -> Unit = { photoModifier ->
        Row(photoModifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            paths.forEachIndexed { index, path ->
                PhotoThumb(path, strings.text(R.string.ui_photo_1_s_6ec17, index + 1),
                    { onOpen(index) }, Modifier.size(56.dp, 64.dp))
            }
        }
    }
    val count: @Composable () -> Unit = {
        Text(strings.text(R.string.share_image_count, paths.size),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (stackedCount) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            previews(Modifier.fillMaxWidth())
            count()
        }
    } else {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            previews(Modifier.weight(1f))
            count()
        }
    }
}

@Composable
private fun ShareDestinationOption(label: String, selected: Boolean, enabled: Boolean,
    detail: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = colors.onBackground.copy(alpha = if (enabled) 1f else 0.38f)
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (detail != null) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Add, null,
            Modifier.size(20.dp), tint = tint)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
            detail?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = colors.primary)
    }
}

@Composable
private fun ShareModeOption(mode: ConversationMode, selected: Boolean, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier) {
    val strings = rememberAppStrings()
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(10.dp),
        color = if (selected) colors.surfaceVariant else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant)) {
        Row(Modifier.heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val tint = (if (selected) colors.primary else colors.onBackground).copy(alpha = if (enabled) 1f else 0.38f)
            Icon(if (mode == ConversationMode.CHAT) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.School,
                null, Modifier.size(19.dp), tint = tint)
            Text(strings.label(mode), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = tint)
            if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(17.dp), tint = tint)
            else Box(Modifier.width(17.dp))
        }
    }
}
