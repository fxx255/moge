package com.moge.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal sealed interface PaperDropTarget {
    data class Category(val id: String?, val name: String) : PaperDropTarget
    data object Delete : PaperDropTarget
}
internal data class PaperDrop(val ids: Set<String>, val target: PaperDropTarget)
internal data class PaperDragVisual(
    val id: String, val title: String, val ids: Set<String>, val origin: Rect,
    val grab: Offset, val start: Offset, val pointer: Offset,
    val preview: (@Composable () -> Unit)? = null,
)

/** Preserve the card's proportions; it fits just inside a category once it has left its origin. */
internal fun paperDragScale(origin: Rect, displacement: Offset, categoryWidth: Float, categoryHeight: Float): Float {
    if (origin.width <= 0f || origin.height <= 0f || !displacement.x.isFinite() || !displacement.y.isFinite() ||
        categoryWidth <= 0f || categoryHeight <= 0f) return 1f
    val compact = (min(categoryWidth / origin.width, categoryHeight / origin.height) * 0.9f).coerceIn(0.01f, 0.95f)
    // Reach the compact preview after a shorter travel distance so a drag
    // does not need to cross the whole card before it visually settles.
    val distance = (max(abs(displacement.x) / origin.width, abs(displacement.y) / origin.height) / 0.6f)
        .coerceIn(0f, 1f)
    val progress = distance * distance * (3f - 2f * distance)
    return 1f + (compact - 1f) * progress
}

/** Root coordinates let cards in either kind of lazy list share the same drop targets. */
@Stable
internal class PaperDragState {
    var visual by mutableStateOf<PaperDragVisual?>(null); private set
    var held by mutableStateOf(false); private set
    var hovered by mutableStateOf<PaperDropTarget?>(null); private set
    var settling by mutableStateOf(false); private set
    var categoriesExpanded by mutableStateOf(false); private set
    var settlementTarget by mutableStateOf<PaperDropTarget?>(null); private set
    var categoryCardSize by mutableStateOf(Size.Zero); private set
    private var rootRect by mutableStateOf(Rect.Zero)
    var rootBounds: Rect
        get() = rootRect
        set(value) { rootRect = value; updateHover() }
    var categoryPromptBounds: Rect = Rect.Zero
        set(value) {
            field = value
            visual?.pointer?.let { expandCategories(it, it) }
            updateHover()
        }
    var categoryActivationBounds: Rect = Rect.Zero
        set(value) { field = value; visual?.pointer?.let { expandCategories(it, it) }; updateHover() }
    var curvedDeleteZone = false
    var categoryBounds: Rect = Rect.Zero
        set(value) { field = value; updateHover() }
    // Resolve live coordinates at release too: scrolling, clipping, and disposal can change
    // which part of a target is visible without another finger movement.
    private val targets = mutableMapOf<PaperDropTarget, () -> Rect>()
    private var moved = false
    private var slop = 0f

    fun begin(
        id: String, title: String, ids: Set<String>, bounds: Rect, local: Offset, touchSlop: Float,
        preview: (@Composable () -> Unit)? = null,
    ) {
        if (held || bounds.isEmpty) return
        targets.clear()
        categoryPromptBounds = Rect.Zero
        categoryBounds = Rect.Zero
        categoriesExpanded = false
        categoryCardSize = Size.Zero
        val point = bounds.topLeft + local
        visual = PaperDragVisual(id, title, ids.toSet(), bounds, local, point, point, preview)
        held = true; settling = false; moved = false; hovered = null; settlementTarget = null; slop = touchSlop
    }

    fun move(delta: Offset) {
        if (!held) return
        val current = visual ?: return
        val next = current.pointer + delta
        visual = current.copy(pointer = next)
        val distance = (next - current.start).getDistance()
        if (distance > 0f && distance >= slop) moved = true
        expandCategories(current.pointer, next)
        updateHover()
    }

    private fun expandCategories(previous: Offset, next: Offset) {
        val current = visual ?: return
        if (!held || !moved || categoriesExpanded || next.y >= current.start.y) return
        val prompt = (categoryActivationBounds.takeUnless { it.isEmpty } ?: categoryPromptBounds).intersect(rootBounds)
        if (prompt.isEmpty) return
        val crossesPrompt = if (next.y < previous.y && previous.y >= prompt.top && next.y < prompt.bottom) {
            val y = previous.y.coerceIn(prompt.top, prompt.bottom)
            val fraction = (previous.y - y) / (previous.y - next.y)
            val x = previous.x + (next.x - previous.x) * fraction
            x >= prompt.left && x < prompt.right
        } else false
        if (prompt.contains(next) || crossesPrompt) categoriesExpanded = true
    }

    fun targetBounds(target: PaperDropTarget, bounds: Rect) {
        targets[target] = { bounds }
        updateCategorySize()
        updateHover()
    }

