package com.moge.app.ui.notebook

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme
import kotlin.math.sign

internal const val CATEGORY_DELETE_HEIGHT_FRACTION = 0.24f

/** The lower scroll band sits above the deletion target, including its curved corners. */
internal fun categoryScrollDirection(pointer: Offset, list: Rect, deletion: Rect, edge: Float): Float {
    if (list.isEmpty || edge <= 0f || pointer.x !in list.left..list.right || pointer.y < list.top ||
        !deletion.isEmpty && deletion.contains(pointer)) return 0f
    val bottom = if (deletion.isEmpty) list.bottom else minOf(list.bottom, deletion.top)
    if (pointer.y > bottom || bottom <= list.top) return 0f
    val band = minOf(edge, (bottom - list.top) / 2f)
    return when {
        pointer.y < list.top + band -> -((list.top + band - pointer.y) / band).coerceIn(0f, 1f)
        pointer.y > bottom - band -> ((pointer.y - bottom + band) / band).coerceIn(0f, 1f)
        else -> 0f
    }
}

/** Keeps scrolling with a stationary finger; a single elastic nudge marks each reached edge. */
@Composable
internal fun rememberCategoryAutoScroll(drag: CategoryDragState, list: LazyListState): State<Float> {
    val bounce = remember { Animatable(0f) }
    val density = LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    val speed = with(density) { 520.dp.toPx() }
    val nudge = with(density) { 12.dp.toPx() }
    val motion = MogeTheme.motionEnabled
    val band = if (!drag.movedBeyondSlop) 0f else drag.visual?.pointer?.let {
        categoryScrollDirection(it, drag.listBounds, drag.deleteBounds, edge).sign
    } ?: 0f
    // Entering/leaving a band restarts the job. Pointer movement within that band must
    // not reset frame timing, otherwise continuous dragging can starve auto-scrolling.
    LaunchedEffect(drag.held, band, list, edge, speed, motion) {
        if (!drag.held || !drag.movedBeyondSlop) {
            bounce.animateTo(0f, tween(if (motion) 120 else 0))
            return@LaunchedEffect
        }
        var previous = 0L
        while (drag.held) {
            val pointer = drag.visual?.pointer ?: break
            val direction = if (drag.deleteArmed) 0f else categoryScrollDirection(pointer, drag.listBounds, drag.deleteBounds, edge)
            if (direction == 0f) {
                bounce.animateTo(0f, tween(if (motion) 120 else 0))
                break
            }
            val frame = withFrameNanos { it }
            val elapsed = if (previous == 0L) 0f else ((frame - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previous = frame
            if (elapsed == 0f) continue
            val consumed = list.scrollBy(direction * speed * elapsed)
            drag.refreshTargets()
            if (consumed == 0f) {
                if (motion) {
                    bounce.animateTo(if (direction > 0f) -nudge else nudge, tween(70))
                    bounce.animateTo(0f, tween(150))
                }
                break
            }
        }
    }
    return bounce.asState()
}
