package com.moge.app.ui.notebook

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
    val strings = rememberAppStrings()
    val drag = rememberPaperDragState()
    val paperScroll = rememberPaperScrollState()
    val detailScroll = remember(state.openEntryId) { PaperScrollState() }
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
        onRight = onReturnToConversation, rightLabel = strings.text(R.string.ui_back_to_conversation_185d6)) {
        PaperPageReveal(state.openEntryId) {
            PaperScaffold(
                headerStyle = PaperHeaderStyle.StackedRight,
                title = if (detail) state.detailEntry?.title ?: strings.text(R.string.ui_saved_item_b487b)
                    else if (state.selecting) strings.text(R.string.ui_1_s_selected_c321a, state.selectedIds.size) else strings.text(R.string.ui_my_notebook_7ec53),
                onBack = back,
                backgroundOffsetY = { if (detail) detailScroll.offsetY else paperScroll.offsetY },
                contentBehindHeader = true,
                headerContentGap = if (detail) PaperHeaderContentGap else PaperHeaderContentGap / 2,
                actions = {
                    if (detail && state.detailEntry != null) {
                        PaperHeaderIconButton(onClick = { moveOpen = true }, enabled = !state.busy) {
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, strings.text(R.string.ui_move_to_category_4bd01))
                        }
                        PaperHeaderIconButton(onClick = { deleteOpen = true }, enabled = !state.busy) {
                            Icon(Icons.Outlined.Delete, strings.text(R.string.ui_remove_from_notebook_c3aca), tint = MaterialTheme.colorScheme.error)
                        }
                    } else if (!detail) {
                        PaperHeaderIconButton(onClick = { manageOpen = true }, enabled = !state.busy) {
                            Icon(Icons.Outlined.FolderOpen, strings.text(R.string.ui_manage_categories_fed61))
                        }
                    }
                },
            ) { pageInsets ->
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (detail) {
                        state.detailEntry?.let { entry ->
                            NotebookEntryDetail(entry, state.sourceExists,
                                state.categories.firstOrNull { it.id == entry.categoryId }?.name ?: strings.text(R.string.ui_uncategorized_b28f1),
                                onOpenConversation, Modifier.fillMaxSize().nestedScroll(detailScroll), contentPadding = pageInsets)
                        } ?: NotebookPlaceholder(strings.text(R.string.ui_loading_saved_item_or_it_has_been_removed_4715a), strings.text(R.string.ui_retry_e2d53), onRetry, Modifier.fillMaxSize())
                    } else FloatingPaperToolbar(Modifier.fillMaxSize(), topInset = pageInsets.calculateTopPadding(), toolbar = {
                        AnimatedVisibility(state.selecting,
                            enter = expandVertically(tween(if (MogeTheme.motionEnabled) 180 else 0)) + fadeIn(tween(if (MogeTheme.motionEnabled) 160 else 0)),
                            exit = shrinkVertically(tween(if (MogeTheme.motionEnabled) 160 else 0)) + fadeOut(tween(if (MogeTheme.motionEnabled) 120 else 0))) {
                            Row(Modifier.fillMaxWidth().excludePageSwipe().padding(horizontal = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                PaperHeaderIconButton(onClick = { moveOpen = true }, enabled = !state.busy && !drag.held) {
                                    Icon(Icons.AutoMirrored.Outlined.DriveFileMove, strings.text(R.string.ui_move_to_category_4bd01))
                                }
                                PaperHeaderIconButton(onClick = { deleteOpen = true }, enabled = !state.busy && !drag.held) {
                                    Icon(Icons.Outlined.Delete, strings.text(R.string.ui_remove_from_notebook_c3aca), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                        PaperSearchPanel(
                            query = state.query, onQuery = onQuery, hint = strings.text(R.string.ui_search_saved_titles_questions_or_answers_43689), enabled = !state.busy,
                            filtersModifier = Modifier.paperCategoryActivation(drag).testTag("notebook-category-filters"),
                            expandedContent = { PaperCategoryDropPanel(drag, state.categories) },
                        ) {
                            PaperCategoryFilter(state.categoryId == null && !state.uncategorizedOnly,
                                { onCategory(null, false) }, strings.text(R.string.ui_all_778fc), enabled = !state.busy)
                            PaperCategoryFilter(state.uncategorizedOnly, { onCategory(null, true) }, strings.text(R.string.ui_uncategorized_b28f1),
                                enabled = !state.busy)
                            state.categories.forEach { category ->
                                PaperCategoryFilter(state.categoryId == category.id, { onCategory(category.id, false) },
                                    category.name, enabled = !state.busy)
                            }
                        }
                        state.categoryError?.let { message ->
                            Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))
                            TextButton(onRetry) { Text(strings.text(R.string.ui_reload_categories_2a2bd)) }
                        }
                        if (state.selecting && !drag.held) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            TextButton(onSelectAll, enabled = !state.busy) { Text(strings.text(R.string.ui_select_all_results_f9320)) }
                            TextButton(onClearSelection, enabled = !state.busy) { Text(strings.text(R.string.ui_deselect_all_f02e9)) }
                        }
                    }) { insets ->
                        when {
                            state.error != null -> NotebookPlaceholder(state.error, strings.text(R.string.ui_retry_e2d53), onRetry, Modifier.fillMaxSize().padding(insets))
                            state.loading && state.entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                            state.entries.isEmpty() -> NotebookPlaceholder(
                                if (state.filtered) strings.text(R.string.ui_no_matching_saved_items_3a77a) else strings.text(R.string.ui_no_saved_items_yet_use_save_to_notebook_after_a_solution_is_da759),
                                if (state.filtered) strings.text(R.string.ui_clear_filters_4bbc4) else strings.text(R.string.ui_ask_a_question_847be),
                                { if (state.filtered) { onQuery(""); onCategory(null, false) } else onBack() }, Modifier.fillMaxSize().padding(insets))
                            else -> LazyColumn(Modifier.fillMaxSize().nestedScroll(paperScroll), contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                                top = insets.calculateTopPadding() + 16.dp, bottom = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(state.entries, key = { it.id }) { entry ->
                                    val category = state.categories.firstOrNull { it.id == entry.categoryId }?.name ?: strings.text(R.string.ui_uncategorized_b28f1)
                                    val selected = entry.id in state.selectedIds
                                    val selecting = state.selecting
                                    val preview: @Composable () -> Unit = remember(entry, category, selected, selecting) {
                                        @Composable { NotebookCard(entry, category, selected, selecting, true, {}, Modifier, showTape = false) }
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
                    if (!detail) PaperDragOverlay(drag, Modifier.matchParentSize())
                }
            }
        }
    }
    if (manageOpen) CategoryManager(state.categories, state.busy, { manageOpen = false },
        onCreateCategory, onRenameCategory, onReorderCategory, onDeleteCategory)
    if (moveOpen) CategoryPicker(state.categories, { moveOpen = false },
        onManage = { moveOpen = false; manageOpen = true }) { id -> moveOpen = false; onMove(id) }
    if (deleteOpen) AlertDialog(onDismissRequest = { deleteOpen = false },
        title = { Text(strings.text(R.string.ui_remove_from_notebook_b697b)) }, text = { Text(strings.text(R.string.ui_delete_the_saved_question_and_answer_conversation_history_is_d3239)) },
        confirmButton = { TextButton(onClick = { deleteOpen = false; onDelete() }, enabled = !state.busy) {
            Text(strings.text(R.string.ui_remove_from_notebook_c3aca), color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = { deleteOpen = false }) { Text(strings.text(R.string.ui_cancel_4d0b4)) } })
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCard(
    entry: NotebookEntryEntity, category: String, selected: Boolean, selecting: Boolean, busy: Boolean,
    onClick: () -> Unit, modifier: Modifier,
    showTape: Boolean = true,
) {
    val strings = rememberAppStrings()
    TapedPaperCard(modifier.clickable(enabled = !busy,
        role = if (selecting) Role.Checkbox else Role.Button,
        onClickLabel = if (selecting) strings.text(R.string.ui_toggle_selection_bcab7) else strings.text(R.string.ui_open_saved_item_02dae), onClick = onClick)
        .semantics(mergeDescendants = true) { this.selected = selected }, showTape = showTape) { cardModifier ->
        OutlinedCard(cardModifier,
            colors = CardDefaults.outlinedCardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(entry.questionText.ifBlank { entry.questionTranscript }.ifBlank { strings.text(R.string.ui_photo_question_770f1) }, maxLines = 3,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(category, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(entry.updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (selected) strings.text(R.string.ui_selected_743aa) else strings.text(R.string.ui_not_selected_53e2d), style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.alpha(if (selecting) 1f else 0f)
                    .then(if (selecting) Modifier else Modifier.clearAndSetSemantics {}))
            }
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
