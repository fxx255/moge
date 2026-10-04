package com.moge.app.ui.solve

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.ui.capture.CaptureStore
import com.moge.app.ui.components.PageSwipeSurface
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
    onResumeConversation: (() -> Unit)? = null,
    onConversationObserved: (String?) -> Unit = {},
    onViewFavorite: (String) -> Unit = {},
    onSwipeHistory: () -> Unit = onOpenHistory,
    onSwipeNotebook: () -> Unit = onOpenNotebook,
    onSwipeNewConversation: () -> Unit = onNewConversation,
    onSwipeResumeConversation: (() -> Unit)? = onResumeConversation,
    readViewport: (String) -> ConversationViewport? = { null },
    saveViewport: (String, ConversationViewport) -> Unit = { _, _ -> },
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var exportContent by remember { mutableStateOf<AnswerExportContent?>(null) }
    var favorite by remember { mutableStateOf<FavoriteTarget?>(null) }
    var selection by remember { mutableStateOf<AnswerSelection?>(null) }
    var savedFavoriteId by rememberSaveable { mutableStateOf<String?>(null) }
    val observedId = state.conversationId ?: conversationId
    LaunchedEffect(observedId) { onConversationObserved(observedId) }
    LaunchedEffect(state.notice) {
        if (state.notice != "已保存到题册") savedFavoriteId = null
    }
    val title = if (state.conversationId == null && conversationId == null) "新对话" else state.title
    var cropQueue by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(CaptureStore.MAX_PHOTOS),
    ) { uris -> vm.importPicked(uris) { imported -> cropQueue = cropQueue + imported } }
    val pickDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importDocument)
    }

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

    val existing = observedId != null
    PageSwipeSurface(enabled = viewer == null && exportContent == null && favorite == null &&
        selection == null && cropQueue.isEmpty(),
        onLeft = if (existing) onSwipeNotebook else onSwipeResumeConversation,
        onRight = if (existing) onSwipeNewConversation else onSwipeHistory,
        leftLabel = if (existing) "前往我的题册" else "返回上一个对话",
        rightLabel = if (existing) "开始新对话" else "前往历史对话") {
        ConversationScaffold(title = title, onBack = onBack, actions = {
            ConversationActions(
                existingConversation = state.conversationId != null || conversationId != null,
                onNewConversation = onNewConversation,
                onOpenHistory = onOpenHistory,
                onOpenSettings = onOpenSettings,
            )
        }, footer = {
            NoticeLine(state.notice, vm::dismissNotice,
                action = savedFavoriteId?.takeIf { state.notice == "已保存到题册" }?.let { id ->
                    { onViewFavorite(id); vm.dismissNotice() }
                })
            FollowUpBar(state, vm::onInputChange, vm::send, vm::stop,
                onTakePhoto = onTakePhoto,
                onPickPhotos = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onPickDocument = {
                    pickDocument.launch(arrayOf(
                        "application/pdf",
                        "application/msword",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        "application/vnd.ms-powerpoint",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "application/vnd.ms-excel",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                        "text/plain",
                        "text/markdown",
                        "text/csv",
                    ))
                },
                onOpenPendingPhoto = { index -> viewer = state.photos to index },
                onRemovePendingPhoto = vm::removePhoto, onRemoveDocument = vm::removeDocument,
                onPasteImages = vm::pasteImages)
        }) { padding ->
            val initialViewport = remember(observedId) { observedId?.let(readViewport) }
            SolveList(state, vm::retry, vm::regenerate,
                onOpenImages = { paths, index -> viewer = paths to index },
                onShare = { chooseQuestion(it, false) }, onSave = { chooseQuestion(it, true) },
                onResume = vm::resume,
                conversationId = observedId, initialViewport = initialViewport,
                saveViewport = { viewport -> observedId?.let { saveViewport(it, viewport) } },
                contentPadding = padding)
        }
    }
    cropQueue.firstOrNull()?.let { path ->
        val next = { cropQueue = cropQueue.drop(1); vm.addPhotos(listOf(path)) }
        PhotoCropDialog(path, onCropped = next, onUseOriginal = next,
            onDismiss = { cropQueue = cropQueue.drop(1); vm.discardPhoto(path) })
    }
    viewer?.let { (paths, index) -> PhotoViewer(paths, index.coerceAtLeast(0), onDismiss = { viewer = null }) }
    exportContent?.let { content -> AnswerExportDialog(content, onDismiss = { exportContent = null }) }
    favorite?.let { target -> FavoriteDialog(target, onDismiss = { favorite = null }, onSaved = { id ->
        savedFavoriteId = id
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

/** Keep each action directly reachable and anchored to the right side of the paper header. */
@Composable
internal fun ConversationActions(
    existingConversation: Boolean,
    onNewConversation: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row {
        if (existingConversation) {
            IconButton(onClick = onNewConversation) { Icon(Icons.Outlined.AddComment, "新对话") }
        } else {
            IconButton(onClick = onOpenHistory) { Icon(Icons.Outlined.History, "历史对话") }
        }
        IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, "设置") }
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
internal fun SolveList(
    state: SolveUiState, onRetry: (String) -> Unit, onRegenerate: (String) -> Unit,
    onOpenImages: (List<String>, Int) -> Unit, onShare: (SolveItem.Answer) -> Unit, onSave: (SolveItem.Answer) -> Unit,
    conversationId: String? = state.conversationId,
    initialViewport: ConversationViewport? = null,
    saveViewport: (ConversationViewport) -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(16.dp),
    onResume: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()
    var restored by remember(conversationId) { mutableStateOf(false) }
    var questionIds by remember(conversationId) { mutableStateOf<List<String>?>(null) }
    val currentItems by rememberUpdatedState(state.items)
    val save by rememberUpdatedState(saveViewport)
    val ids = state.items.map { it.id }
    LaunchedEffect(conversationId, ids) {
        if (ids.isEmpty()) return@LaunchedEffect
        val questions = state.items.filterIsInstance<SolveItem.Question>().map { it.id }
        if (!restored) {
            val anchor = initialViewport
            listState.scrollToItem(anchor?.indexIn(ids) ?: ids.lastIndex, anchor?.offset ?: 0)
            restored = true
        } else if (questionIds != null && questions.any { it !in questionIds.orEmpty() }) {
            // Only a new question moves the viewport; reopening/history loading/answer refresh do not.
            listState.animateScrollToItem(ids.lastIndex)
        }
        questionIds = questions
    }
    fun recordViewport() {
        if (!restored) return
        val index = listState.firstVisibleItemIndex
        currentItems.getOrNull(index)?.let { save(ConversationViewport(it.id, index, listState.firstVisibleItemScrollOffset)) }
    }
    LaunchedEffect(listState, conversationId, restored) {
        if (restored) snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { recordViewport() }
    }
    DisposableEffect(listState, conversationId) {
        onDispose { recordViewport() }
    }
    if (state.items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(contentPadding).padding(16.dp), contentAlignment = Alignment.Center) {
            Text("输入问题，或拍照、选图开始对话", style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("conversation-list"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(state.items, key = { it.id }) { item ->
            when (item) {
                is SolveItem.Question -> if (item.isFirst) QuestionCard(item, onOpenImages) else FollowUpNote(item, onOpenImages)
                is SolveItem.Answer -> AnswerSheet(item, onRetry, onRegenerate,
                    actionsEnabled = !state.generating && !state.busyElsewhere && !state.submitting,
                    onOpenImages = onOpenImages, answerFirst = state.answerFirst,
                    onResume = onResume,
                    onShare = { onShare(item) }, onSave = { onSave(item) })
            }
        }
    }
}

@Composable
private fun NoticeLine(notice: String?, onTimeout: () -> Unit, action: (() -> Unit)? = null) {
    if (notice == null) return
    LaunchedEffect(notice) { delay(4_000); onTimeout() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(notice, style = MaterialTheme.typography.bodyMedium,
            color = if (action != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).liveRegion())
        if (action != null) TextButton(onClick = action) { Text("查看题册") }
    }
}
