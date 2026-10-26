package com.moge.app.ui.history


import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

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
import androidx.compose.material.icons.automirrored.outlined.Chat
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

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
    val strings = rememberAppStrings()
    val drag = rememberPaperDragState()
    val paperScroll = rememberPaperScrollState()
    val controlSway = rememberPaperControlSway()
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
            val result = snackbar.showSnackbar(message, actionLabel = if (message.startsWith(strings.text(R.string.ui_saved_c7e9a))) strings.text(R.string.ui_view_notebook_aeb82) else null)
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
        onLeft = onNewConversation, leftLabel = strings.text(R.string.ui_go_to_new_chat_44a4e)) {
        PaperScaffold(
            headerStyle = PaperHeaderStyle.StackedLeft,
            contentBehindHeader = true,
            headerContentGap = PaperHeaderContentGap / 2,
            title = if (state.selecting) strings.text(R.string.ui_1_s_selected_70403, state.selectedIds.size) else strings.text(R.string.ui_history_68387),
            backgroundOffsetY = { paperScroll.offsetY },
            onBack = { if (drag.held) drag.release(cancelled = true) else if (state.selecting && !state.busy) onClearSelection() else onBack() },
            actions = {
                if (!state.selecting) {
                    PaperHeaderIconButton(onClick = { onOpenNotebook(null) }, enabled = !state.busy && !drag.held) {
                        Icon(Icons.AutoMirrored.Outlined.MenuBook, strings.text(R.string.ui_my_notebook_7ec53))
                    }
                    PaperHeaderIconButton(onClick = { manageOpen = true }, enabled = !state.busy && !drag.held) { Icon(Icons.Outlined.FolderOpen, strings.text(R.string.ui_manage_categories_fed61)) }
                }
            },
        ) { pageInsets ->
            Box(Modifier.weight(1f).fillMaxWidth()) {
                FloatingPaperToolbar(Modifier.fillMaxSize(), topInset = pageInsets.calculateTopPadding(), toolbar = {
                    AnimatedVisibility(state.selecting && !drag.held,
                        enter = expandVertically(tween(if (MogeTheme.motionEnabled) 180 else 0)) + fadeIn(tween(if (MogeTheme.motionEnabled) 160 else 0)),
                        exit = shrinkVertically(tween(if (MogeTheme.motionEnabled) 160 else 0)) + fadeOut(tween(if (MogeTheme.motionEnabled) 120 else 0))) {
                        Row(Modifier.fillMaxWidth().excludePageSwipe().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            PaperHeaderIconButton(onClick = { moveOpen = true }, enabled = !state.busy && !drag.held) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, strings.text(R.string.ui_move_to_category_4bd01)) }
                            PaperHeaderIconButton(onClick = { renameOpen = true }, enabled = state.selectedIds.size == 1 && !state.busy) {
                                Icon(Icons.Outlined.Edit, strings.text(R.string.ui_rename_1cd80))
                            }
                            PaperHeaderIconButton(onClick = onCollect, enabled = !state.busy && !drag.held) {
                                Icon(Icons.Outlined.BookmarkBorder, strings.text(R.string.ui_save_latest_solution_to_notebook_1571c))
                            }
                            PaperHeaderIconButton(onClick = onPin, enabled = state.selectedIds.size == 1 && !state.busy) {
                                Icon(Icons.Outlined.PushPin, strings.text(R.string.ui_pin_or_unpin_00b2c))
                            }
                            PaperHeaderIconButton(onClick = { deleteOpen = true }, enabled = !state.busy) {
                                Icon(Icons.Outlined.Delete, strings.text(R.string.ui_delete_selected_conversations_7375d), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    PaperSearchPanel(
                        query = state.query, onQuery = onQuery, hint = strings.text(R.string.ui_search_titles_messages_or_recognized_text_52b39), enabled = !state.busy,
                        filtersModifier = Modifier.paperCategoryActivation(drag).testTag("history-category-filters"),
                        expandedContent = { PaperCategoryDropPanel(drag, state.categories) },
                        scrollSway = { controlSway.fraction },
                    ) {
                        PaperCategoryFilter(state.categoryId == null && !state.uncategorizedOnly,
                            { onCategory(null, false) }, strings.text(R.string.ui_all_778fc), enabled = !state.busy && !drag.held)
                        PaperCategoryFilter(state.uncategorizedOnly, { onCategory(null, true) }, strings.text(R.string.ui_uncategorized_b28f1),
                            enabled = !state.busy && !drag.held)
                        state.categories.forEach { category ->
                            PaperCategoryFilter(state.categoryId == category.id, { onCategory(category.id, false) },
                                category.name, enabled = !state.busy && !drag.held)
                        }
                    }
                    state.categoryError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                        TextButton(onRetry) { Text(strings.text(R.string.ui_reload_categories_2a2bd)) }
                    }
                    if (state.selecting && !drag.held) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onSelectAll, enabled = !state.busy) { Text(strings.text(R.string.ui_select_all_results_f9320)) }
                            TextButton(onClearSelection, enabled = !state.busy) { Text(strings.text(R.string.ui_deselect_all_f02e9)) }
                        }
                    }
                }) { insets ->
                    when {
                        state.error != null -> HistoryPlaceholder(state.error, strings.text(R.string.ui_retry_e2d53), onRetry, Modifier.fillMaxSize().padding(insets))
                        // 换搜索词时保留旧结果直到新结果到达，网格不闪、滚动位置也不丢。
                        state.loading && state.entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.semantics { contentDescription = strings.text(R.string.ui_loading_history_ea500) })
                        }
                        state.entries.isEmpty() -> HistoryPlaceholder(
                            if (state.filtered) strings.text(R.string.ui_no_matching_conversations_08522) else strings.text(R.string.ui_conversations_are_saved_here_after_you_send_a_question_a675e),
                            if (state.filtered) strings.text(R.string.ui_clear_filters_4bbc4) else strings.text(R.string.ui_ask_a_question_847be),
                            { if (state.filtered) { onQuery(""); onCategory(null, false) } else onBack() }, Modifier.fillMaxSize().padding(insets),
                        )
                        else -> HistoryGrid(state, onOpenConversation, onToggleSelection, drag, drop,
                            Modifier.fillMaxSize().nestedScroll(paperScroll).nestedScroll(controlSway), insets)
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(8.dp))
                PaperDragOverlay(drag, Modifier.matchParentSize())
            }
        }
        if (moveOpen) CategoryPicker(state.categories, { moveOpen = false },
            onManage = { moveOpen = false; manageOpen = true }) { category ->
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
            title = { Text(strings.text(R.string.ui_delete_1_s_conversations_a9236, state.selectedIds.size)) },
            text = { Text(strings.text(R.string.ui_conversations_and_messages_will_be_permanently_deleted_saved_fd14f)) },
            confirmButton = { TextButton(onClick = { deleteOpen = false; onDelete() }, enabled = !state.busy) {
                Text(strings.text(R.string.ui_delete_3755f), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text(strings.text(R.string.ui_cancel_4d0b4)) } },
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
    toolbarInsets: PaddingValues,
) {
    val strings = rememberAppStrings()
    // 常规字号两列瀑布流；辅助大字号改一列，为正文留出足够宽度。
    val columns = if (LocalDensity.current.fontScale > 1.5f) 1 else 2
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
        }
    }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns), modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
            top = toolbarInsets.calculateTopPadding() + 8.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalItemSpacing = 12.dp,
    ) {
        items(state.entries, key = { it.conversation.id }) { entry ->
            val id = entry.conversation.id
            val thumbnail = if (entry.conversation.coverImage.isNotBlank())
                rememberThumbnail(entry.conversation.coverImage, THUMB_MAX_PX, preferProcessed = true) else Thumbnail.Failed
            val category = state.categories.firstOrNull { it.id == entry.conversation.categoryId }?.name ?: strings.text(R.string.ui_uncategorized_b28f1)
            val selected = id in state.selectedIds
            val selecting = state.selecting
            val generating = id == state.activeConversationId
            val retention = historyRetentionLabel(entry, state.autoCleanupEnabled, state.retentionDays, now, generating, strings)
            // Allocate a new immutable preview when data changes; a live composable lambda
            // would otherwise update the lifted card during later recompositions.
            val preview: @Composable () -> Unit = remember(entry, selected, selecting, generating, category, thumbnail, retention) {
                @Composable { HistoryCard(entry, selected, selecting, true, generating, {}, category, Modifier, thumbnail, retention, showTape = false) }
            }
            HistoryCard(entry, selected, selecting, state.busy, generating,
                onClick = { if (drag.visual == null) { if (state.selecting) onToggle(id) else onOpen(id) } },
                category = category,
                modifier = Modifier.animateItem().testTag("history-card-$id")
                    .paperDragSource(drag, id, entry.conversation.title, state.selectedIds, !state.busy, onToggle, onDrop, preview),
                thumbnail = thumbnail, retention = retention)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HistoryCard(
    entry: HistoryEntry, selected: Boolean, selecting: Boolean, busy: Boolean,
    generating: Boolean, onClick: () -> Unit, category: String, modifier: Modifier, thumbnail: Thumbnail,
    retention: String,
    showTape: Boolean = true,
) {
    val strings = rememberAppStrings()
    val shape = RoundedCornerShape(8.dp)
    val conversation = entry.conversation
    TapedPaperCard(modifier.clickable(enabled = !busy, role = if (selecting) Role.Checkbox else Role.Button,
        onClickLabel = if (selecting) strings.text(R.string.ui_toggle_selection_bcab7) else strings.text(R.string.ui_open_conversation_11408), onClick = onClick)
        .semantics(mergeDescendants = true) { if (selecting) this.selected = selected }, showTape = showTape) { cardModifier ->
        Column(
            cardModifier.heightIn(min = 48.dp).clip(shape)
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                .padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (conversation.coverImage.isNotBlank()) HistoryCover(thumbnail)
            if (conversation.coverImage.isBlank() && entry.questionPreview.isNotBlank()) Text(
                entry.questionPreview.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.conversationMode == "CHAT") {
                    Icon(Icons.AutoMirrored.Outlined.Chat, strings.text(R.string.ui_chat_3ddf6),
                        Modifier.padding(end = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(conversation.title, style = MaterialTheme.typography.titleMedium, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f))
                Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp).size(24.dp).alpha(if (selecting) 1f else 0f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(retention, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (entry.favoriteCount > 0) Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Outlined.Bookmark, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(strings.text(R.string.ui_1_s_saved_answers_27f1b, entry.favoriteCount), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (conversation.pinned) Text(strings.text(R.string.ui_pinned_d7d97), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(conversation.updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!generating && entry.requestStatus in setOf("INTERRUPTED", "CANCELLED")) {
                Text(strings.text(R.string.ui_incomplete_open_to_retry_024a1), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error)
            }
            if (generating || entry.requestStatus in setOf("PREPARING", "RUNNING")) Text(strings.text(R.string.ui_generating_5f576), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}

@Composable
private fun HistoryCover(thumbnail: Thumbnail) {
    val strings = rememberAppStrings()
    Box(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(4.dp)).background(MogeTheme.paper.scratch),
        contentAlignment = Alignment.Center) {
        when (thumbnail) {
            is Thumbnail.Ready -> {
                val painted = remember(thumbnail.bitmap) { thumbnail.bitmap.asImageBitmap() }
                Image(painted, strings.text(R.string.ui_question_photo_62144), Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Thumbnail.Loading -> Unit
            Thumbnail.Failed -> Text(strings.text(R.string.ui_photo_unavailable_4aba7), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    val strings = rememberAppStrings()
    var title by rememberSaveable(initial) { mutableStateOf(initial) }
    val cleaned = title.trim()
    val valid = cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 80
    AlertDialog(onDismissRequest = onDismiss, title = { Text(strings.text(R.string.ui_rename_conversation_23543)) },
        text = {
            OutlinedTextField(title, { title = it }, label = { Text(strings.text(R.string.ui_conversation_title_b259c)) }, singleLine = true,
                isError = !valid, supportingText = { Text(strings.text(R.string.ui_1_80_characters_ab06f)) }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onSave(cleaned) }, enabled = valid) { Text(strings.text(R.string.ui_save_fadf2)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.text(R.string.ui_cancel_4d0b4)) } })
}
