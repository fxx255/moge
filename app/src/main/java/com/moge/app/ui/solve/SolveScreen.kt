package com.moge.app.ui.solve

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.ui.capture.CaptureStore
import com.moge.app.ui.components.PaperScaffold
import com.moge.app.ui.export.AnswerExportContent
import com.moge.app.ui.export.AnswerExportDialog
import com.moge.app.ui.photo.PhotoCropDialog
import com.moge.app.ui.viewer.PhotoViewer
import kotlinx.coroutines.delay

/** 首页与历史会话共用的文字/图像对话页。拍照只回传附件，不自动发送。 */
@Composable
fun SolveScreen(
    conversationId: String?,
    onBack: (() -> Unit)? = null,
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenNotebook: () -> Unit = {},
    onNewConversation: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    vm: SolveViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var menu by remember { mutableStateOf(false) }
    var exportContent by remember { mutableStateOf<AnswerExportContent?>(null) }
    var favorite by remember { mutableStateOf<FavoriteTarget?>(null) }
    var selection by remember { mutableStateOf<AnswerSelection?>(null) }
    val title = if (state.conversationId == null && conversationId == null) "新对话" else state.title
    var cropQueue by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(CaptureStore.MAX_PHOTOS),
    ) { uris -> vm.importPicked(uris) { imported -> cropQueue = cropQueue + imported } }

    fun performAction(answer: SolveItem.Answer, question: SolveItem.Question, collect: Boolean) {
        if (collect) {
            state.conversationId?.let { favorite = FavoriteTarget(it, state.title, question, answer) }
        } else {
            exportContent = AnswerExportContent(state.title, question.text, question.transcript,
                question.photoPaths, answer.text, answer.finalAnswer, answer.figurePaths)
        }
    }
    fun chooseQuestion(answer: SolveItem.Answer, collect: Boolean) {
        val current = questionForAnswer(state.items, answer) ?: return
        val before = state.items.takeWhile { it.id != answer.id }.filterIsInstance<SolveItem.Question>()
        val alternatives = before.filter { it.id != current.id && it.photoPaths.isNotEmpty() }.takeLast(5).asReversed()
        if (current.photoPaths.isNotEmpty() || alternatives.isEmpty()) performAction(answer, current, collect)
        else selection = AnswerSelection(answer, current, alternatives, collect)
    }

    PaperScaffold(title = title, onBack = onBack, actions = {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "对话菜单") }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (state.conversationId != null) DropdownMenuItem(text = { Text("新对话") }, onClick = { menu = false; onNewConversation() })
            DropdownMenuItem(text = { Text("历史对话") }, onClick = { menu = false; onOpenHistory() })
            DropdownMenuItem(text = { Text("我的题册") }, onClick = { menu = false; onOpenNotebook() })
            DropdownMenuItem(text = { Text("设置") }, onClick = { menu = false; onOpenSettings() })
        }
    }) {
        SolveList(state, vm::retry, vm::regenerate,
            onOpenImages = { paths, index -> viewer = paths to index },
            onShare = { chooseQuestion(it, false) }, onSave = { chooseQuestion(it, true) })
        NoticeLine(state.notice, vm::dismissNotice)
        FollowUpBar(state, vm::onInputChange, vm::send, vm::stop,
            onTakePhoto = onTakePhoto,
            onPickPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onOpenPendingPhoto = { index -> viewer = state.photos to index },
            onRemovePendingPhoto = vm::removePhoto)
    }
    cropQueue.firstOrNull()?.let { path ->
        val next = { cropQueue = cropQueue.drop(1); vm.addPhotos(listOf(path)) }
        PhotoCropDialog(path, onCropped = next, onUseOriginal = next,
            onDismiss = { cropQueue = cropQueue.drop(1); vm.discardPhoto(path) })
    }
    viewer?.let { (paths, index) -> PhotoViewer(paths, index.coerceAtLeast(0), onDismiss = { viewer = null }) }
    exportContent?.let { content -> AnswerExportDialog(content, onDismiss = { exportContent = null }) }
    favorite?.let { target -> FavoriteDialog(target, onDismiss = { favorite = null }, onSaved = {
        favorite = null; vm.showNotice("已保存到题册")
    }) }
    selection?.let { pending ->
        AlertDialog(onDismissRequest = { selection = null },
            title = { Text(if (pending.collect) "选择收藏的题目" else "选择分享的题目") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("这次追问没有附图，可选择是否带上前面的题目照片。")
                    TextButton(onClick = { selection = null; performAction(pending.answer, pending.current, pending.collect) }) {
                        Text("仅本次问题：" + pending.current.text.take(45))
                    }
                    pending.alternatives.forEach { previous ->
                        TextButton(onClick = {
                            selection = null
                            performAction(pending.answer, withFollowUp(previous, pending.current), pending.collect)
                        }) { Text("原题与本次追问：" + previous.text.ifBlank { "图片题目" }.take(45)) }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { selection = null }) { Text("取消") } })
    }
}

private data class AnswerSelection(val answer: SolveItem.Answer, val current: SolveItem.Question,
    val alternatives: List<SolveItem.Question>, val collect: Boolean)

internal fun questionForAnswer(items: List<SolveItem>, answer: SolveItem.Answer): SolveItem.Question? {
    if (answer.replyToMessageId.isNotBlank()) {
        return items.filterIsInstance<SolveItem.Question>().firstOrNull { it.id == answer.replyToMessageId }
    }
    return items.takeWhile { it.id != answer.id }.filterIsInstance<SolveItem.Question>().lastOrNull()
}

internal fun withFollowUp(original: SolveItem.Question, followUp: SolveItem.Question): SolveItem.Question =
    original.copy(text = listOf(original.text, "本次追问：" + followUp.text).filter { it.isNotBlank() }.joinToString("\n\n"))

@Composable
private fun ColumnScope.SolveList(
    state: SolveUiState, onRetry: (String) -> Unit, onRegenerate: (String) -> Unit,
    onOpenImages: (List<String>, Int) -> Unit, onShare: (SolveItem.Answer) -> Unit, onSave: (SolveItem.Answer) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.items.size) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }
    if (state.items.isEmpty()) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("输入问题，或拍照、选图开始对话", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(state.items, key = { it.id }) { item ->
            when (item) {
                is SolveItem.Question -> if (item.isFirst) QuestionCard(item, onOpenImages) else FollowUpNote(item, onOpenImages)
                is SolveItem.Answer -> AnswerSheet(item, onRetry, onRegenerate,
                    actionsEnabled = !state.generating && !state.busyElsewhere && !state.submitting,
                    onOpenImages = onOpenImages, answerFirst = state.answerFirst,
                    onShare = { onShare(item) }, onSave = { onSave(item) })
            }
        }
    }
}

@Composable
private fun NoticeLine(notice: String?, onTimeout: () -> Unit) {
    if (notice == null) return
    LaunchedEffect(notice) { delay(4_000); onTimeout() }
    Text(notice, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp).liveRegion())
}
