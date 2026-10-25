package com.moge.app.ui.solve

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.components.LocalPaperPageMotion

/** Reading layers animate independently; the target page alone owns the stationary composer. */
data class ConversationPageMotion(
    val readingModifier: Modifier = Modifier,
    val active: Boolean = true,
    val switching: Boolean = false,
    val direction: Int = 0,
    val progress: (() -> Float)? = null,
)

@Composable
internal fun paperConversationPageMotion(): ConversationPageMotion {
    val page = LocalPaperPageMotion.current
    return ConversationPageMotion(page.readingModifier, page.active, page.switching,
        page.swipeDirection, page.swipeProgress.takeIf { page.switching })
}

@Composable
internal fun AnimatedContentScope.conversationPageMotion(
    direction: Int,
    stationaryComposer: Boolean,
): ConversationPageMotion {
    val animate = stationaryComposer && MogeTheme.motionEnabled && direction != 0
    val page = LocalPaperPageMotion.current
    return ConversationPageMotion(
        readingModifier = if (!stationaryComposer) page.readingModifier else Modifier.animateEnterExit(
            enter = if (animate) slideInHorizontally(tween(240)) { -direction * it } else EnterTransition.None,
            exit = if (animate) slideOutHorizontally(tween(240)) { direction * it } else ExitTransition.None,
            label = "conversation-reading-layer",
        ),
        active = transition.targetState == EnterExitState.Visible,
        switching = animate && transition.currentState != transition.targetState,
        direction = if (animate) direction else 0,
    )
}