    fun targetBounds(target: PaperDropTarget, coordinates: LayoutCoordinates) {
        targets[target] = {
            if (coordinates.isAttached) {
                val origin = coordinates.localToRoot(Offset.Zero)
                Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
            } else Rect.Zero
        }
        updateCategorySize()
        updateHover()
    }

    fun removeTarget(target: PaperDropTarget) {
        targets.remove(target)
        updateCategorySize()
        updateHover()
    }

    private fun updateCategorySize() {
        val card = targets.filterKeys { it is PaperDropTarget.Category }.values
            .map { it() }.filterNot { it.isEmpty }.minByOrNull { it.height }
        if (card != null) categoryCardSize = card.size
    }

    private fun visibleTargetBounds(target: PaperDropTarget): Rect {
        val bounds = (targets[target]?.invoke() ?: Rect.Zero).intersect(rootBounds)
        return if (target is PaperDropTarget.Category) {
            if (categoriesExpanded) bounds.intersect(categoryBounds) else Rect.Zero
        } else bounds
    }

    private fun updateHover() {
        val point = visual?.pointer ?: return
        hovered = if (!held || !moved) null else targets.keys.firstOrNull { target ->
            val bounds = visibleTargetBounds(target)
            !bounds.isEmpty && bounds.contains(point) &&
                (target != PaperDropTarget.Delete || !curvedDeleteZone || curvedDeleteContains(bounds, point))
        }
    }

    fun release(cancelled: Boolean = false): PaperDrop? {
        if (!held) return null
        val current = visual ?: return null
        updateHover()
        val target = hovered.takeUnless { cancelled || !moved }
        settlementTarget = target
        val destination = target?.let { visibleTargetBounds(it).center } ?: current.start
        visual = current.copy(pointer = destination)
        held = false; settling = true; hovered = null; categoriesExpanded = false
        targets.clear()
        categoryPromptBounds = Rect.Zero
        categoryBounds = Rect.Zero
        return target?.let { PaperDrop(current.ids, it) }
    }

    fun finishSettling() {
        if (!held) { visual = null; settling = false; settlementTarget = null; targets.clear() }
    }
}

@Composable
internal fun rememberPaperDragState() = remember { PaperDragState() }

internal fun Modifier.paperCategoryActivation(state: PaperDragState): Modifier = composed {
    DisposableEffect(state) { onDispose { state.categoryActivationBounds = Rect.Zero } }
    onGloballyPositioned { state.categoryActivationBounds = it.boundsInRoot() }
}

/** Long hold selects once; lifting without movement does not execute a drop. */
internal fun Modifier.paperDragSource(
    state: PaperDragState, id: String, title: String, selectedIds: Set<String>, enabled: Boolean,
    onSelect: (String) -> Unit, onDrop: (PaperDrop) -> Unit,
    preview: (@Composable () -> Unit)? = null,
): Modifier = composed {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val currentSelection by rememberUpdatedState(selectedIds)
    val currentTitle by rememberUpdatedState(title)
    val currentPreview by rememberUpdatedState(preview)
    val select by rememberUpdatedState(onSelect)
    val drop by rememberUpdatedState(onDrop)
    val haptic = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val sourceAlpha by animateFloatAsState(if (state.visual?.id == id && state.held) 0.3f else 1f,
        if (MogeTheme.motionEnabled) tween(160) else snap(), label = "drag-source-alpha")
    this.onGloballyPositioned {
        // A partially visible card still lifts at its full measured size.
        val origin = it.localToRoot(Offset.Zero)
        bounds = Rect(origin.x, origin.y, origin.x + it.size.width, origin.y + it.size.height)
    }
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
                        state.begin(id, currentTitle, ids, bounds, point, touchSlop, currentPreview)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                },
                onDrag = { change, amount -> change.consume(); state.move(amount) },
                onDragEnd = { state.release()?.let(drop) },
                onDragCancel = { state.release(cancelled = true) },
            )
        }
}

