package com.moge.app.ui.notebook

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moge.app.data.db.NotebookCategoryEntity

@Composable
internal fun CategoryManager(
    categories: List<NotebookCategoryEntity>, busy: Boolean, onDismiss: () -> Unit,
    onCreate: (String) -> Unit, onRename: (String, String) -> Unit,
    onReorder: (String, Int) -> Unit, onDelete: (String) -> Unit,
) {
    val strings = rememberAppStrings()
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val categoryList = rememberLazyListState()
    LaunchedEffect(categories.map { it.id }) {
        if (categories.none { it.id == deleteId }) deleteId = null
    }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CategoryManagerContent(categories, busy, onCreate = onCreate, onRename = onRename,
            onReorder = onReorder, onDelete = { deleteId = it }, onDismiss = onDismiss,
            modifier = Modifier.fillMaxSize().imePadding(), listState = categoryList)
    }
    categories.firstOrNull { it.id == deleteId }?.let { category ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text(strings.text(R.string.ui_delete_category_1_s_4cda9, category.name)) },
            text = { Text(strings.text(R.string.ui_questions_and_conversations_move_to_uncategorized_their_cont_b026e)) },
            confirmButton = { TextButton(onClick = { deleteId = null; onDelete(category.id) }, enabled = !busy) { Text(strings.text(R.string.ui_delete_category_6add2)) } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text(strings.text(R.string.ui_cancel_4d0b4)) } })
    }
}

@Composable
internal fun CategoryPicker(categories: List<NotebookCategoryEntity>, onDismiss: () -> Unit,
    onManage: () -> Unit = {}, onPick: (String?) -> Unit) {
    val strings = rememberAppStrings()
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CategoryWorkspace {
                CategoryWorkspaceHeader(strings.text(R.string.ui_move_to_category_48df9), onDismiss, actionLabel = strings.text(R.string.ui_cancel_4d0b4))
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { CategoryDestination(strings.text(R.string.ui_uncategorized_b28f1)) { onPick(null) } }
                    items(categories.size, key = { categories[it].id }) { index ->
                        val category = categories[index]
                        CategoryDestination(category.name) { onPick(category.id) }
                    }
                    item { TextButton(onManage, Modifier.fillMaxWidth()) { Text(strings.text(R.string.ui_manage_categories_fed61)) } }
                }
            }
        }
    }
}

@Composable
private fun CategoryDestination(name: String, onClick: () -> Unit) {
    CategoryPaperSurface(onClick = onClick) {
        Text(name, style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 18.dp, end = 16.dp, top = 18.dp, bottom = 18.dp))
    }
}
