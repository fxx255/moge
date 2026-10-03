package com.moge.app.ui.notebook

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import com.moge.app.ui.theme.MogeTheme
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.data.db.NotebookEntryEntity
import com.moge.app.ui.components.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NotebookScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    vm: NotebookViewModel = hiltViewModel(),
    onReturnToConversation: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    NotebookContent(state, onBack, onOpenConversation, vm::setQuery, vm::setCategory,
        vm::toggleSelection, vm::clearSelection, vm::selectAll, vm::openEntry, vm::closeEntry,
        vm::moveSelected, vm::deleteSelected, vm::createCategory, vm::renameCategory,
        vm::reorderCategory, vm::deleteCategory, vm::retry, vm::dismissMessage, vm::moveItems, vm::deleteItems, onReturnToConversation)
}

@Composable
internal fun NotebookContent(
    state: NotebookUiState,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onQuery: (String) -> Unit,
    onCategory: (String?, Boolean) -> Unit,
    onToggleSelection: (String) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onCloseEntry: () -> Unit,
    onMove: (String?) -> Unit,
    onDelete: () -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onReorderCategory: (String, Int) -> Unit,
    onDeleteCategory: (String) -> Unit,
    onRetry: () -> Unit,
    onDismissMessage: () -> Unit,
    onMoveItems: (Set<String>, String?) -> Unit = { _, _ -> },
    onDeleteItems: (Set<String>) -> Unit = {},
    onReturnToConversation: (() -> Unit)? = null,
) {
    val drag = rememberPaperDragState()
    val drop: (PaperDrop) -> Unit = { action -> when (val target = action.target) {
        PaperDropTarget.Delete -> onDeleteItems(action.ids)
        is PaperDropTarget.Category -> onMoveItems(action.ids, target.id)
    } }
    var manageOpen by rememberSaveable { mutableStateOf(false) }
    var moveOpen by rememberSaveable { mutableStateOf(false) }
    var deleteOpen by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); onDismissMessage() }
    }
    val detail = state.openEntryId != null
    val back: () -> Unit = { if (drag.held) drag.release(cancelled = true) else if (detail) onCloseEntry() else if (state.selecting && !state.busy) onClearSelection() else onBack() }
    BackHandler(drag.held || detail || (state.selecting && !state.busy), onBack = { back(); Unit })
    PageSwipeSurface(enabled = !state.selecting && !drag.held && !state.busy &&
        !manageOpen && !moveOpen && !deleteOpen,
        onRight = onReturnToConversation, rightLabel = "返回对话") {
        PaperScaffold(
            title = if (detail) state.detailEntry?.title ?: "收藏详情"
                else if (state.selecting) "已选 ${state.selectedIds.size} 条" else "我的题册",
            onBack = back,
            actions = {
                if (detail && state.detailEntry != null) {
                    IconButton(onClick = { moveOpen = true }, enabled = !state.busy) {
                        Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "移动分类")
                    }
                    IconButton(onClick = { deleteOpen = true }, enabled = !state.busy) {
                        Icon(Icons.Outlined.Delete, "移出题册", tint = MaterialTheme.colorScheme.error)
                    }
                } else if (!detail) {
                    IconButton(onClick = { manageOpen = true }, enabled = !state.busy) {
                        Icon(Icons.Outlined.FolderOpen, "管理分类")
                    }
                }
            },
        ) {
            AnimatedVisibility(state.selecting && !detail,
                enter = expandVertically(tween(if (MogeTheme.motionEnabled) 180 else 0)) + fadeIn(tween(if (MogeTheme.motionEnabled) 160 else 0)),
                exit = shrinkVertically(tween(if (MogeTheme.motionEnabled) 160 else 0)) + fadeOut(tween(if (MogeTheme.motionEnabled) 120 else 0))) {
                Row(Modifier.fillMaxWidth().excludePageSwipe().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { moveOpen = true }, enabled = !state.busy && !drag.held) {
                        Icon(Icons.AutoMirrored.Outlined.DriveFileMove, "移动分类")
                    }
                    IconButton(onClick = { deleteOpen = true }, enabled = !state.busy && !drag.held) {
                        Icon(Icons.Outlined.Delete, "移出题册", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (detail) {
                    state.detailEntry?.let { entry ->
                        NotebookEntryDetail(entry, state.sourceExists,
                            state.categories.firstOrNull { it.id == entry.categoryId }?.name ?: "未分类",
                            onOpenConversation, Modifier.fillMaxSize())
                    } ?: NotebookPlaceholder("正在读取收藏，或收藏已移出题册", "重试", onRetry, Modifier.fillMaxSize())
                } else Column(Modifier.fillMaxSize()) {
                    OutlinedTextField(state.query, onQuery, enabled = !state.busy, singleLine = true,
                        label = { Text("搜索收藏标题、题目或解答") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (state.query.isNotEmpty()) IconButton(onClick = { onQuery("") }, enabled = !state.busy) {
                                Icon(Icons.Outlined.Close, "清除搜索")
                            }
                        }, modifier = Modifier.fillMaxWidth().excludePageSwipe().padding(horizontal = 16.dp))
                    Row(Modifier.fillMaxWidth().paperCategoryActivation(drag).testTag("notebook-category-filters")
                        .excludePageSwipe().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(state.categoryId == null && !state.uncategorizedOnly,
                            { onCategory(null, false) }, { Text("全部") }, enabled = !state.busy,
                            modifier = Modifier.heightIn(min = 48.dp))
                        FilterChip(state.uncategorizedOnly, { onCategory(null, true) }, { Text("未分类") },
                            enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp))
                        state.categories.forEach { category ->
                            FilterChip(state.categoryId == category.id, { onCategory(category.id, false) }, { Text(category.name) },
                                enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    state.categoryError?.let { message ->
                        Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                        TextButton(onRetry) { Text("重试读取分类") }
                    }
                    if (state.selecting) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        TextButton(onSelectAll, enabled = !state.busy) { Text("全选当前结果") }
                        TextButton(onClearSelection, enabled = !state.busy) { Text("取消选择") }
                    } else if (state.entries.isNotEmpty()) Text("${state.entries.size} 条收藏 · 长按可管理",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    when {
                        state.error != null -> NotebookPlaceholder(state.error, "重试", onRetry, Modifier.weight(1f))
                        state.loading && state.entries.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        state.entries.isEmpty() -> NotebookPlaceholder(
                            if (state.filtered) "没有找到匹配的收藏" else "还没有收藏，解答完成后可通过“收藏到题册”保存题目与解答",
                            if (state.filtered) "清除筛选" else "去提问",
                            { if (state.filtered) { onQuery(""); onCategory(null, false) } else onBack() }, Modifier.weight(1f))
                        else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.entries, key = { it.id }) { entry ->
                                val category = state.categories.firstOrNull { it.id == entry.categoryId }?.name ?: "未分类"
                                val selected = entry.id in state.selectedIds
                                val selecting = state.selecting
                                val preview: @Composable () -> Unit = remember(entry, category, selected, selecting) {
                                    @Composable { NotebookCard(entry, category, selected, selecting, true, {}, Modifier) }
                                }
                                NotebookCard(entry, category, selected, selecting, state.busy,
                                    onClick = { if (drag.visual == null) { if (state.selecting) onToggleSelection(entry.id) else onOpenEntry(entry.id) } },
                                    modifier = Modifier.animateItem().testTag("notebook-card-${entry.id}")
                                        .paperDragSource(drag, entry.id, entry.title, state.selectedIds, !state.busy, onToggleSelection, drop, preview))
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
                if (!detail) PaperDragOverlay(drag, state.categories, Modifier.matchParentSize())
            }
        }
    }
    if (manageOpen) CategoryManager(state.categories, state.busy, { manageOpen = false },
        onCreateCategory, onRenameCategory, onReorderCategory, onDeleteCategory)
    if (moveOpen) CategoryPicker(state.categories, { moveOpen = false }) { id -> moveOpen = false; onMove(id) }
    if (deleteOpen) AlertDialog(onDismissRequest = { deleteOpen = false },
        title = { Text("移出题册？") }, text = { Text("保存的题目与解答快照将被删除，历史对话仍保留。") },
        confirmButton = { TextButton(onClick = { deleteOpen = false; onDelete() }, enabled = !state.busy) {
            Text("移出题册", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text("取消") } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCard(
    entry: NotebookEntryEntity, category: String, selected: Boolean, selecting: Boolean, busy: Boolean,
    onClick: () -> Unit, modifier: Modifier,
) {
    OutlinedCard(modifier.fillMaxWidth().clickable(enabled = !busy,
        role = if (selecting) Role.Checkbox else Role.Button,
        onClickLabel = if (selecting) "切换选择" else "打开收藏", onClick = onClick).semantics { this.selected = selected },
        colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(entry.questionText.ifBlank { entry.questionTranscript }.ifBlank { "图片题目" }, maxLines = 3,
                overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(entry.updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (selected) "已选择" else "未选择", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.alpha(if (selecting) 1f else 0f)
                .then(if (selecting) Modifier else Modifier.clearAndSetSemantics {}))
        }
    }
}

@Composable
private fun NotebookPlaceholder(message: String, action: String, onAction: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onAction) { Text(action) }
    }
}
