package com.moge.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import com.moge.app.ui.theme.MogeTheme

internal fun paperPageEnter(motion: Boolean) = scaleIn(tween(if (motion) 220 else 0), initialScale = 0.9f,
    transformOrigin = TransformOrigin(0.5f, 0.2f)) + fadeIn(tween(if (motion) 150 else 0))

internal fun paperPageExit(motion: Boolean) = scaleOut(tween(if (motion) 180 else 0), targetScale = 0.94f,
    transformOrigin = TransformOrigin(0.5f, 0.2f)) + fadeOut(tween(if (motion) 140 else 0))

/** Each saved entry unfolds once; loading its body cannot restart the page animation. */
@Composable
internal fun PaperPageReveal(entryId: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = MogeTheme.motionEnabled
    val visibility = remember(entryId, motion) {
        MutableTransitionState(entryId == null || !motion).apply { targetState = true }
    }
    AnimatedVisibility(visibility, modifier, enter = paperPageEnter(motion), exit = paperPageExit(motion)) { content() }
}
