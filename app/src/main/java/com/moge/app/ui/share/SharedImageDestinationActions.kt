package com.moge.app.ui.share

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.moge.app.R
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.localization.rememberAppStrings

@Composable
internal fun SharedImageDestinationActions(currentAvailable: Boolean, currentTitle: String?, busy: Boolean,
    horizontal: Boolean, onConfirm: (ConversationMode?) -> Unit, modifier: Modifier = Modifier,
    history: List<SharedConversationTarget> = emptyList(), onConfirmHistory: (SharedConversationTarget) -> Unit = {}) {
    val strings = rememberAppStrings()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val selected = history.firstOrNull { it.id == selectedId }
    val available = if (selectedId == null) currentAvailable else selected != null
    val actions: @Composable (Modifier) -> Unit = { buttonModifier ->
        ShareCurrentDestinationButton(strings.text(if (selectedId == null) R.string.share_join_current else R.string.share_join_selected),
                enabled = !busy && available, primary = available,
                detail = (if (selectedId == null) currentTitle else selected?.title)?.takeIf { available && it.isNotBlank() },
                onClick = { if (selected != null) onConfirmHistory(selected) else onConfirm(null) },
                modifier = buttonModifier.testTag("share-current-card")) {
            Box {
                IconButton(onClick = { expanded = true }, enabled = !busy,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = LocalContentColor.current),
                    modifier = Modifier.size(48.dp).testTag("share-history-toggle")) {
                    Icon(Icons.Outlined.ExpandMore, strings.text(R.string.share_choose_history))
                }
                DropdownMenu(expanded = expanded && !busy, onDismissRequest = { expanded = false },
                    modifier = Modifier.widthIn(min = 240.dp, max = 360.dp).heightIn(max = 320.dp)
                        .testTag("share-history-menu")) {
                    DropdownMenuItem(text = { Text(strings.text(R.string.share_join_current)) },
                        enabled = currentAvailable,
                        onClick = { selectedId = null; expanded = false },
                        trailingIcon = { if (selected == null) Icon(Icons.Outlined.Check, null) })
                    if (history.isNotEmpty()) HorizontalDivider()
                    val dateFormat = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()) }
                    history.forEach { conversation ->
                        DropdownMenuItem(onClick = { selectedId = conversation.id; expanded = false },
                            modifier = Modifier.testTag("share-history-${conversation.id}"),
                            text = { Column {
                                Text(conversation.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(dateFormat.format(conversation.lastActivity), style = MaterialTheme.typography.bodySmall)
                            } },
                            trailingIcon = { if (selectedId == conversation.id) Icon(Icons.Outlined.Check, null) })
                    }
                }
            }
        }
        ShareDestinationButton(strings.text(R.string.share_join_new_question), Icons.Outlined.School,
            enabled = !busy, onClick = { onConfirm(ConversationMode.SOLVE) },
            modifier = buttonModifier.testTag("share-join-new-question"))
        ShareDestinationButton(strings.text(R.string.share_join_new_chat), Icons.Outlined.ChatBubbleOutline,
            enabled = !busy, onClick = { onConfirm(ConversationMode.CHAT) },
            modifier = buttonModifier.testTag("share-join-new-chat"))
    }
    if (horizontal) Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions(Modifier.weight(1f)) }
    else Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) { actions(Modifier.fillMaxWidth()) }
}

/** Two independent actions share the original button surface, border and rounded shape. */
@Composable
private fun ShareCurrentDestinationButton(label: String, enabled: Boolean, primary: Boolean,
    detail: String?, onClick: () -> Unit, modifier: Modifier, historyMenu: @Composable () -> Unit) {
    val colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.outlinedButtonColors()
    Surface(modifier, shape = MaterialTheme.shapes.medium,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
        border = if (primary) null else ButtonDefaults.outlinedButtonBorder(enabled)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 48.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp)
                .testTag("share-join-current"), verticalAlignment = Alignment.CenterVertically) {
                ShareDestinationContent(label, Icons.Outlined.AttachFile, detail,
                    arrowModifier = Modifier.testTag("share-current-forward"))
            }
            // With no current conversation the join action is disabled, but history is available.
            CompositionLocalProvider(LocalContentColor provides
                if (primary) colors.contentColor else MaterialTheme.colorScheme.onSurface) {
                historyMenu()
            }
        }
    }
}

@Composable
private fun ShareDestinationButton(label: String, icon: ImageVector, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, detail: String? = null) {
    val content: @Composable RowScope.() -> Unit = {
        ShareDestinationContent(label, icon, detail)
    }
    val sized = modifier.heightIn(min = 48.dp)
    val padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    if (primary) Button(onClick, sized, enabled = enabled, shape = MaterialTheme.shapes.medium,
        contentPadding = padding, content = content)
    else OutlinedButton(onClick, sized, enabled = enabled, shape = MaterialTheme.shapes.medium,
        contentPadding = padding, content = content)
}

@Composable
private fun RowScope.ShareDestinationContent(label: String, icon: ImageVector, detail: String?,
    arrowModifier: Modifier = Modifier) {
    Icon(icon, null, Modifier.size(20.dp))
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis) }
    }
    Spacer(Modifier.width(8.dp))
    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, arrowModifier.size(18.dp))
}
