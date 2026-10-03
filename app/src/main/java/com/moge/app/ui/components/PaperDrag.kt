package com.moge.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal sealed interface PaperDropTarget {
    data class Category(val id: String?, val name: String) : PaperDropTarget
    data object Delete : PaperDropTarget
}
internal data class PaperDrop(val ids: Set<String>, val target: PaperDropTarget)
internal data class PaperDragVisual(
    val id: String, val title: String, val ids: Set<String>, val origin: Rect,
    val grab: Offset, val start: Offset, val pointer: Offset,
)

/** Root coordinates let cards in either kind of lazy list share the same drop targets. */
@Stable
internal class PaperDragState {
    var visual by mutableStateOf<PaperDragVisual?>(null); private set
    var held by mutableStateOf(false); private set
    var hovered by mutableStateOf<PaperDropTarget?>(null); private set
    var settling by mutableStateOf(false); private set
    var rootBounds by mutableStateOf(Rect.Zero)
    var stripBounds: Rect = Rect.Zero
    private val targets = mutableMapOf<PaperDropTarget, Rect>()
    private var moved = false
    private var slop = 0f

    fun begin(id: String, title: String, ids: Set<String>, bounds: Rect, local: Offset, touchSlop: Float) {
        if (held || bounds.isEmpty) return
        targets.clear()
        stripBounds = Rect.Zero
        val point = bounds.topLeft + local
        visual = PaperDragVisual(id, title, ids.toSet(), bounds, local, point, point)
        held = true; settling = false; moved = false; hovered = null; slop = touchSlop
    }
    fun move(delta: Offset) {
        if (!held) return
        val current = visual ?: return
        val next = current.pointer + delta
        visual = current.copy(pointer = next)
        if ((next - current.start).getDistance() >= slop) moved = true
        updateHover()
    }
    fun targetBounds(target: PaperDropTarget, bounds: Rect) {
        targets[target] = bounds
        updateHover()
    }
    private fun updateHover() {
        val point = visual?.pointer ?: return
        hovered = if (!held || !moved) null else targets.entries.firstOrNull { (target, rect) ->
            rect.contains(point) && (target == PaperDropTarget.Delete || stripBounds.contains(point))
        }?.key
    }
    fun release(cancelled: Boolean = false): PaperDrop? {
        if (!held) return null
        val current = visual ?: return null
        val target = hovered.takeUnless { cancelled || !moved }
        val destination = target?.let { targets[it]?.center } ?: current.start
        visual = current.copy(pointer = destination)
        held = false; settling = true; hovered = null
        return target?.let { PaperDrop(current.ids, it) }
    }
    fun finishSettling() { if (!held) { visual = null; settling = false; targets.clear() } }
}

@Composable
internal fun rememberPaperDragState() = remember { PaperDragState() }

/** Long hold selects once; lifting without movement does not execute a drop. */
internal fun Modifier.paperDragSource(
    state: PaperDragState, id: String, title: String, selectedIds: Set<String>, enabled: Boolean,
    onSelect: (String) -> Unit, onDrop: (PaperDrop) -> Unit,
): Modifier = composed {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val currentSelection by rememberUpdatedState(selectedIds)
    val currentTitle by rememberUpdatedState(title)
    val select by rememberUpdatedState(onSelect)
    val drop by rememberUpdatedState(onDrop)
    val haptic = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val sourceAlpha by animateFloatAsState(if (state.visual?.id == id && state.held) 0.3f else 1f,
        if (MogeTheme.motionEnabled) tween(160) else snap(), label = "drag-source-alpha")
    this.onGloballyPositioned { bounds = it.boundsInRoot() }
        .graphicsLayer { alpha = sourceAlpha }
        .semantics {
            if (enabled) onLongClick("选择并拖动") {
                if (id !in currentSelection) select(id)
                true
            }
        }
        .pointerInput(state, id, enabled) {
            if (enabled) detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    if (!state.held) {
                        val ids = currentSelection + id
                        if (id !in currentSelection) select(id)
                        state.begin(id, currentTitle, ids, bounds, point, touchSlop)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, amount -> change.consume(); state.move(amount) },
                onDragEnd = { state.release()?.let(drop) },
                onDragCancel = { state.release(cancelled = true) },
            )
        }
}

