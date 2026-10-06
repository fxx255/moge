package com.moge.app.ui.history

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.data.db.HistoryEntry
import com.moge.app.ui.components.*
import com.moge.app.ui.notebook.CategoryManager
import com.moge.app.ui.notebook.CategoryPicker
import androidx.compose.ui.platform.testTag
import com.moge.app.ui.photo.THUMB_MAX_PX
import com.moge.app.ui.photo.Thumbnail
import com.moge.app.ui.photo.rememberThumbnail
import com.moge.app.ui.theme.MogeTheme
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    vm: HistoryViewModel = hiltViewModel(),
    onOpenNotebook: (String?) -> Unit = {},
    onNewConversation: (() -> Unit)? = null,
    onLocateMessage: (String, String) -> Unit = { _, _ -> },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    HistoryContent(state, onBack, { id -> vm.openConversation(id) { conversation, message ->
        if (message != null) onLocateMessage(conversation, message)
        onOpenConversation(conversation)
    } }, vm::setQuery,
        vm::toggleSelection, vm::clearSelection, vm::selectAll, vm::renameSelected,
        vm::deleteSelected, vm::pinSelected, vm::retry, vm::dismissMessage,
        vm::setCategory, vm::createCategory, vm::renameCategory, vm::reorderCategory, vm::deleteCategory,
        vm::moveItems, vm::deleteItems, vm::collectSelected, onOpenNotebook, onNewConversation, vm::dismissSavedFavorite)
}

