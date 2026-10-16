package com.moge.app.ui.notebook

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
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除分类“${category.name}”？") },
            text = { Text("该分类的题目与历史对话会移至未分类，内容不会删除。") },
            confirmButton = { TextButton(onClick = { deleteId = null; onDelete(category.id) }, enabled = !busy) { Text("删除分类") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } })
    }
}

@Composable
internal fun CategoryPicker(categories: List<NotebookCategoryEntity>, onDismiss: () -> Unit,
    onManage: () -> Unit = {}, onPick: (String?) -> Unit) {
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CategoryWorkspace {
                CategoryWorkspaceHeader("移动到分类", onDismiss, actionLabel = "取消")
                LazyColumn(Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { CategoryDestination("未分类") { onPick(null) } }
                    items(categories.size, key = { categories[it].id }) { index ->
                        val category = categories[index]
                        CategoryDestination(category.name) { onPick(category.id) }
                    }
                    item { TextButton(onManage, Modifier.fillMaxWidth()) { Text("管理分类") } }
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
