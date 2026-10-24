package com.moge.app.ui.solve

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

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
    val strings = rememberAppStrings()
    val duration = if (MogeTheme.motionEnabled) 240 else 0
    BoxWithConstraints(Modifier.widthIn(max = 360.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Leave compact landscape layouts to the header and composer.
        if (maxHeight >= 120.dp) AnimatedContent(mode,
            transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)) },
            label = "conversation-greeting") { selected ->
            Text(if (selected == ConversationMode.CHAT) strings.text(R.string.ui_what_shall_we_talk_about_ad45d) else strings.text(R.string.ui_what_would_you_like_to_solve_c1b45),
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center)
        }
    }
}
