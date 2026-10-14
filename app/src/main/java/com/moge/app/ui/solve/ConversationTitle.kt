package com.moge.app.ui.solve

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.components.HighlightedTitle
import com.moge.app.ui.components.excludePageSwipe
import com.moge.app.ui.theme.MogeTheme

private data class HeaderTitle(val text: String, val newMode: ConversationMode?)

/** New pages switch mode here; saved conversations keep their mode and reveal title changes. */
@Composable
internal fun ConversationTitle(
    title: String,
    newMode: ConversationMode? = null,
    switchEnabled: Boolean = true,
    onSelectMode: (ConversationMode) -> Unit = {},
) {
    val text = when (newMode) {
        ConversationMode.SOLVE -> "新题目"
        ConversationMode.CHAT -> "新对话"
        null -> title
    }
    val motion = MogeTheme.motionEnabled
    val initial = remember { HeaderTitle(text, newMode) }
    val target = HeaderTitle(text, newMode)
    val switch = if (newMode != null) Modifier.excludePageSwipe().testTag("conversation-mode-switch")
        .clickable(enabled = switchEnabled, role = Role.Button,
            onClickLabel = if (newMode == ConversationMode.SOLVE) "切换到对话模式" else "切换到解题模式") {
            onSelectMode(if (newMode == ConversationMode.SOLVE) ConversationMode.CHAT else ConversationMode.SOLVE)
        }.semantics { stateDescription = "${newMode.label}模式" }
    else Modifier
    Box(switch.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
        AnimatedContent(target, contentAlignment = Alignment.CenterStart, transitionSpec = {
            val duration = if (motion) 240 else 0
            if (initialState.newMode != null && targetState.newMode != null) {
                (fadeIn(tween(duration)) + slideInVertically(tween(duration, easing = FastOutSlowInEasing)) { it / 4 })
                    .togetherWith(fadeOut(tween(if (motion) 120 else 0)) +
                        slideOutVertically(tween(duration, easing = FastOutSlowInEasing)) { -it / 4 })
                    .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(duration) }))
            } else {
                fadeIn(tween(if (motion) 120 else 0)).togetherWith(fadeOut(tween(if (motion) 100 else 0)))
                    .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(duration) }))
            }
        }, label = "conversation-title") { shown ->
            WritingTitle(shown.text, animate = shown.newMode == null && shown != initial)
        }
    }
}

/** Measure once, then reveal the ink and highlighter together without moving header actions. */
@Composable
private fun WritingTitle(title: String, animate: Boolean) {
    val motion = MogeTheme.motionEnabled
    val reveal = remember(title, animate, motion) { Animatable(if (animate && motion) 0f else 1f) }
    LaunchedEffect(reveal) {
        if (animate && motion) {
            val duration = (title.codePointCount(0, title.length) * 26).coerceIn(180, 600)
            reveal.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
        }
    }
    HighlightedTitle(title, maxLines = 1, modifier = Modifier.drawWithContent {
        if (reveal.value >= 1f) drawContent()
        else clipRect(right = size.width * reveal.value) { this@drawWithContent.drawContent() }
    })
}
