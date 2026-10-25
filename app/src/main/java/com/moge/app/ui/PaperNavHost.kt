package com.moge.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.LocalOwnersProvider
import com.moge.app.ui.components.*
import com.moge.app.ui.theme.MogeTheme

internal val LocalPaperSwipeNavigation = staticCompositionLocalOf<PaperSwipeNavigation?> { null }

/**
 * Uses Navigation's real entry owners and saved state for both halves of a drag.
 * Keeping every entry under the same key also keeps native formula views and list state
 * in place when a preview becomes the current page or is dragged back.
 */
@Composable
internal fun PaperNavHost(
    nav: NavHostController,
    graph: NavGraph,
    openedCard: Pair<String, String>?,
    content: @Composable (NavBackStackEntry) -> Unit,
) {
    val owner = checkNotNull(LocalViewModelStoreOwner.current)
    nav.setViewModelStore(owner.viewModelStore)
    nav.graph = graph
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(nav, lifecycle) {
        nav.setLifecycleOwner(lifecycle)
        onDispose { }
    }
    val navigator = nav.navigatorProvider.getNavigator<ComposeNavigator>("composable")
    val coroutineScope = rememberCoroutineScope()
    val swipe = remember(nav, navigator) { PaperSwipeNavigation(nav, navigator, coroutineScope) }
    DisposableEffect(lifecycle, swipe) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) swipe.cancelImmediately()
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose {
            lifecycle.lifecycle.removeObserver(observer)
            swipe.cancelImmediately()
        }
    }
    val paper = LocalPaperNavigationScope.current
    swipe.motionEnabled = MogeTheme.motionEnabled
    swipe.minimumVelocity = with(LocalDensity.current) { 600 * density }
    swipe.beginPaperPose = { paper?.beginDrag() }
    swipe.finishPaperPose = { commit -> paper?.finishDrag(commit) }
    val visible by nav.visibleEntries.collectAsState()
    val backStack by navigator.backStack.collectAsState()
    val current = nav.currentBackStackEntry ?: backStack.lastOrNull()
    val session = swipe.session
    val finishing = swipe.finishing
    SideEffect { paper?.dragFraction = session?.let { { it.progress } } }
    // Keep at most one neighbour that still belongs to Navigation. Returning to a
    // formula-heavy page can then reuse its measured native views without rebuilding.
    val parked = swipe.parked?.takeIf { candidate -> backStack.any { it.id == candidate.id } && candidate.id != current?.id }
    val entries = (listOfNotNull(parked) + when {
        session != null -> listOf(session.source, session.target)
        finishing != null -> listOfNotNull(current)
        else -> visible.filter { it.destination.navigatorName == "composable" }
    }).distinctBy { it.id }
    val savedState = rememberSaveableStateHolder()
    val initialId = remember { current?.id }
    BackHandler(session != null || backStack.size > 1) {
        if (session != null) swipe.release(0f, cancelled = true) else nav.popBackStack()
    }
    LaunchedEffect(finishing) { if (finishing != null) swipe.finishTransitions() }
    DisposableEffect(navigator) {
        onDispose {
            (navigator.backStack.value + nav.visibleEntries.value + swipe.finishing.orEmpty() +
                listOfNotNull(swipe.session?.source, swipe.session?.target))
                .distinctBy { it.id }.forEach(navigator::onTransitionComplete)
        }
    }
    CompositionLocalProvider(LocalPaperSwipeNavigation provides swipe) {
        Box(Modifier.fillMaxSize().clipToBounds()) {
            entries.forEach { entry -> key(entry.id) {
                val inSwipe = session?.let { entry.id == it.source.id || entry.id == it.target.id } == true
                val active = if (session != null) entry.id == session.source.id else entry.id == current?.id
                val parkedOnly = entry.id == parked?.id && !inSwipe && !active
                val motion = MogeTheme.motionEnabled
                val opacity = remember { Animatable(if (entry.id == initialId || inSwipe || finishing != null) 1f else 0f) }
                LaunchedEffect(active, inSwipe, finishing, motion) {
                    if (!inSwipe && finishing == null && !parkedOnly) {
                        if (motion) opacity.animateTo(if (active) 1f else 0f, tween(if (active) 180 else 140))
                        else opacity.snapTo(if (active) 1f else 0f)
                        navigator.onTransitionComplete(entry)
                    } else opacity.snapTo(if (parkedOnly) 0f else 1f)
                }
                val translation = { if (inSwipe) {
                    session.direction * session.width * (session.progress - if (entry.id == session.target.id) 1 else 0)
                } else 0f }
                val card = openedCard?.second == entry.id
                val reading = Modifier.graphicsLayer {
                    translationX = translation()
                    alpha = if (inSwipe || finishing != null) 1f else opacity.value
                    if (parkedOnly) {
                        translationX = swipe.parkedSide * size.width
                        alpha = 0f
                    }
                    if (card && !inSwipe && motion) {
                        scaleX = 0.94f + 0.06f * opacity.value
                        scaleY = scaleX
                        transformOrigin = TransformOrigin(0.5f, 0.2f)
                    }
                    clip = true
                }
                CompositionLocalProvider(LocalPaperPageMotion provides PaperPageMotion(
                    readingModifier = reading,
                    active = active,
                    headerAlpha = { if (parkedOnly) 0f else if (inSwipe || finishing != null) 1f else opacity.value },
                    headerOffsetX = translation,
                    swipeTarget = inSwipe && !active,
                    switching = inSwipe,
                    swipeDirection = session?.direction ?: 0,
                    swipeProgress = { if (inSwipe) session?.progress ?: 0f else 0f },
                )) {
                    Box(Modifier.fillMaxSize().then(if (parkedOnly) Modifier.clearAndSetSemantics { } else Modifier)) {
                        entry.LocalOwnersProvider(savedState) { content(entry) }
                    }
                }
            } }
            if (session != null) Box(Modifier.fillMaxSize().pointerInput(Unit) {
                // A second touch during settling must not activate a button whose ink is moving.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            })
        }
    }
}

/** Resolve a neighbour at gesture start, using the latest page mode and conversation ID. */
@Composable
internal fun PaperSwipeDestination(
    entry: NavBackStackEntry,
    target: (Int) -> PaperSwipeTarget?,
    content: @Composable () -> Unit,
) {
    val navigation = LocalPaperSwipeNavigation.current
    val resolve by rememberUpdatedState(target)
    val gesture = remember(navigation, entry) { navigation?.let { host -> object : InteractivePageSwipe {
        override fun start(direction: Int, width: Float): Boolean =
            resolve(direction)?.let { host.start(entry, it, direction, width) } ?: false
        override fun drag(displacement: Float) = host.drag(displacement)
        override fun release(velocity: Float, cancelled: Boolean) = host.release(velocity, cancelled)
    } } }
    CompositionLocalProvider(LocalInteractivePageSwipe provides gesture, content = content)
}