@OptIn(ExperimentalLayoutApi::class)
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
    BoxWithConstraints(modifier.onGloballyPositioned { state.rootBounds = it.boundsInRoot() }) {
        val deleteHeight = maxHeight * 0.3f
        val categoryWidth = ((maxWidth - 32.dp) / 2).coerceIn(80.dp, 160.dp)
        val categoryTop = with(density) { (state.categoryActivationBounds.top - state.rootBounds.top).coerceAtLeast(0f).toDp() }
        val gridMaxHeight = (maxHeight - deleteHeight - categoryTop - 52.dp).coerceAtLeast(0.dp)
        AnimatedVisibility(state.held, Modifier.align(Alignment.TopCenter).padding(top = categoryTop),
            enter = fadeIn(tween(if (motion) 160 else 0)) + slideInVertically(tween(if (motion) 200 else 0)) { -it / 3 },
            exit = fadeOut(tween(if (motion) 120 else 0)) + slideOutVertically(tween(if (motion) 160 else 0)) { -it / 3 }) {
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp, modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text(if (state.categoriesExpanded) "松手放入分组" else "向上拖入分组，展开全部分组",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.fillMaxWidth().testTag("drop-category-prompt")
                            .onGloballyPositioned { state.categoryPromptBounds = it.boundsInRoot() }
                            .padding(horizontal = 16.dp, vertical = 12.dp))
                    // This outer box follows the animated height, so unrevealed rows cannot
                    // receive drops even though AnimatedVisibility has already composed them.
                    Box(Modifier.fillMaxWidth().clipToBounds()
                        .onGloballyPositioned { state.categoryBounds = it.boundsInRoot() }) {
                        androidx.compose.animation.AnimatedVisibility(state.categoriesExpanded,
                            enter = expandVertically(tween(if (motion) 220 else 0), expandFrom = Alignment.Top) + fadeIn(tween(if (motion) 160 else 0)),
                            exit = shrinkVertically(tween(if (motion) 160 else 0), shrinkTowards = Alignment.Top) + fadeOut(tween(if (motion) 120 else 0))) {
                            FlowRow(Modifier.fillMaxWidth().heightIn(max = gridMaxHeight).verticalScroll(scroll)
                                .testTag("drop-category-grid").padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val choices = listOf(PaperDropTarget.Category(null, "未分类")) + categories.map { PaperDropTarget.Category(it.id, it.name) }
                                choices.forEach { target ->
                                    key(target.id) {
                                        DisposableEffect(state, target) { onDispose { state.removeTarget(target) } }
                                        val highlighted = state.hovered == target
                                        Surface(color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                            border = BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) MaterialTheme.colorScheme.primary else MogeTheme.paper.cardStroke),
                                            shape = MaterialTheme.shapes.small,
                                            modifier = Modifier.width(categoryWidth).heightIn(min = 64.dp).testTag("drop-category-${target.id ?: "none"}")
                                                .onGloballyPositioned { state.targetBounds(target, it) }) {
                                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp))
                                                Text(target.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                            }
                                        }
                                    }
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
            DisposableEffect(state) { onDispose { state.removeTarget(PaperDropTarget.Delete) } }
            val armed = state.hovered == PaperDropTarget.Delete
            PaperDeleteZone(armed, if (armed) "松手删除 ${state.visual?.ids?.size ?: 1} 项" else "向下拖到红色区域删除",
                Modifier.fillMaxWidth().height(deleteHeight).testTag("drop-delete")
                    .onGloballyPositioned { state.curvedDeleteZone = true; state.targetBounds(PaperDropTarget.Delete, it) })
        }
        state.visual?.let { visual ->
            var lifted by remember(visual.id) { mutableStateOf(!motion) }
            LaunchedEffect(state.held) { lifted = state.held }
            val point by animateOffsetAsState(visual.pointer, if (!motion || state.held) snap() else tween(260), label = "paper-drop-position")
            val alpha by animateFloatAsState(if (lifted) 1f else 0f, if (motion) tween(260) else snap(), label = "paper-drop-alpha")
            val width = with(density) { visual.origin.width.toDp() }
            val height = with(density) { visual.origin.height.toDp() }
            val categoryHeightPx = state.categoryCardSize.height.takeIf { it > 0f } ?: with(density) { 64.dp.toPx() }
            val categoryWidthPx = state.categoryCardSize.width.takeIf { it > 0f } ?: with(density) { categoryWidth.toPx() }
            val displacement = if (state.settling && state.settlementTarget != null) {
                Offset(visual.origin.width, visual.origin.height)
            } else visual.pointer - visual.start
            val scale by animateFloatAsState(
                paperDragScale(visual.origin, displacement, categoryWidthPx, categoryHeightPx),
                if (!motion) snap() else tween(if (state.held) 80 else 260), label = "paper-drag-scale")
            val shadow = with(density) { 12.dp.toPx() }
            val previewShape = MaterialTheme.shapes.small
            Box(Modifier.offset {
                val offset = point - visual.grab - state.rootBounds.topLeft
                IntOffset(offset.x.roundToInt(), offset.y.roundToInt())
            }.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(width, height)
                .graphicsLayer {
                    this.alpha = alpha; shadowElevation = shadow; shape = previewShape
                    scaleX = scale; scaleY = scale
                    transformOrigin = TransformOrigin((visual.grab.x / visual.origin.width).coerceIn(0f, 1f),
                        (visual.grab.y / visual.origin.height).coerceIn(0f, 1f))
                }
                .testTag("drag-paper"), propagateMinConstraints = true) {
                visual.preview?.invoke()
            }
        }
    }
}
