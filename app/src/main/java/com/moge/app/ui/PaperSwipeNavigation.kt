package com.moge.app.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import com.moge.app.ui.components.completePageSwipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.moge.app.domain.ConversationMode

internal fun newConversationSwipeTarget(nav: NavHostController, mode: ConversationMode? = null): PaperSwipeTarget {
    val previous = nav.previousBackStackEntry?.takeIf {
        it.destination.route == Routes.SOLVE && it.logicalConversationId() == null
    }
    return PaperSwipeTarget(Routes.solve(newMode = mode), previous) {
        if (previous != null && mode != null) previous.savedStateHandle[Routes.NEW_CONVERSATION_MODE_RESULT] = mode.name
    }
}

internal fun conversationSwipeTarget(nav: NavHostController, id: String): PaperSwipeTarget =
    PaperSwipeTarget(Routes.solve(id), nav.previousBackStackEntry?.takeIf {
        it.destination.route == Routes.SOLVE && it.logicalConversationId() == id
    })

internal class PaperSwipeTarget(
    val route: String,
    val previous: NavBackStackEntry? = null,
    val onCommit: () -> Unit = {},
)

internal class PaperSwipeSession(
    val source: NavBackStackEntry,
    val target: NavBackStackEntry,
    val direction: Int,
    val width: Float,
    val pushed: Boolean,
    val onCommit: () -> Unit,
) {
    var progress by mutableFloatStateOf(0f)
    var settling by mutableStateOf(false)
}

/**
 * A previous entry is previewed without popping it. A fresh entry is pushed tentatively,
 * so cancelling only removes that entry; the source owner and its draft stay intact.
 */
internal class PaperSwipeNavigation(
    private val nav: NavHostController,
    private val navigator: ComposeNavigator,
    private val scope: CoroutineScope,
) {
    var session by mutableStateOf<PaperSwipeSession?>(null)
        private set
    var finishing by mutableStateOf<List<NavBackStackEntry>?>(null)
        private set
    var parked by mutableStateOf<NavBackStackEntry?>(null)
        private set
    var parkedSide = 1
        private set
    var motionEnabled = true
    var minimumVelocity = 600f
    var beginPaperPose: (() -> Unit)? = null
    var finishPaperPose: (suspend (Boolean) -> Unit)? = null
    private var settlingJob: Job? = null

    fun start(source: NavBackStackEntry, target: PaperSwipeTarget, direction: Int, width: Float): Boolean {
        if (session != null || finishing != null || nav.currentBackStackEntry?.id != source.id || width <= 0) return false
        val previous = target.previous?.takeIf { it.id == nav.previousBackStackEntry?.id }
        val destination = if (previous != null) {
            navigator.prepareForTransition(source)
            navigator.prepareForTransition(previous)
            previous
        } else {
            nav.navigate(target.route)
            nav.currentBackStackEntry ?: return false
        }
        if (source.id == destination.id) return false
        beginPaperPose?.invoke()
        session = PaperSwipeSession(source, destination, direction, width, previous == null, target.onCommit)
        return true
    }

    fun drag(displacement: Float) {
        session?.takeUnless { it.settling }?.let {
            it.progress = (displacement * it.direction / it.width).coerceIn(0f, 1f)
        }
    }

    fun release(velocity: Float, cancelled: Boolean) {
        val swipe = session?.takeUnless { it.settling } ?: return
        swipe.settling = true
        val commit = !cancelled && completePageSwipe(swipe.progress, velocity * swipe.direction, minimumVelocity)
        settlingJob = scope.launch {
            if (motionEnabled) animate(swipe.progress, if (commit) 1f else 0f,
                initialVelocity = velocity * swipe.direction / swipe.width,
                animationSpec = spring(dampingRatio = 1f, stiffness = 550f)) { value, _ ->
                swipe.progress = value.coerceIn(0f, 1f)
            }
            swipe.progress = if (commit) 1f else 0f
            if (session !== swipe) return@launch
            val expected = if (swipe.pushed) swipe.target else swipe.source
            if (nav.currentBackStackEntry?.id != expected.id) {
                // An external share/deep link may replace navigation while a finger is down.
                finishPaperPose?.invoke(false)
                finishing = listOf(swipe.source, swipe.target)
                session = null
                return@launch
            }
            finishPaperPose?.invoke(commit)
            if (commit) {
                if (!swipe.pushed) nav.popBackStack()
                swipe.onCommit()
            } else if (swipe.pushed) nav.popBackStack()
            if (commit && swipe.pushed) {
                parked = swipe.source
                parkedSide = swipe.direction
            } else if (!commit && !swipe.pushed) {
                parked = swipe.target
                parkedSide = -swipe.direction
            }
            // The host removes the outgoing composition before releasing its lifecycle/VM.
            finishing = listOf(swipe.source, swipe.target)
            session = null
        }
    }

    /** Do not save a tentative destination when the activity pauses or rotates mid-drag. */
    fun cancelImmediately() {
        val swipe = session ?: return
        settlingJob?.cancel()
        if (swipe.pushed && nav.currentBackStackEntry?.id == swipe.target.id) nav.popBackStack()
        finishing = listOf(swipe.source, swipe.target)
        session = null
        scope.launch { finishPaperPose?.invoke(false) }
    }

    fun finishTransitions() {
        finishing?.forEach(navigator::onTransitionComplete)
        finishing = null
    }
}
