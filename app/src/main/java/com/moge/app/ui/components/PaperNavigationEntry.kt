package com.moge.app.ui.components

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.moge.app.ui.theme.MogeTheme

internal class PaperPageMotion(
    val readingModifier: Modifier = Modifier,
    val active: Boolean = true,
    val headerAlpha: () -> Float = { 1f },
    val headerOffsetX: () -> Float = { 0f },
    val swipeTarget: Boolean = false,
    val switching: Boolean = false,
    val swipeDirection: Int = 0,
    val swipeProgress: () -> Float = { 0f },
    val composerActive: Boolean = active,
    val composerExitFraction: () -> Float = { 0f },
)

internal val LocalPaperPageMotion = staticCompositionLocalOf { PaperPageMotion() }

/** Navigation animates reading layers; the paper and controls keep their own fixed geometry. */
@Composable
internal fun AnimatedContentScope.PaperNavigationEntry(
    direction: Int = 0,
    openingCard: Boolean = false,
    closingCard: Boolean = false,
    content: @Composable () -> Unit,
) {
    val motion = MogeTheme.motionEnabled
    val opacity by transition.animateFloat(transitionSpec = { tween(if (motion) 180 else 0) },
        label = "paper-header-ink") { if (it == EnterExitState.Visible) 1f else 0f }
    val reading = Modifier.animateEnterExit(
        enter = when {
            !motion -> EnterTransition.None
            direction != 0 -> slideInHorizontally(tween(240)) { -direction * it }
            openingCard -> paperPageEnter(true)
            else -> fadeIn(tween(180))
        },
        exit = when {
            !motion -> ExitTransition.None
            direction != 0 -> slideOutHorizontally(tween(240)) { direction * it }
            closingCard -> paperPageExit(true)
            else -> fadeOut(tween(120))
        }, label = "paper-reading-layer",
    )
    CompositionLocalProvider(LocalPaperPageMotion provides PaperPageMotion(
        reading, transition.targetState == EnterExitState.Visible, { opacity },
    ), content = content)
}
