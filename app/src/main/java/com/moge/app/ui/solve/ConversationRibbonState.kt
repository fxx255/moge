package com.moge.app.ui.solve

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween

/** Shared by adjacent navigation entries so a restored page continues the same paper motion. */
class ConversationRibbonState(initialExisting: Boolean? = null) {
    private var initialized = initialExisting != null
    internal val progress = Animatable(if (initialExisting == true) 1f else 0f)

    /** Only the empty/existing state changes the pose; title and generation updates never do. */
    internal suspend fun show(existing: Boolean, motionEnabled: Boolean) {
        val target = if (existing) 1f else 0f
        if (!initialized || !motionEnabled) {
            initialized = true
            progress.snapTo(target)
        } else if (progress.value != target) {
            progress.animateTo(target, tween(520, easing = FastOutSlowInEasing))
        }
    }
}
