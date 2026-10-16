package com.moge.app.ui.notebook

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag as trackDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.ui.components.PaperDeleteZone
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The gesture lives above the lazy list so scrolling/reordering cannot dispose its pointer source. */
@Composable
internal fun CategoryManagerContent(
    categories: List<NotebookCategoryEntity>,
    busy: Boolean,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onReorder: (String, Int) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val drag = remember { CategoryDragState() }
    var creating by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var nameDraft by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val latestEditing by rememberUpdatedState(creating || renameId != null)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val list = listState
    val latestCategories by rememberUpdatedState(categories)
    val latestBusy by rememberUpdatedState(busy)
    val reorder by rememberUpdatedState(onReorder)
    val delete by rememberUpdatedState(onDelete)
    var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val motion = MogeTheme.motionEnabled
    val duration = if (motion) 260 else 0
    val sidePadding = with(density) { 16.dp.toPx() }

    fun cancelEdit() {
        renameId = null
        creating = false
        focus.clearFocus()
        keyboard?.hide()
    }
    fun startRename(category: NotebookCategoryEntity) {
        if (busy || drag.locked || renameId != null || creating) return
        nameDraft = TextFieldValue(category.name, TextRange(0, category.name.length))
        renameId = category.id
    }
    fun saveEdit() {
        val name = nameDraft.text.trim()
        if (busy || drag.locked || name.isEmpty() || name.codePointCount(0, name.length) > 80) return
        if (creating) onCreate(name)
        else {
            val id = renameId ?: return
            if (categories.none { it.id == id }) return
            onRename(id, name)
        }
        cancelEdit()
    }
    BackHandler(enabled = creating || renameId != null) { cancelEdit() }
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(creating, imeBottom) {
        if (creating) {
            withFrameNanos { }
            if (motion) list.animateScrollToItem(categories.size) else list.scrollToItem(categories.size)
        }
    }
    LaunchedEffect(categories.map { it.id }) {
        if (renameId != null && categories.none { it.id == renameId }) cancelEdit()
    }
    SideEffect {
        drag.sync(categories.map { it.id }, busy)
        drag.rowLayout = {
            list.layoutInfo.visibleItemsInfo.associate { item ->
                // layoutInfo offsets include content padding and exclude placement animation.
                item.key.toString() to Rect(
                    drag.listBounds.left + sidePadding, drag.listBounds.top + item.offset,
                    drag.listBounds.right - sidePadding, drag.listBounds.top + item.offset + item.size,
                )
            }
        }
    }
    DisposableEffect(drag) { onDispose { drag.cancel(); drag.finishSettling() } }

    LaunchedEffect(drag.pending, busy) {
        if (drag.pending && !busy) {
            val started = withFrameNanos { it }
            while (drag.pending && !latestBusy) {
                val now = withFrameNanos { it }
                if (now - started >= CATEGORY_PENDING_RECONCILE_MS * 1_000_000L) {
                    drag.reconcilePending(latestCategories.map { it.id }, latestBusy)
                    break
                }
            }
        }
    }

    LaunchedEffect(drag.settling, motion) {
        if (drag.settling) {
            // Let the final preview/cancel order lay out before returning to its live slot.
            withFrameNanos { }
            drag.updateSettlementTarget()
            if (duration > 0) delay(duration.toLong())
            drag.finishSettling()
        }
    }
    LaunchedEffect(drag.deleteArmed) {
        if (drag.deleteArmed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    val bounce by rememberCategoryAutoScroll(drag, list)

    val byId = categories.associateBy { it.id }
    // The initial composition precedes SideEffect; render the repository list immediately.
    val ids = if (drag.order.isEmpty() && categories.isNotEmpty()) categories.map { it.id } else drag.order
    val displayed = ids.mapNotNull(byId::get)
    val previousDisplayIds = remember { mutableListOf<String>() }
    SideEffect {
        val displayedIds = displayed.map { it.id }
        if (previousDisplayIds.isNotEmpty() && previousDisplayIds != displayedIds) {
            // Stable keys normally anchor the old first item. Moving that item must not
            // scroll the viewport along with the dragged card or with a rollback.
            list.requestScrollToItem(
                list.firstVisibleItemIndex.coerceAtMost((displayed.lastIndex).coerceAtLeast(0)),
                list.firstVisibleItemScrollOffset,
            )
        }
        previousDisplayIds.clear()
        previousDisplayIds.addAll(displayedIds)
    }
    val enabled = !busy && !drag.locked && renameId == null && !creating
    BoxWithConstraints(modifier.testTag("category-manager").semantics {
        stateDescription = if (drag.held) "正在拖动分类" else if (drag.pending || busy) "正在保存分类顺序" else "可以拖动排序"
    }.clipToBounds()
        .onGloballyPositioned { rootCoordinates = it; drag.rootBounds = it.boundsInRoot() }
        .pointerInput(drag) {
            awaitEachGesture {
                // Inspect the handle before child buttons consume DOWN; unrelated taps belong
                // entirely to those buttons and the list's ordinary scrolling recognizer.
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val coordinates = rootCoordinates?.takeIf { it.isAttached } ?: return@awaitEachGesture
                if (latestBusy || latestEditing) return@awaitEachGesture
                val id = drag.handleAt(coordinates.localToRoot(down.position)) ?: return@awaitEachGesture
                val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                val category = latestCategories.firstOrNull { it.id == id } ?: return@awaitEachGesture
                if (latestBusy || !drag.begin(id, category.name, coordinates.localToRoot(press.position), touchSlop)) return@awaitEachGesture
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                try {
                    val completed = trackDrag(press.id) { change ->
                        change.consume()
                        if (coordinates.isAttached) drag.moveTo(coordinates.localToRoot(change.position))
                        else drag.cancel()
                    }
                    if (completed) {
                        currentEvent.changes.forEach { if (!it.pressed) it.consume() }
                        when (val drop = drag.release(latestCategories.map { it.id }, latestBusy)) {
                            is CategoryDrop.Reorder -> reorder(drop.id, drop.offset)
                            is CategoryDrop.Delete -> delete(drop.id)
                            null -> Unit
                        }
                    } else drag.cancel()
                } finally {
                    if (drag.held) drag.cancel()
                }
            }
        }) {
        val deleteHeight = maxHeight * CATEGORY_DELETE_HEIGHT_FRACTION
        CategoryWorkspace(Modifier.align(Alignment.Center)) {
            CategoryWorkspaceHeader("管理分类", onDismiss, enabled = !creating && renameId == null) {
                TextButton(onClick = {
                    nameDraft = TextFieldValue()
                    creating = true
                }, enabled = enabled) { Text("新建分类") }
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth()
                .testTag("category-list").onGloballyPositioned { drag.listBounds = it.boundsInRoot() }
                .graphicsLayer { translationY = bounce },
                // Keep the last slot and an insertion gap reachable above the screen-edge trash.
                // Reserve this space before lifting too, so a hold never resizes the viewport.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = deleteHeight + 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = !drag.held) {
                if (displayed.isEmpty() && !creating) item {
                    Text("暂无分类", modifier = Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium)
                }
                items(displayed, key = { it.id }) { category ->
                    DisposableEffect(category.id) { onDispose { drag.unregister(category.id) } }
                    val actions = if (enabled) buildList {
                        if (categories.indexOfFirst { it.id == category.id } > 0) {
                            add(CustomAccessibilityAction("上移 ${category.name}") {
                                drag.reorderByAction(category.id, -1, latestCategories.map { it.id }, latestBusy)?.let {
                                    reorder(it.id, it.offset)
                                } != null
                            })
                        }
                        if (categories.indexOfFirst { it.id == category.id } < categories.lastIndex) {
                            add(CustomAccessibilityAction("下移 ${category.name}") {
                                drag.reorderByAction(category.id, 1, latestCategories.map { it.id }, latestBusy)?.let {
                                    reorder(it.id, it.offset)
                                } != null
                            })
                        }
                        add(CustomAccessibilityAction("改名 ${category.name}") {
                            if (latestBusy || drag.locked || latestCategories.none { it.id == category.id }) false
                            else { startRename(category); true }
                        })
                        add(CustomAccessibilityAction("删除分类 ${category.name}") {
                            if (latestBusy || drag.locked || latestCategories.none { it.id == category.id }) false
                            else { delete(category.id); true }
                        })
                    } else emptyList()
                    CategoryCard(category.name, if (renameId == category.id) !busy else enabled,
                        onRename = { startRename(category) },
                        editingValue = nameDraft.takeIf { renameId == category.id },
                        onEditChange = { nameDraft = it }, onSaveEdit = ::saveEdit, onCancelEdit = ::cancelEdit,
                        handleModifier = Modifier.testTag("category-handle-${category.id}").onGloballyPositioned { coordinates ->
                            drag.registerHandle(category.id) { if (coordinates.isAttached) coordinates.boundsInRoot() else Rect.Zero }
                        },
                        modifier = Modifier.animateItem(
                            fadeInSpec = if (motion) tween(160) else snap(),
                            placementSpec = if (motion) tween(220) else snap(),
                            fadeOutSpec = if (motion) tween(200) else snap(),
                        ).testTag("category-card-${category.id}").onGloballyPositioned { coordinates ->
                            drag.registerOrigin(category.id) {
                                if (!coordinates.isAttached) Rect.Zero else {
                                    val origin = coordinates.localToRoot(Offset.Zero)
                                    Rect(origin.x, origin.y, origin.x + coordinates.size.width, origin.y + coordinates.size.height)
                                }
                            }
                        }.graphicsLayer { alpha = if (drag.visual?.id == category.id) 0f else 1f }
                            .semantics { customActions = actions })
                }
                if (creating) item(key = "category-draft") {
                    CategoryCard("", enabled = !busy, onRename = {}, creating = true,
                        editingValue = nameDraft, onEditChange = { nameDraft = it },
                        onSaveEdit = ::saveEdit, onCancelEdit = ::cancelEdit,
                        modifier = Modifier.testTag("category-draft"))
                }
            }
        }
        AnimatedVisibility(drag.held, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(if (motion) 160 else 0)) + slideInVertically(tween(if (motion) 200 else 0)) { it / 3 },
            exit = fadeOut(tween(if (motion) 120 else 0)) + slideOutVertically(tween(if (motion) 160 else 0)) { it / 3 }) {
            DisposableEffect(drag) { onDispose { drag.deleteBounds = Rect.Zero } }
            PaperDeleteZone(armed = drag.deleteArmed,
                label = if (drag.deleteArmed) "松手后确认删除分类" else "拖到这里删除分类",
                modifier = Modifier.fillMaxWidth().height(deleteHeight).testTag("category-delete-zone")
                    .onGloballyPositioned { drag.deleteBounds = it.boundsInRoot() })
        }
        drag.visual?.let { visual ->
            var lifted by remember(visual.id) { mutableStateOf(false) }
            LaunchedEffect(visual.id) { lifted = true }
            val position by animateOffsetAsState(visual.pointer - visual.grab,
                if (drag.held || !motion) snap() else tween(duration), label = "category-return")
            val scale by animateFloatAsState(
                if (drag.held && lifted) 1.025f else if (visual.settlement == CategorySettlement.Delete) 0.72f else 1f,
                if (motion) tween(duration) else snap(), label = "category-lift")
            val alpha by animateFloatAsState(if (drag.settling && visual.settlement == CategorySettlement.Delete) 0f else 1f,
                if (motion) tween(duration) else snap(), label = "category-delete")
            CategoryCard(visual.name, enabled = false, onRename = {}, floating = true,
                modifier = Modifier.offset {
                    IntOffset((position.x - drag.rootBounds.left).roundToInt(), (position.y - drag.rootBounds.top).roundToInt())
                }.size(with(density) { visual.origin.width.toDp() }, with(density) { visual.origin.height.toDp() })
                    .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                    .clearAndSetSemantics { this[androidx.compose.ui.semantics.SemanticsProperties.TestTag] = "category-drag-preview" })
        }
    }
}
