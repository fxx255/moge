package com.moge.app.ui.solve

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.R
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.ui.localization.rememberAppStrings
import com.moge.app.ui.notebook.CategoryCard
import com.moge.app.ui.notebook.CategoryDestination
import com.moge.app.ui.notebook.CategoryPaperSurface
import com.moge.app.ui.notebook.CategoryWorkspace
import com.moge.app.ui.notebook.CategoryWorkspaceHeader
import com.moge.app.ui.theme.MogeTheme

@Composable
internal fun FavoriteDialog(target: FavoriteTarget, onDismiss: () -> Unit, onSaved: (String) -> Unit,
    vm: FavoriteViewModel = hiltViewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val existing by vm.existing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    LaunchedEffect(target.answer.id) { vm.open(target.answer.id) }
    Dialog(onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        FavoriteDialogContent(target, categories, existing?.categoryId, existing != null, busy, loading, error,
            onDismiss = onDismiss, onSave = { category, name -> vm.save(target, category, name, onSaved) })
    }
}

/** A category destination uses the same punched cards and inline editor as its manager. */
@Composable
internal fun FavoriteDialogContent(
    target: FavoriteTarget,
    categories: List<NotebookCategoryEntity>,
    initialCategoryId: String?,
    updating: Boolean,
    busy: Boolean,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSave: (String?, String) -> Unit,
) {
    val strings = rememberAppStrings()
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    var categoryId by rememberSaveable(target.answer.id) { mutableStateOf(initialCategoryId) }
    var edited by rememberSaveable(target.answer.id) { mutableStateOf(false) }
    var newCategory by rememberSaveable(target.answer.id) { mutableStateOf("") }
    var creating by rememberSaveable(target.answer.id) { mutableStateOf(false) }
    var draft by rememberSaveable(target.answer.id, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    val enabled = !busy && !loading
    LaunchedEffect(initialCategoryId) { if (!edited) categoryId = initialCategoryId }
    LaunchedEffect(creating) { if (creating) list.scrollToItem(1) }
    fun choose(id: String?) {
        categoryId = id; edited = true; newCategory = ""; creating = false
        focus.clearFocus()
    }
    Box(Modifier.fillMaxSize().imePadding().testTag("favorite-dialog"), contentAlignment = Alignment.Center) {
        CategoryWorkspace {
            CategoryWorkspaceHeader(strings.text(if (updating) R.string.ui_update_saved_item_e37c0
                else R.string.ui_save_to_notebook_fd727), onDismiss, enabled = !busy,
                actionLabel = strings.text(R.string.ui_cancel_4d0b4))
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().selectableGroup()
                .testTag("favorite-categories"), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "question") {
                    CategoryPaperSurface {
                        Column(Modifier.padding(start = 22.dp, end = 16.dp, top = 16.dp, bottom = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(target.title.ifBlank { strings.text(R.string.ui_photo_question_770f1) },
                                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (target.question.text.isNotBlank()) Text(target.question.text,
                                style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            if (updating) Text(strings.text(R.string.ui_saving_updates_this_item_s_question_and_answer_67ad3),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item(key = "new-category") {
                    if (creating) CategoryCard("", enabled, onRename = {}, creating = true, editingValue = draft,
                        onEditChange = { draft = it }, onSaveEdit = {
                            newCategory = draft.text.trim(); edited = true; creating = false; focus.clearFocus()
                        }, onCancelEdit = { creating = false; focus.clearFocus() },
                        modifier = Modifier.testTag("favorite-category-draft"))
                    else if (newCategory.isNotBlank()) CategoryCard(newCategory, enabled,
                        onRename = { draft = TextFieldValue(newCategory); creating = true },
                        showHandle = false, selected = true,
                        modifier = Modifier.testTag("favorite-new-category"))
                    else OutlinedButton(onClick = { draft = TextFieldValue(); creating = true }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("favorite-add-category")) {
                        Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp))
                        Text(strings.text(R.string.ui_new_category_04c08))
                    }
                }
                item(key = "uncategorized") {
                    CategoryDestination(strings.text(R.string.ui_uncategorized_b28f1),
                        selected = categoryId == null && newCategory.isBlank(), enabled = enabled) { choose(null) }
                }
                items(categories, key = { it.id }) { category ->
                    CategoryDestination(category.name, selected = categoryId == category.id && newCategory.isBlank(),
                        enabled = enabled) { choose(category.id) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { onSave(categoryId, newCategory) }, enabled = enabled && !creating,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("favorite-save")) {
                    if (busy || loading) {
                        if (MogeTheme.motionEnabled) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else CircularProgressIndicator(progress = { .65f }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(strings.text(when {
                        loading -> R.string.ui_loading_saved_item_4bda3
                        busy -> R.string.ui_saving_6644f
                        newCategory.isNotBlank() -> R.string.ui_save_create_category_e7929
                        else -> R.string.ui_save_item_f699e
                    }))
                }
            }
        }
    }
}
