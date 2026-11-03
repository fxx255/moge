package com.moge.app.ui.share

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moge.app.R
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.localization.rememberAppStrings

@Composable
internal fun SharedImageDestinationActions(currentAvailable: Boolean, currentTitle: String?, busy: Boolean,
    horizontal: Boolean, onConfirm: (ConversationMode?) -> Unit, modifier: Modifier = Modifier) {
    val strings = rememberAppStrings()
    val actions: @Composable (Modifier) -> Unit = { buttonModifier ->
        ShareDestinationButton(strings.text(R.string.share_join_current), Icons.Outlined.AttachFile,
            enabled = !busy && currentAvailable, primary = currentAvailable,
            detail = currentTitle?.takeIf { currentAvailable && it.isNotBlank() }, onClick = { onConfirm(null) },
            modifier = buttonModifier.testTag("share-join-current"))
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

@Composable
private fun ShareDestinationButton(label: String, icon: ImageVector, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, detail: String? = null) {
    val content: @Composable RowScope.() -> Unit = {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(18.dp))
    }
    val sized = modifier.heightIn(min = 48.dp)
    val padding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    if (primary) Button(onClick, sized, enabled = enabled, shape = MaterialTheme.shapes.medium,
        contentPadding = padding, content = content)
    else OutlinedButton(onClick, sized, enabled = enabled, shape = MaterialTheme.shapes.medium,
        contentPadding = padding, content = content)
}
