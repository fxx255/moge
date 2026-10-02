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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
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

    MaterialTheme(colorScheme = lightColorScheme(
        primary = Color(0xFF315F53), background = Color(EXPORT_PAPER), surface = Color(EXPORT_PAPER),
        onBackground = Color(EXPORT_INK), onSurface = Color(EXPORT_INK),
    )) {
        Dialog(onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize().testTag("answer-export-dialog")) {
                Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("图片分享", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text(if (busy) "取消并关闭" else "关闭") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = choice == ExportChoice.FULL, enabled = !busy,
                            onClick = { choice = ExportChoice.FULL; confirmed = false }, label = { Text("题目与完整解答") })
                        FilterChip(selected = choice == ExportChoice.ANSWER_ONLY,
                            enabled = !busy && content.finalAnswer.isNotBlank(),
                            onClick = { choice = ExportChoice.ANSWER_ONLY; confirmed = false }, label = { Text("题目与答案") })
                    }
                    if (content.finalAnswer.isBlank()) Text("此回答没有独立答案，导出完整解答。", style = MaterialTheme.typography.bodySmall)
                    if (!confirmed) {
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("请确认分享的是这道题；如关联照片有误，返回选择题目后再分享。", style = MaterialTheme.typography.bodyMedium)
                            Text(content.title.ifBlank { "题目与解答" }, style = MaterialTheme.typography.titleMedium)
                            if (content.questionText.isNotBlank()) Text(content.questionText)
                            else Text("题目文字为空，请核对照片 / 识别文本。", style = MaterialTheme.typography.bodySmall)
                            // Display one selected photo at a time so proofing many attachments
                            // never retains one decoded bitmap per question photo.
                            if (content.questionPhotos.isNotEmpty()) QuestionPhotos(content.questionPhotos)
                            if (content.questionTranscript.isNotBlank()) {
                                Text("已有识别文本", style = MaterialTheme.typography.titleSmall)
                                Text(content.questionTranscript)
                            }
                            Text("将导出浅色纸面；较长内容会按顺序分成多张图片。", style = MaterialTheme.typography.bodySmall)
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
                            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("export-preview")) {
                                ExportPreviewImage(ready.pages[page].absolutePath, "导出预览，第 ${page + 1} 张", previewPage = true)
                            }
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
private fun QuestionPhotos(paths: List<String>) {
    var index by remember(paths) { mutableIntStateOf(0) }
    Text("题目照片 ${index + 1} / ${paths.size}")
    ExportPreviewImage(paths[index], "待确认的题目照片 ${index + 1}", previewPage = false)
    if (paths.size > 1) Row {
        TextButton(enabled = index > 0, onClick = { index-- }) { Text("上一张照片") }
        TextButton(enabled = index < paths.lastIndex, onClick = { index++ }) { Text("下一张照片") }
    }
}

/** One sampled preview only. Decoding remains on IO and disposal releases it on page changes. */
@Composable
private fun ExportPreviewImage(path: String, description: String, previewPage: Boolean) {
    val holder = remember(path, previewPage) { PreviewBitmap() }
    val bitmap by produceState<Bitmap?>(null, holder) {
        try {
            withContext(Dispatchers.IO) {
                val decoded = decodeUprightPhoto(path, if (previewPage) 1600 else 800)
                synchronized(holder) {
                    if (holder.disposed) decoded?.recycle() else holder.bitmap = decoded
                }
            }
            value = holder.bitmap
        } catch (error: CancellationException) { throw error }
    }
    DisposableEffect(holder) {
        onDispose { synchronized(holder) { holder.disposed = true; holder.bitmap?.recycle(); holder.bitmap = null } }
    }
    if (bitmap == null) Text("$description：正在读取或图片缺失，请核对")
    else Image(bitmap!!.asImageBitmap(), description, contentScale = ContentScale.FillWidth,
        modifier = Modifier.fillMaxWidth().then(if (previewPage) Modifier else Modifier.heightIn(max = 360.dp)))
}

private class PreviewBitmap { var bitmap: Bitmap? = null; var disposed = false }
