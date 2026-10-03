package com.moge.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private class SwipeExclusions {
    val regions = mutableMapOf<Any, LayoutCoordinates>()
    fun contains(point: Offset) = regions.values.any {
        it.isAttached && it.boundsInRoot().contains(point)
    }
}
private val LocalSwipeExclusions = staticCompositionLocalOf<SwipeExclusions?> { null }

/** Child scrollbars and the composer keep their own gestures, including native text selection. */
internal fun Modifier.excludePageSwipe(): Modifier = composed {
    val exclusions = LocalSwipeExclusions.current
    if (exclusions == null) this else {
        val key = remember { Any() }
        DisposableEffect(exclusions, key) { onDispose { exclusions.regions.remove(key) } }
        this.onGloballyPositioned { exclusions.regions[key] = it }
    }
}

/** Navigate on release after a short, single-finger horizontal gesture. */
@Composable
internal fun PageSwipeSurface(
    enabled: Boolean = true,
    onLeft: (() -> Unit)? = null,
    onRight: (() -> Unit)? = null,
    leftLabel: String = "向左切换页面",
    rightLabel: String = "向右切换页面",
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val exclusions = remember { SwipeExclusions() }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val left by rememberUpdatedState(onLeft)
    val right by rememberUpdatedState(onRight)
    val active by rememberUpdatedState(enabled)
    val config = LocalViewConfiguration.current
    Box(modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.localToRoot(Offset.Zero) }
        .semantics {
            customActions = if (!enabled) emptyList() else buildList {
                if (onLeft != null) add(CustomAccessibilityAction(leftLabel) { left?.invoke(); true })
                if (onRight != null) add(CustomAccessibilityAction(rightLabel) { right?.invoke(); true })
            }
        }
        .pointerInput(exclusions, config) {
            val threshold = 72.dp.toPx()
            awaitEachGesture {
                // Native selectable TextViews can consume DOWN; inspect before their handling.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!active || exclusions.contains(rootOrigin + down.position)) return@awaitEachGesture
                var horizontal = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (!active || event.changes.size != 1) break
                    val change = event.changes.single()
                    if (change.id != down.id) break
                    val delta = change.position - down.position
                    if (!horizontal) {
                        if (change.uptimeMillis - down.uptimeMillis >= config.longPressTimeoutMillis) break
                        if (abs(delta.y) > config.touchSlop && abs(delta.y) >= abs(delta.x)) break
                        if (abs(delta.x) > config.touchSlop * 2 && abs(delta.x) > abs(delta.y) * 1.5f) {
                            if ((delta.x < 0 && left == null) || (delta.x > 0 && right == null)) break
                            horizontal = true
                        }
                    }
                    if (horizontal) change.consume()
                    if (!change.pressed) {
                        if (horizontal && abs(delta.x) >= threshold && abs(delta.x) > abs(delta.y) * 1.5f) {
                            if (delta.x < 0) left?.invoke() else right?.invoke()
                        }
                        break
                    }
                }
            }
        }) {
        CompositionLocalProvider(LocalSwipeExclusions provides exclusions, content = content)
    }
}
