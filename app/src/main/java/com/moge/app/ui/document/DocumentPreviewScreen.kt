package com.moge.app.ui.document

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moge.app.data.document.DocumentAttachment
import com.moge.app.data.document.DocumentIndex
import com.moge.app.ui.components.PaperScaffold
import com.moge.app.ui.components.marginLine
import com.moge.app.ui.settings.formatBytes
import com.moge.app.ui.theme.MogeTheme
import java.io.File

internal data class DocumentPreviewUiState(
    val attachment: DocumentAttachment? = null,
    val index: DocumentIndex? = null,
    val selected: Int = 0,
    val locator: String = "",
    val text: String = "",
    val image: Bitmap? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

@Composable
internal fun DocumentPreviewScreen(
    state: DocumentPreviewUiState,
    onClose: () -> Unit,
    onOpenOriginal: () -> Unit,
    onSection: (Int) -> Unit,
    onLocator: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val sections = state.index?.sections.orEmpty()
    val images = state.index?.images.orEmpty()
    val activeImage = images.indexOf(state.locator)
    val extension = state.attachment?.path?.let { File(it).extension.lowercase() }.orEmpty()
    val unit = when (extension) { "pdf" -> "页"; "pptx" -> "张"; else -> "项" }
    var jump by rememberSaveable(state.attachment?.id) { mutableStateOf(false) }
    val title = when {
        activeImage >= 0 -> "图片 ${activeImage + 1}"
        else -> state.index?.tables?.firstOrNull { it.id == state.locator }?.title
            ?: sections.getOrNull(state.selected)?.title.orEmpty()
    }
    PaperScaffold(title = "文件预览", onBack = onClose, modifier = Modifier.testTag("document-preview"), actions = {
        OutlinedButton(onClick = onOpenOriginal, enabled = state.attachment != null,
            shape = MaterialTheme.shapes.large, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.semantics { contentDescription = "打开原件" }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("原件")
        }
    }) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PreviewFileCard(state)
            if (extension in setOf("docx", "pptx", "xlsx")) {
                Text("此处展示文件内容，完整排版可打开原件查看。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (images.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (activeImage >= 0 && sections.isNotEmpty()) {
                        AssistChip(onClick = { onSection(state.selected) }, label = { Text("返回正文") })
                    }
                    images.forEachIndexed { index, locator ->
                        FilterChip(selected = locator == state.locator, onClick = { onLocator(locator) },
                            label = { Text("图片 ${index + 1}") },
                            leadingIcon = { Icon(Icons.Outlined.Image, null, Modifier.size(16.dp)) })
                    }
                }
            }
            PreviewReadingArea(state, title, Modifier.weight(1f).fillMaxWidth(), onOpenOriginal, onRetry)
            if (sections.isNotEmpty()) {
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onSection(state.selected - 1) }, enabled = state.selected > 0) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一$unit")
                        }
                        TextButton(onClick = { jump = true }, modifier = Modifier.weight(1f)) {
                            Text("第 ${state.selected + 1} / ${sections.size} $unit", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(6.dp))
                            Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(16.dp))
                        }
                        IconButton(onClick = { onSection(state.selected + 1) }, enabled = state.selected < sections.lastIndex) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一$unit")
                        }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
    if (jump && sections.isNotEmpty()) {
        PreviewJumpDialog(state.selected, sections.size, unit, onDismiss = { jump = false }) { position ->
            onSection(position); jump = false
        }
    }
}

@Composable
private fun PreviewFileCard(state: DocumentPreviewUiState) {
    val document = state.attachment
    val (icon, kind) = documentIcon(document?.path.orEmpty())
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(document?.name ?: if (state.busy) "正在读取文件…" else "文件",
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("preview-file-name"))
                document?.let {
                    Text("$kind · ${formatBytes(it.bytes)}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun PreviewReadingArea(
    state: DocumentPreviewUiState,
    title: String,
    modifier: Modifier,
    onOpenOriginal: () -> Unit,
    onRetry: () -> Unit,
) {
    Surface(modifier.testTag("preview-reading-area"), color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        when {
            state.busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text("正在读取内容…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.error != null -> PreviewMessage("暂时无法预览", state.error, error = true,
                button = { FilledTonalButton(onClick = onRetry) { Text("重新加载") } })
            state.image != null || state.text.isNotBlank() -> key(state.locator) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    if (title.isNotBlank()) {
                        Text(title, Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    state.image?.let { bitmap ->
                        Image(bitmap.asImageBitmap(), "文档页面", Modifier.fillMaxWidth().padding(10.dp))
                    }
                    if (state.text.isNotBlank()) {
                        SelectionContainer {
                            Box(Modifier.fillMaxWidth().marginLine(MogeTheme.paper.marginLine, inset = 16.dp)
                                .padding(start = 30.dp, end = 18.dp, top = 18.dp, bottom = 24.dp)) {
                                Text(state.text, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("preview-body"))
                            }
                        }
                    }
                }
            }
            state.index?.sections.isNullOrEmpty() -> PreviewMessage("此格式暂不支持预览", "可以打开原件查看完整内容。",
                button = { FilledTonalButton(onClick = onOpenOriginal, enabled = state.attachment != null) { Text("打开原件") } })
            else -> PreviewMessage("这里没有可显示的内容", "可查看文件中的图片，或打开原件。")
        }
    }
}

@Composable
private fun PreviewMessage(title: String, detail: String, error: Boolean = false, button: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        if (error) Icon(Icons.Outlined.ErrorOutline, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.error)
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        button?.invoke()
    }
}

@Composable
private fun PreviewJumpDialog(current: Int, count: Int, unit: String, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf((current + 1).toString()) }
    val position = input.toIntOrNull()?.minus(1)?.takeIf { it in 0 until count }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(horizontal = 24.dp).widthIn(max = 400.dp).fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("跳转位置", style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(input, { input = it.filter(Char::isDigit).take(6) }, singleLine = true,
                    label = { Text("位置（1—$count $unit）") }, isError = input.isNotEmpty() && position == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { position?.let(onSelect) }),
                    supportingText = { Text(if (input.isNotEmpty() && position == null) "请输入范围内的数字" else "共 $count $unit") },
                    modifier = Modifier.fillMaxWidth().testTag("preview-jump-input"))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    TextButton(onClick = { position?.let(onSelect) }, enabled = position != null) { Text("前往") }
                }
            }
        }
    }
}