@Composable
internal fun PaperDragOverlay(state: PaperDragState, categories: List<NotebookCategoryEntity>, modifier: Modifier = Modifier) {
    val motion = MogeTheme.motionEnabled
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state.hovered) {
        if (state.hovered != null) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    LaunchedEffect(state.held, state.settling, motion) {
        if (state.settling) { if (motion) delay(280); state.finishSettling() }
        if (state.held) scroll.scrollTo(0)
    }
    // Hovering either edge reveals groups that don't fit on screen, with the same finger.
    LaunchedEffect(state.held, state.visual?.pointer) {
        val initial = state.visual?.pointer ?: return@LaunchedEffect
        val strip = state.stripBounds
        val margin = with(density) { 36.dp.toPx() }
        if (!state.held || !strip.contains(initial) || (initial.x >= strip.left + margin && initial.x <= strip.right - margin)) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (state.held) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(0.05f)
            lastFrame = frame
            val point = state.visual?.pointer ?: continue
            val rect = state.stripBounds
            val edge = with(density) { 36.dp.toPx() }
            if (rect.contains(point)) {
                val direction = when {
                    point.x < rect.left + edge -> -1f
                    point.x > rect.right - edge -> 1f
                    else -> 0f
                }
                if (direction != 0f) {
                    val next = (scroll.value + direction * with(density) { 220.dp.toPx() } * seconds).roundToInt().coerceIn(0, scroll.maxValue)
                    if (next == scroll.value) break
                    scroll.scrollTo(next)
                } else break
            }
        }
    }
    Box(modifier.onGloballyPositioned { state.rootBounds = it.boundsInRoot() }) {
        AnimatedVisibility(state.held, Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(if (motion) 160 else 0)) + slideInVertically(tween(if (motion) 200 else 0)) { -it / 3 },
            exit = fadeOut(tween(if (motion) 120 else 0)) + slideOutVertically(tween(if (motion) 160 else 0)) { -it / 3 }) {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("向上拖入分组 · 靠近两侧可查看更多", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp))
                    Row(Modifier.fillMaxWidth().onGloballyPositioned { state.stripBounds = it.boundsInRoot() }
                        .horizontalScroll(scroll).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val choices = listOf(PaperDropTarget.Category(null, "未分类")) + categories.map { PaperDropTarget.Category(it.id, it.name) }
                        choices.forEach { target ->
                            val highlighted = state.hovered == target
                            val scale by animateFloatAsState(if (highlighted) 1.04f else 1f, if (motion) tween(140) else snap(), label = "drop-group")
                            Surface(color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                border = BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) MaterialTheme.colorScheme.primary else MogeTheme.paper.cardStroke),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.width(136.dp).heightIn(min = 64.dp).testTag("drop-category-${target.id ?: "none"}")
                                    .onGloballyPositioned { state.targetBounds(target, it.boundsInRoot()) }
                                    .graphicsLayer { scaleX = scale; scaleY = scale }) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp))
                                    Text(target.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
        AnimatedVisibility(state.held, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(if (motion) 160 else 0)) + slideInVertically(tween(if (motion) 200 else 0)) { it / 3 },
            exit = fadeOut(tween(if (motion) 120 else 0)) + slideOutVertically(tween(if (motion) 160 else 0)) { it / 3 }) {
            val armed = state.hovered == PaperDropTarget.Delete
            Surface(color = if (armed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.errorContainer,
                contentColor = if (armed) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onErrorContainer,
                border = BorderStroke(if (armed) 3.dp else 1.dp, MaterialTheme.colorScheme.error), shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(12.dp).heightIn(min = 76.dp).testTag("drop-delete")
                    .onGloballyPositioned { state.targetBounds(PaperDropTarget.Delete, it.boundsInRoot()) }) {
                Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(if (armed) 32.dp else 28.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(if (armed) "松手删除 ${state.visual?.ids?.size ?: 1} 项" else "向下拖到红色区域删除", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        state.visual?.let { visual ->
            var lifted by remember(visual.id) { mutableStateOf(!motion) }
            LaunchedEffect(state.held) { lifted = state.held }
            val point by animateOffsetAsState(visual.pointer, if (!motion || state.held) snap() else tween(260), label = "paper-drop-position")
            val alpha by animateFloatAsState(if (lifted) 1f else 0f, if (motion) tween(260) else snap(), label = "paper-drop-alpha")
            val scale by animateFloatAsState(if (lifted) 1.04f else 0.96f, if (motion) tween(260) else snap(), label = "paper-drop-scale")
            val width = with(density) { visual.origin.width.toDp() }.coerceIn(160.dp, 300.dp)
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary), shadowElevation = 12.dp,
                modifier = Modifier.width(width).offset {
                    val offset = point - visual.grab - state.rootBounds.topLeft
                    IntOffset(offset.x.roundToInt(), offset.y.roundToInt())
                }.graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale; rotationZ = -1.5f }
                    .testTag("drag-paper")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(visual.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("已选 ${visual.ids.size} 项 · 拖离目标可取消", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
