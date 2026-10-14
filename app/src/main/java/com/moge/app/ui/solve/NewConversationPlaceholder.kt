package com.moge.app.ui.solve

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.theme.MogeTheme

@Composable
internal fun NewConversationPlaceholder(mode: ConversationMode) {
    val duration = if (MogeTheme.motionEnabled) 240 else 0
    BoxWithConstraints(Modifier.widthIn(max = 360.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Leave compact landscape layouts to the header and composer.
        if (maxHeight >= 120.dp) AnimatedContent(mode,
            transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)) },
            label = "conversation-greeting") { selected ->
            Text(if (selected == ConversationMode.CHAT) "今天聊点什么？" else "有什么题目要解？",
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center)
        }
    }
}
