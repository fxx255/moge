package com.moge.app.ui.export

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.moge.app.ui.components.GridPaper
import com.moge.app.ui.components.HighlightedTitle
import com.moge.app.ui.components.Tape
import com.moge.app.ui.components.marginLine
import com.moge.app.ui.components.paperCard
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.markdown.LocalFigurePathResolver
import com.moge.app.ui.photo.decodeUprightPhoto
import com.moge.app.ui.viewer.galleryNeedsLegacyPermission
import com.moge.app.ui.viewer.saveImagesToGallery
import com.moge.app.ui.viewer.shareImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SolveScreen's share callback passes the selected question and its completed answer snapshot.
 * This preview never searches conversation history or reads the screen's collapsed state.
 * For interrupted answers, the caller must label the snapshot explicitly before opening it.
 * Question selection stays with the caller; this dialog asks the user to confirm the supplied
 * question/photos/transcript before producing images. Defaults to question + full answer.
 */
@Composable
fun AnswerExportDialog(content: AnswerExportContent, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val resolver = LocalFigurePathResolver.current
    val scope = rememberCoroutineScope()
    val session = remember(content) { ExportDialogSession() }
    var result by remember(content) { mutableStateOf<ExportResult?>(null) }
    var choice by remember(content) { mutableStateOf(ExportChoice.FULL) }
    var busy by remember(content) { mutableStateOf(false) }
    var progress by remember(content) { mutableStateOf("") }
    var message by remember(content) { mutableStateOf<String?>(null) }
    var confirmed by remember(content) { mutableStateOf(false) }
    var page by remember(content) { mutableIntStateOf(0) }

    DisposableEffect(session) {
        onDispose {
            session.active = false
            session.job?.cancel()
            // The Compose scope is cancelled on dismissal. NonCancellable is needed to remove
            // ready, unshared files as well as the renderer's in-progress cleanup.
            scope.launch(NonCancellable + Dispatchers.IO) { session.result?.files?.close() }
        }
    }

    fun render() {
        if (busy) return
        busy = true; message = null; progress = "正在准备纸面…"; page = 0
        session.job = scope.launch {
            try {
                session.result?.files?.close()
                session.result = null; result = null
                val next = renderAnswerExport(context, content, choice, resolver) { progress = it }
                session.result = next
                result = next
                progress = "已生成 ${next.pages.size} 张连续图片"
            } catch (_: CancellationException) {
                if (session.active) message = "已取消，临时图片已清理"
            } catch (_: Throwable) {
                if (session.active) message = "导出失败，请重试。请检查可用存储空间与题目附件。"
            } finally { if (session.active) busy = false }
        }
    }

    fun save() {
        val ready = result ?: return
        if (busy) return
        busy = true; message = null; progress = "正在保存图片…"
        session.job = scope.launch {
            try {
                val album = saveImagesToGallery(context, ready.pages.map { it.absolutePath }) { saved, total ->
                    progress = "正在保存 $saved / $total 张图片…"
                }
                message = "已保存全部 ${ready.pages.size} 张图片到 $album"
            } catch (_: CancellationException) {
                if (session.active) message = "已取消保存，本次相册写入已撤销"
            } catch (_: Throwable) {
                if (session.active) message = "保存失败，本次相册写入已撤销，请重试"
            } finally { if (session.active) busy = false }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && session.active) save()
        else if (session.active) message = "没有存储权限，无法保存到相册；仍可系统分享"
    }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        GridPaper(Modifier.fillMaxSize().testTag("answer-export-dialog")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HighlightedTitle("图片分享", modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, if (busy) "取消并关闭" else "关闭分享") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportChoiceCard("题目与完整解答", selected = choice == ExportChoice.FULL, enabled = !busy,
                        onClick = { choice = ExportChoice.FULL; confirmed = false }, modifier = Modifier.weight(1f))
                    ExportChoiceCard("题目与答案", selected = choice == ExportChoice.ANSWER_ONLY,
                        enabled = !busy && content.finalAnswer.isNotBlank(),
                        onClick = { choice = ExportChoice.ANSWER_ONLY; confirmed = false }, modifier = Modifier.weight(1f))
                }
                if (content.finalAnswer.isBlank()) Text("此回答没有独立答案，导出完整解答。", style = MaterialTheme.typography.bodySmall)
                if (!confirmed) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .paperCard(MaterialTheme.colorScheme.surface, MogeTheme.paper.cardStroke)
                        .marginLine(MogeTheme.paper.marginLine, inset = 16.dp)
                        .padding(start = 28.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("核对题目", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text("确认题目和照片，再生成分享稿纸。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(content.title.ifBlank { "题目与解答" }, style = MaterialTheme.typography.titleMedium)
                        if (content.questionText.isNotBlank()) Text(content.questionText)
                        // Display one selected photo at a time so proofing many attachments
                        // never retains one decoded bitmap per question photo.
                        if (content.questionPhotos.isNotEmpty()) QuestionPhotos(content.questionPhotos)
                        if (content.questionTranscript.isNotBlank()) {
                            Text("已有识别文本", style = MaterialTheme.typography.titleSmall)
                            Text(content.questionTranscript)
                        }
                        Text("分享图片使用高清浅色稿纸，优先生成一张长图；超长解答按页码分张。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { confirmed = true; render() }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("export-confirm-question")) { Text("确认题目并预览") }
                } else {
                    val ready = result
                    if (ready != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { page-- }, enabled = !busy && page > 0) { Text("上一张") }
                            Text("${page + 1} / ${ready.pages.size} · 按页码连续阅读", modifier = Modifier.weight(1f))
                            TextButton(onClick = { page++ }, enabled = !busy && page < ready.pages.lastIndex) { Text("下一张") }
                        }
                        key(ready, page) {
                            ready.pages.getOrNull(page)?.let { selected ->
                                ExportLongImagePreview(selected.absolutePath, "导出预览，第 ${page + 1} 张",
                                    Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                        Text("双击放大，滑动查看细节", style = MaterialTheme.typography.bodySmall)
                        if (ready.warnings.isNotEmpty()) {
                            Text("预览中已标明：" + ready.warnings.take(3).joinToString("；") +
                                if (ready.warnings.size > 3) "；还有 ${ready.warnings.size - 3} 处，请逐页核对" else "",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    } else Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (busy) "正在排版…" else "图片尚未生成，请重试")
                    }
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(progress, modifier = Modifier.weight(1f))
                            TextButton(onClick = { session.job?.cancel() }) { Text("取消") }
                        }
                    } else if (ready == null) Button(onClick = ::render, modifier = Modifier.fillMaxWidth()) { Text("重试导出") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && ready != null, modifier = Modifier.weight(1f), onClick = {
                            if (galleryNeedsLegacyPermission() && ContextCompat.checkSelfPermission(context,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else save()
                        }) { Text("保存全部图片") }
                        Button(enabled = !busy && ready != null, modifier = Modifier.weight(1f), onClick = {
                            try {
                                val exported = checkNotNull(ready)
                                shareImages(context, exported.pages.map { it.absolutePath })
                                exported.files.retainForSharing()
                                message = "已打开系统分享，共 ${exported.pages.size} 张图片"
                            } catch (_: Exception) { message = "无法打开系统分享，请重试或先保存图片" }
                        }) { Text("系统分享") }
                    }
                    TextButton(enabled = !busy, onClick = { confirmed = false }) { Text("重新核对题目 / 导出范围") }
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("export-message")) }
            }
        }
        }
    }
}

