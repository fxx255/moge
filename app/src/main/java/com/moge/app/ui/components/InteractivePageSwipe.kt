package com.moge.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Direct manipulation is separate from the screen's accessible navigation actions. */
internal interface InteractivePageSwipe {
    fun start(direction: Int, width: Float): Boolean
    fun drag(displacement: Float)
    fun release(velocity: Float, cancelled: Boolean)
}

internal val LocalInteractivePageSwipe = staticCompositionLocalOf<InteractivePageSwipe?> { null }

/** A deliberate reverse fling cancels even after crossing the distance threshold. */
internal fun completePageSwipe(progress: Float, velocity: Float, minimumVelocity: Float): Boolean = when {
    velocity <= -minimumVelocity -> false
    progress >= 0.32f -> true
    else -> progress > 0.04f && velocity >= minimumVelocity
}
