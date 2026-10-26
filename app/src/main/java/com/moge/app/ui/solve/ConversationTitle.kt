package com.moge.app.ui.solve

import com.moge.app.ui.localization.label
import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

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
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.components.excludePageSwipe
import com.moge.app.ui.components.HighlightedTitle
import com.moge.app.ui.components.LocalPaperPageMotion
import com.moge.app.ui.components.paperHeaderTitleStyle
import com.moge.app.ui.components.paperHeaderPressColor
import com.moge.app.ui.theme.MogeTheme

private data class HeaderTitle(val text: String, val newMode: ConversationMode?)

/** New pages switch mode here; saved conversations keep their mode and reveal title changes. */
@Composable
internal fun ConversationTitle(
    title: String,
    newMode: ConversationMode? = null,
    switchEnabled: Boolean = true,
    onEditTitle: (() -> Unit)? = null,
    onSelectMode: (ConversationMode) -> Unit = {},
) {
    val strings = rememberAppStrings()
    val text = when (newMode) {
        ConversationMode.SOLVE -> strings.text(R.string.ui_new_question_f832f)
        ConversationMode.CHAT -> strings.text(R.string.ui_new_chat_1b7ab)
        null -> title
    }
    val motion = MogeTheme.motionEnabled
    val page = LocalPaperPageMotion.current
    val animateChanges = motion && page.active && !page.switching
    val target = HeaderTitle(text, newMode)
    var previous by remember { mutableStateOf(target) }
    // Loading a saved page is not a new title being written. Capture this decision
    // per title, so making an incoming page active cannot restart its reveal.
    val writeTitle = remember(target) {
        animateChanges && target != previous &&
            (previous.newMode != null || previous.text.isNotBlank())
    }
    SideEffect { previous = target }
    val interaction = remember { MutableInteractionSource() }
    val pressBackground = Modifier.background(
        paperHeaderPressColor(interaction, newMode == null || switchEnabled), MaterialTheme.shapes.small)
    val switch = if (newMode != null) Modifier.excludePageSwipe().testTag("conversation-mode-switch")
        .then(pressBackground)
        .clickable(interactionSource = interaction, indication = null,
            enabled = switchEnabled, role = Role.Button,
            onClickLabel = if (newMode == ConversationMode.SOLVE) strings.text(R.string.ui_switch_to_chat_1c3e0) else strings.text(R.string.ui_switch_to_solve_3e16b)) {
            onSelectMode(if (newMode == ConversationMode.SOLVE) ConversationMode.CHAT else ConversationMode.SOLVE)
        }.semantics { stateDescription = strings.text(R.string.ui_1_s_mode_3cfe2, strings.label(newMode)) }
    else if (onEditTitle != null) Modifier.excludePageSwipe().testTag("conversation-title")
        .then(pressBackground)
        .combinedClickable(interactionSource = interaction, indication = null,
            onClick = {}, onLongClickLabel = strings.text(R.string.ui_edit_conversation_title_9c8af), onLongClick = onEditTitle)
    else Modifier
    Box(switch.heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
        if (newMode == null) {
            // A saved title has one stable layout, including while its data loads.
            // AnimatedContent would briefly measure the empty outgoing title and
            // clip the incoming marquee even with a zero-duration transition.
            WritingTitle(text, animate = writeTitle, animateChanges = animateChanges, scroll = page.active)
        } else {
            AnimatedContent(target, contentAlignment = Alignment.CenterStart, transitionSpec = {
                val duration = if (animateChanges) 240 else 0
                (fadeIn(tween(duration)) + slideInVertically(tween(duration, easing = FastOutSlowInEasing)) { it / 4 })
                    .togetherWith(fadeOut(tween(if (animateChanges) 120 else 0)) +
                        slideOutVertically(tween(duration, easing = FastOutSlowInEasing)) { -it / 4 })
                    .using(SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> tween(duration) }))
            }, label = "conversation-title") { shown ->
                WritingTitle(shown.text, animate = false, animateChanges = animateChanges, scroll = page.active)
            }
        }
    }
}

/** Reveal the title ink without moving header actions. */
@Composable
private fun WritingTitle(title: String, animate: Boolean, animateChanges: Boolean, scroll: Boolean) {
    val motion = MogeTheme.motionEnabled
    val reveal = remember(title) { Animatable(if (animate && animateChanges) 0f else 1f) }
    val revealed by remember(reveal) { derivedStateOf { reveal.value >= 1f } }
    LaunchedEffect(reveal, animateChanges) {
        if (animateChanges) {
            val duration = (title.codePointCount(0, title.length) * 26).coerceIn(180, 600)
            reveal.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
        } else reveal.snapTo(1f)
    }
    HighlightedTitle(title, maxLines = 1, style = paperHeaderTitleStyle(),
        revealProgress = { reveal.value },
        // Keep the same viewport while revealing and dragging. An inactive page
        // starts at its leading text; its marquee begins only after navigation.
        textModifier = if (motion) Modifier.basicMarquee(
            iterations = if (revealed && scroll) Int.MAX_VALUE else 0,
            initialDelayMillis = 2_000,
            repeatDelayMillis = 2_000,
            spacing = MarqueeSpacing(36.dp),
            velocity = 18.dp,
        ) else Modifier)
}
