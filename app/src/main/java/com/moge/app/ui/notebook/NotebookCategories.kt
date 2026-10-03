package com.moge.app.ui.notebook

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moge.app.data.db.NotebookCategoryEntity

@Composable
internal fun CategoryManager(
    categories: List<NotebookCategoryEntity>, busy: Boolean, onDismiss: () -> Unit,
    onCreate: (String) -> Unit, onRename: (String, String) -> Unit,
    onReorder: (String, Int) -> Unit, onDelete: (String) -> Unit,
) {
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val categoryList = rememberLazyListState()
    LaunchedEffect(categories.map { it.id }) {
        if (categories.none { it.id == deleteId }) deleteId = null
    }
    // Creation keeps the same window; renaming happens directly inside the category card.
    Dialog(onDismissRequest = {
        when {
            createOpen -> createOpen = false
            else -> onDismiss()
        }
    }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().imePadding().padding(horizontal = 12.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            when {
                createOpen -> CategoryNameForm("新建分类", "", { createOpen = false }) { name ->
                    createOpen = false; onCreate(name)
                }
                else -> Surface(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(), shape = RoundedCornerShape(24.dp)) {
                    CategoryManagerContent(categories, busy,
                        onCreate = { createOpen = true }, onRename = onRename,
                        onReorder = onReorder, onDelete = { deleteId = it }, onDismiss = onDismiss,
                        listState = categoryList)
                }
            }
        }
    }
    categories.firstOrNull { it.id == deleteId }?.let { category ->
        AlertDialog(onDismissRequest = { deleteId = null }, title = { Text("删除分类“${category.name}”？") },
            text = { Text("该分类的题目与历史对话会移至未分类，内容不会删除。") },
            confirmButton = { TextButton(onClick = { deleteId = null; onDelete(category.id) }, enabled = !busy) { Text("删除分类") } },
            dismissButton = { TextButton(onClick = { deleteId = null }) { Text("取消") } })
    }
}

@Composable
internal fun CategoryPicker(categories: List<NotebookCategoryEntity>, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("移动到分类") },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            TextButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) { Text("未分类") }
            categories.forEach { category ->
                TextButton(onClick = { onPick(category.id) }, modifier = Modifier.fillMaxWidth()) { Text(category.name) }
            }
        } }, confirmButton = { TextButton(onDismiss) { Text("取消") } })
}

@Composable
private fun CategoryNameForm(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(title, initial) { mutableStateOf(initial) }
    val cleaned = name.trim()
    val valid = cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 80
    Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(name, { name = it }, label = { Text("分类名称") }, singleLine = true,
                supportingText = { Text("1–80 个字") }, isError = !valid, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss) { Text("取消") }
                TextButton(onClick = { onSave(cleaned) }, enabled = valid) { Text("保存") }
            }
        }
    }
}