@Composable
internal fun HistoryContent(
    state: HistoryUiState,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onQuery: (String) -> Unit,
    onToggleSelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onPin: () -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit,
    onCategory: (String?, Boolean) -> Unit = { _, _ -> },
    onCreateCategory: (String) -> Unit = {},
    onRenameCategory: (String, String) -> Unit = { _, _ -> },
    onReorderCategory: (String, Int) -> Unit = { _, _ -> },
    onDeleteCategory: (String) -> Unit = {},
    onMoveItems: (Set<String>, String?) -> Unit = { _, _ -> },
    onDeleteItems: (Set<String>) -> Unit = {},
    onCollect: () -> Unit = {},
    onOpenNotebook: (String?) -> Unit = {},
    onNewConversation: (() -> Unit)? = null,
    onDismissSavedFavorite: () -> Unit = {},
) {
    val drag = rememberPaperDragState()
    val drop: (PaperDrop) -> Unit = { action -> when (val target = action.target) {
        PaperDropTarget.Delete -> onDeleteItems(action.ids)
        is PaperDropTarget.Category -> onMoveItems(action.ids, target.id)
    } }
    var moveOpen by rememberSaveable { mutableStateOf(false) }
    var manageOpen by rememberSaveable { mutableStateOf(false) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message, state.savedFavoriteId) {
        state.message?.let { message ->
            val saved = state.savedFavoriteId
            val result = snackbar.showSnackbar(message, actionLabel = if (message.startsWith("已收藏 ")) "查看题册" else null)
            if (result == SnackbarResult.ActionPerformed) onOpenNotebook(saved)
            onDismissMessage()
            onDismissSavedFavorite()
        }
    }
    BackHandler(drag.held || (state.selecting && !state.busy)) {
        if (drag.held) drag.release(cancelled = true) else onClearSelection()
    }
    PageSwipeSurface(enabled = !state.selecting && !drag.held && !state.busy &&
        !manageOpen && !moveOpen && !renameOpen && !deleteOpen,
        onLeft = onNewConversation, leftLabel = "前往新对话") {
        PaperScaffold(
            title = if (state.selecting) "已选 ${state.selectedIds.size} 个" else "历史对话",
            onBack = { if (drag.held) drag.release(cancelled = true) else if (state.selecting && !state.busy) onClearSelection() else onBack() },
            actions = {
                if (!state.selecting) {
                    IconButton(onClick = { onOpenNotebook(null) }, enabled = !state.busy && !drag.held) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, "我的题册")
                    }
                    IconButton(onClick = { manageOpen = true }, enabled = !state.busy && !drag.held) { Icon(Icons.Outlined.FolderOpen, "管理分类") }
                }
            },
        ) {
            AnimatedVisibility(state.selecting,
                enter = expandVertically(tween(if (MogeTheme.motionEnabled) 180 else 0)) + fadeIn(tween(if (MogeTheme.motionEnabled) 160 else 0)),
                exit = shrinkVertically(tween(if (MogeTheme.motionEnabled) 160 else 0)) + fadeOut(tween(if (MogeTheme.motionEnabled) 120 else 0))) {
                Row(Modifier.fillMaxWidth().excludePageSwipe().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { moveOpen = true }, enabled = !state.busy && !drag.held) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "移动分类") }
                    IconButton(onClick = { renameOpen = true }, enabled = state.selectedIds.size == 1 && !state.busy) {
                        Icon(Icons.Outlined.Edit, "重命名")
                    }
                    IconButton(onClick = onCollect, enabled = !state.busy && !drag.held) {
                        Icon(Icons.Outlined.BookmarkBorder, "收藏最新解答到题册")
                    }
                    IconButton(onClick = onPin, enabled = state.selectedIds.size == 1 && !state.busy) {
                        Icon(Icons.Outlined.PushPin, "置顶或取消置顶")
                    }
                    IconButton(onClick = { deleteOpen = true }, enabled = !state.busy) {
                        Icon(Icons.Outlined.Delete, "删除所选对话", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(
                        value = state.query, onValueChange = onQuery, enabled = !state.busy,
                        label = { Text("搜索标题、消息或识别文本") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }, enabled = !state.busy) {
                                Icon(Icons.Outlined.Close, "清除搜索")
                            }
                        },
                        modifier = Modifier.fillMaxWidth().excludePageSwipe().padding(horizontal = 16.dp),
                    )
                    Row(Modifier.fillMaxWidth().paperCategoryActivation(drag).testTag("history-category-filters")
                        .excludePageSwipe().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(state.categoryId == null && !state.uncategorizedOnly, { onCategory(null, false) }, { Text("全部") }, enabled = !state.busy && !drag.held)
                        FilterChip(state.uncategorizedOnly, { onCategory(null, true) }, { Text("未分类") }, enabled = !state.busy && !drag.held)
                        state.categories.forEach { category ->
                            FilterChip(state.categoryId == category.id, { onCategory(category.id, false) }, { Text(category.name) }, enabled = !state.busy && !drag.held)
                        }
                    }
                    state.categoryError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                        TextButton(onRetry) { Text("重试读取分类") }
                    }
                    if (state.selecting) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onSelectAll, enabled = !state.busy) { Text("全选当前结果") }
                            TextButton(onClearSelection, enabled = !state.busy) { Text("取消选择") }
                        }
                    } else if (state.error == null && state.entries.isNotEmpty()) {
                        Text("${state.entries.size} 个对话 · 长按选择，上拖分组，下拖删除", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    when {
                        state.error != null -> HistoryPlaceholder(state.error, "重试", onRetry, Modifier.weight(1f))
                        // 换搜索词时保留旧结果直到新结果到达，网格不闪、滚动位置也不丢。
                        state.loading && state.entries.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.semantics { contentDescription = "正在读取历史" })
                        }
                        state.entries.isEmpty() -> HistoryPlaceholder(
                            if (state.filtered) "没有找到匹配的对话" else "还没有历史对话，发送问题后会自动保存",
                            if (state.filtered) "清除筛选" else "去提问",
                            { if (state.filtered) { onQuery(""); onCategory(null, false) } else onBack() }, Modifier.weight(1f),
                        )
                        else -> HistoryGrid(state, onOpenConversation, onToggleSelection, drag, drop, Modifier.weight(1f))
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
                PaperDragOverlay(drag, state.categories, Modifier.matchParentSize())
            }
        }
        if (moveOpen) CategoryPicker(state.categories, { moveOpen = false }) { category ->
            moveOpen = false; onMoveItems(state.selectedIds, category)
        }
    }
    if (manageOpen) CategoryManager(state.categories, state.busy, { manageOpen = false },
        onCreateCategory, onRenameCategory, onReorderCategory, onDeleteCategory)
    val selected = state.entries.firstOrNull { it.conversation.id in state.selectedIds }
    if (renameOpen && selected != null && state.selectedIds.size == 1) {
        RenameDialog(selected.conversation.title, { renameOpen = false }) { title ->
            renameOpen = false
            onRename(title)
        }
    }
    if (deleteOpen && state.selecting) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("删除 ${state.selectedIds.size} 个对话？") },
            text = { Text("对话与消息将从历史中删除，无法撤销。已收藏的题目与解答快照仍保留在题册中。生成中的对话会保留，请先停止生成。") },
            confirmButton = { TextButton(onClick = { deleteOpen = false; onDelete() }, enabled = !state.busy) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun HistoryPlaceholder(message: String, action: String, onAction: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onAction) { Text(action) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryGrid(
    state: HistoryUiState,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    drag: PaperDragState,
    onDrop: (PaperDrop) -> Unit,
    modifier: Modifier,
) {
    // 常规字号两列瀑布流；辅助大字号改一列，为正文留出足够宽度。
    val columns = if (LocalDensity.current.fontScale > 1.5f) 1 else 2
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns), modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalItemSpacing = 12.dp,
    ) {
        items(state.entries, key = { it.conversation.id }) { entry ->
            val id = entry.conversation.id
            val thumbnail = if (entry.conversation.coverImage.isNotBlank())
                rememberThumbnail(entry.conversation.coverImage, THUMB_MAX_PX) else Thumbnail.Failed
            val category = state.categories.firstOrNull { it.id == entry.conversation.categoryId }?.name ?: "未分类"
            val selected = id in state.selectedIds
            val selecting = state.selecting
            val generating = id == state.activeConversationId
            // Allocate a new immutable preview when data changes; a live composable lambda
            // would otherwise update the lifted card during later recompositions.
            val preview: @Composable () -> Unit = remember(entry, selected, selecting, generating, category, thumbnail) {
                @Composable { HistoryCard(entry, selected, selecting, true, generating, {}, category, Modifier, thumbnail) }
            }
            HistoryCard(entry, selected, selecting, state.busy, generating,
                onClick = { if (drag.visual == null) { if (state.selecting) onToggle(id) else onOpen(id) } },
                category = category,
                modifier = Modifier.animateItem().testTag("history-card-$id")
                    .paperDragSource(drag, id, entry.conversation.title, state.selectedIds, !state.busy, onToggle, onDrop, preview),
                thumbnail = thumbnail)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    entry: HistoryEntry, selected: Boolean, selecting: Boolean, busy: Boolean,
    generating: Boolean, onClick: () -> Unit, category: String, modifier: Modifier, thumbnail: Thumbnail,
) {
    val shape = RoundedCornerShape(6.dp)
    val conversation = entry.conversation
    Column(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MogeTheme.paper.cardStroke.copy(alpha = 0.45f), shape)
            .clickable(enabled = !busy, role = if (selecting) Role.Checkbox else Role.Button,
                onClickLabel = if (selecting) "切换选择" else "打开对话", onClick = onClick)
            .semantics(mergeDescendants = true) { if (selecting) this.selected = selected }
            .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (conversation.coverImage.isNotBlank()) HistoryCover(thumbnail)
        if (conversation.coverImage.isBlank() && entry.questionPreview.isNotBlank()) Text(
            entry.questionPreview.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(conversation.title, style = MaterialTheme.typography.titleMedium, maxLines = 3,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f))
            Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp).size(24.dp).alpha(if (selecting) 1f else 0f))
        }
        Text(category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (entry.favoriteCount > 0) Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Outlined.Bookmark, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text("已收藏 ${entry.favoriteCount} 条解答", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        if (conversation.pinned) Text("已置顶", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(conversation.updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!generating && entry.requestStatus in setOf("INTERRUPTED", "CANCELLED")) {
            Text("解答未完成 · 打开后可重试", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error)
        }
        if (generating || entry.requestStatus in setOf("PREPARING", "RUNNING")) Text("正在解答", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun HistoryCover(thumbnail: Thumbnail) {
    Box(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(4.dp)).background(MogeTheme.paper.scratch),
        contentAlignment = Alignment.Center) {
        when (thumbnail) {
            is Thumbnail.Ready -> {
                val painted = remember(thumbnail.bitmap) { thumbnail.bitmap.asImageBitmap() }
                Image(painted, "题目照片", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Thumbnail.Loading -> Unit
            Thumbnail.Failed -> Text("照片暂不可用", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var title by rememberSaveable(initial) { mutableStateOf(initial) }
    val cleaned = title.trim()
    val valid = cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 80
    AlertDialog(onDismissRequest = onDismiss, title = { Text("重命名对话") },
        text = {
            OutlinedTextField(title, { title = it }, label = { Text("对话标题") }, singleLine = true,
                isError = !valid, supportingText = { Text("1–80 个字") }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(cleaned) }, enabled = valid) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
