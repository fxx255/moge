package com.moge.app.ui.document

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.moge.app.data.document.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DocumentCards(paths: List<String>, onRemove: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val store = remember(context) { DocumentStore(context) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        paths.forEach { path -> key(path) {
            val document by produceState<com.moge.app.data.document.DocumentAttachment?>(null, path) {
                value = withContext(Dispatchers.IO) { runCatching { store.attachment(path) }.getOrNull() }
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { DocumentPreviewActivity.open(context, path) }, modifier = Modifier.weight(1f)) {
                    Text(document?.let { "${it.name} · ${(it.bytes / 1024).coerceAtLeast(1)} KB" } ?: File(path).name)
                }
                onRemove?.let { remove -> TextButton(onClick = { remove(path) }) { Text("移除") } }
            }
        } }
    }
}
