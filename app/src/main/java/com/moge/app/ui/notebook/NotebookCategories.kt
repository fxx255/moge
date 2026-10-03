package com.moge.app.ui.notebook

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moge.app.data.db.NotebookCategoryEntity

@Composable
internal fun CategoryManager(
    categories: List<NotebookCategoryEntity>, busy: Boolean, onDismiss: () -> Unit,
    onCreate: (String) -> Unit, onRename: (String, String) -> Unit,
    onReorder: (String, Int) -> Unit, onDelete: (String) -> Unit,
) {
    var createOpen by rememberSaveable { mutableStateOf(false) }
    var renameId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("管理分类") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { createOpen = true }, enabled = !busy) { Text("新建分类") }
                if (categories.isEmpty()) Text("按自己的需要新建分类。删除分类会把题目与历史对话移至未分类。")
                categories.forEachIndexed { index, category ->
                    Column(Modifier.fillMaxWidth()) {
                        Text(category.name, style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onReorder(category.id, -1) }, enabled = !busy && index > 0) {
                                Icon(Icons.Outlined.ArrowUpward, "上移 ${category.name}")
                            }
                            IconButton(onClick = { onReorder(category.id, 1) }, enabled = !busy && index < categories.lastIndex) {
                                Icon(Icons.Outlined.ArrowDownward, "下移 ${category.name}")
                            }
                            IconButton(onClick = { renameId = category.id }, enabled = !busy) {
                                Icon(Icons.Outlined.Edit, "改名 ${category.name}")
                            }
                            IconButton(onClick = { deleteId = category.id }, enabled = !busy) {
                                Icon(Icons.Outlined.Delete, "删除分类 ${category.name}")
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onDismiss) { Text("完成") } })
    if (createOpen) CategoryNameDialog("新建分类", "", { createOpen = false }) { name ->
        createOpen = false; onCreate(name)
    }
    categories.firstOrNull { it.id == renameId }?.let { category ->
        CategoryNameDialog("分类改名", category.name, { renameId = null }) { name -> renameId = null; onRename(category.id, name) }
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
private fun CategoryNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by rememberSaveable(title, initial) { mutableStateOf(initial) }
    val cleaned = name.trim()
    val valid = cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 80
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, label = { Text("分类名称") }, singleLine = true,
            supportingText = { Text("1–80 个字") }, isError = !valid, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(cleaned) }, enabled = valid) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } })
}