private class ExportDialogSession {
    var active = true
    var job: Job? = null
    var result: ExportResult? = null
}

@Composable
private fun ExportChoiceCard(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(selected = selected, onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small,
        color = if (selected) colors.primaryContainer else colors.surface,
        contentColor = if (!enabled) colors.onSurface.copy(alpha = 0.38f) else if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) colors.primary else MogeTheme.paper.cardStroke.copy(alpha = 0.4f)),
        modifier = modifier) {
        Text(label, Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun QuestionPhotos(paths: List<String>) {
    if (paths.isEmpty()) return
    var index by remember(paths) { mutableIntStateOf(0) }
    val current = index.coerceIn(paths.indices)
    Text("题目照片 ${current + 1} / ${paths.size}", style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary)
    Box(Modifier.fillMaxWidth()) {
        ExportPreviewImage(paths[current], "待确认的题目照片 ${current + 1}")
        Tape(Modifier.align(Alignment.TopEnd).padding(end = 12.dp), width = 42.dp, height = 14.dp)
    }
    if (paths.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = current > 0, onClick = { index = (current - 1).coerceAtLeast(0) }) { Text("上一张照片") }
        TextButton(enabled = current < paths.lastIndex, onClick = { index = (current + 1).coerceAtMost(paths.lastIndex) }) { Text("下一张照片") }
    }
}

/**
 * Each path owns a fresh composition so produceState cannot carry a previous page's bitmap.
 * Once published to Compose, bitmaps are managed by GC: rendering may still reference an old
 * display list after disposal, so manually recycling that bitmap can crash the next frame.
 * Only the current sampled image is held by this composable; obsolete producers are cancelled.
 */
@Composable
internal fun ExportPreviewImage(
    path: String,
    description: String,
    decoder: suspend (String, Int) -> Bitmap? = { source, maxSide ->
        withContext(Dispatchers.IO) { decodeUprightPhoto(source, maxSide) }
    },
) {
    key(path) {
        val preview by produceState<PreviewImage>(PreviewImage.Loading, path) {
            value = try {
                decoder(path, 1000)?.let { PreviewImage.Ready(it) } ?: PreviewImage.Missing
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { PreviewImage.Missing }
        }
        val paper = MogeTheme.paper
        Box(Modifier.fillMaxWidth().height(240.dp)
            .paperCard(MaterialTheme.colorScheme.surface, paper.cardStroke.copy(alpha = 0.55f)).padding(6.dp),
            contentAlignment = Alignment.Center) {
            when (val current = preview) {
                PreviewImage.Loading -> Text("正在读取图片…", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                PreviewImage.Missing -> Text("图片无法读取，请返回核对原题或重新生成。", Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                is PreviewImage.Ready -> Image(current.bitmap.asImageBitmap(), description,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize())
            }
        }
    }
}

private sealed interface PreviewImage {
    data object Loading : PreviewImage
    data object Missing : PreviewImage
    data class Ready(val bitmap: Bitmap) : PreviewImage
}
