package com.moge.app.ui.document

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moge.app.data.document.DocumentStore
import com.moge.app.ui.settings.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Pending originals float above the composer; removing a chip only detaches it from the draft. */
@Composable
fun PendingDocumentChips(paths: List<String>, onRemove: (String) -> Unit) {
    if (paths.isEmpty()) return
    val context = LocalContext.current
    val store = remember(context) { DocumentStore(context) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paths.forEach { path -> key(path) {
            val document by produceState<com.moge.app.data.document.DocumentAttachment?>(null, path) {
                value = withContext(Dispatchers.IO) { runCatching { store.attachment(path) }.getOrNull() }
            }
            val name = document?.name ?: File(path).name
            val (icon, kind) = documentIcon(path)
            Surface(
                modifier = Modifier.widthIn(min = 144.dp, max = 228.dp),
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp,
                tonalElevation = 2.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).heightIn(min = 48.dp)
                            .clickable { DocumentPreviewActivity.open(context, path) }
                            .padding(start = 14.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(icon, kind, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onRemove(path) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.Close, "移除文档 $name", Modifier.size(18.dp))
                    }
                }
            }
        } }
    }
}

@Composable
fun DocumentCards(paths: List<String>, onRemove: ((String) -> Unit)? = null) {
    if (paths.isEmpty()) return
    val context = LocalContext.current
    val store = remember(context) { DocumentStore(context) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        paths.forEach { path -> key(path) {
            val document by produceState<com.moge.app.data.document.DocumentAttachment?>(null, path) {
                value = withContext(Dispatchers.IO) { runCatching { store.attachment(path) }.getOrNull() }
            }
            val name = document?.name ?: File(path).name
            val (icon, kind) = documentIcon(path)
            Surface(
                modifier = Modifier.fillMaxWidth().testTag("document-card"),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable { DocumentPreviewActivity.open(context, path) }
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(icon, kind, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                            Text(document?.let { "$kind · ${formatBytes(it.bytes)}" } ?: kind,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    onRemove?.let { remove ->
                        IconButton(onClick = { remove(path) }) {
                            Icon(Icons.Outlined.Close, "移除文档 $name", Modifier.size(18.dp))
                        }
                    }
                }
            }
        } }
    }
}

internal fun documentIcon(path: String) = when (File(path).extension.lowercase()) {
    "pdf" -> Icons.Outlined.PictureAsPdf to "PDF 文档"
    "doc", "docx" -> Icons.Outlined.Description to "Word 文档"
    "ppt", "pptx" -> Icons.Outlined.Slideshow to "演示文稿"
    "xls", "xlsx", "csv" -> Icons.Outlined.GridOn to "表格"
    else -> Icons.AutoMirrored.Outlined.TextSnippet to "文本文档"
}
