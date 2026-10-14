package com.moge.app.ui.solve

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.components.excludePageSwipe
import com.moge.app.ui.theme.MogeTheme

@Composable
internal fun NewConversationPlaceholder(
    mode: ConversationMode,
    enabled: Boolean,
    onSelect: (ConversationMode) -> Unit,
) {
    val duration = if (MogeTheme.motionEnabled) 220 else 0
    BoxWithConstraints(Modifier.widthIn(max = 360.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Attachments and the keyboard can leave little height in landscape.
        // Keep mode controls reachable before spending space on the greeting.
        val showGreeting = maxHeight >= 160.dp
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (showGreeting) AnimatedContent(mode,
                transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)) },
                label = "conversation-greeting") { selected ->
                Text(if (selected == ConversationMode.CHAT) "今天聊点什么？" else "有什么题目要解？",
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center)
            }
            ConversationModePicker(mode, enabled, onSelect)
        }
    }
}

/** Text tabs with an ink/chalk underline and accessible radio semantics. */
@Composable
internal fun ConversationModePicker(mode: ConversationMode, enabled: Boolean, onSelect: (ConversationMode) -> Unit) {
    val duration = if (MogeTheme.motionEnabled) 220 else 0
    BoxWithConstraints(Modifier.widthIn(max = 240.dp).fillMaxWidth().excludePageSwipe()
        .testTag("conversation-mode-picker").selectableGroup()) {
        val halfWidth = maxWidth / 2
        val offset by animateDpAsState(if (mode == ConversationMode.CHAT) halfWidth else 0.dp,
            tween(duration), label = "conversation-mode-selection")
        Box(Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter)
            .background(MaterialTheme.colorScheme.outlineVariant))
        Box(Modifier.align(Alignment.BottomStart).offset(x = offset + (halfWidth - 32.dp) / 2)
            .width(32.dp).height(3.dp).background(MaterialTheme.colorScheme.primary))
        Row(Modifier.fillMaxWidth()) {
            ConversationMode.entries.forEach { choice ->
                val selected = choice == mode
                val tint by animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(duration), label = "conversation-mode-text")
                Box(Modifier.weight(1f).heightIn(min = 48.dp)
                    .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(choice) })
                    .padding(horizontal = 12.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Text(choice.label, color = tint, style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}
