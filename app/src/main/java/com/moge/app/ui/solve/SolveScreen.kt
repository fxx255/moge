package com.moge.app.ui.solve

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.ui.capture.CaptureStore
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.components.PageSwipeSurface
import com.moge.app.ui.components.KeepConversationScreenOn
import com.moge.app.ui.export.AnswerExportContent
import com.moge.app.ui.export.AnswerExportDialog
import com.moge.app.ui.photo.PhotoCropDialog
import com.moge.app.ui.viewer.PhotoViewer
import com.moge.app.ui.markdown.LocalSelectionScrollHost
import com.moge.app.ui.markdown.SelectionScrollHost
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
    pageMotion: ConversationPageMotion = ConversationPageMotion(),
    ribbonState: ConversationRibbonState? = null,
) {
    val strings = rememberAppStrings()
    val state by vm.uiState.collectAsStateWithLifecycle()
    KeepConversationScreenOn(state.keepScreenOnInConversation)
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var exportContent by remember { mutableStateOf<AnswerExportContent?>(null) }
    var favorite by remember { mutableStateOf<FavoriteTarget?>(null) }
    var selection by remember { mutableStateOf<AnswerSelection?>(null) }
    var savedFavoriteId by rememberSaveable { mutableStateOf<String?>(null) }
    val observedId = state.conversationId ?: conversationId
    var editingTitle by rememberSaveable(observedId) { mutableStateOf(false) }
    LaunchedEffect(observedId) { onConversationObserved(observedId) }
    LaunchedEffect(state.notice) {
        if (state.notice != strings.text(R.string.ui_saved_to_notebook_32992)) savedFavoriteId = null
    }
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
        val alternatives = if (state.conversationMode == ConversationMode.CHAT) emptyList()
            else before.filter { it.id != current.id && it.photoPaths.isNotEmpty() }.takeLast(5).asReversed()
        if (current.photoPaths.isNotEmpty() || alternatives.isEmpty()) performAction(answer, current, collect)
        else selection = AnswerSelection(answer, current, alternatives, collect)
    }

    val existing = observedId != null
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissEditing = {
        if (!state.submitting && !state.switchingBranch && !state.importingPhotos) {
            focusManager.clearFocus()
            keyboard?.hide()
            vm.cancelEditing()
        }
    }
    BackHandler(enabled = state.editingQuestionId != null && viewer == null && exportContent == null &&
        favorite == null && selection == null && cropQueue.isEmpty(), onBack = dismissEditing)
    PageSwipeSurface(enabled = viewer == null && exportContent == null && favorite == null &&
        selection == null && cropQueue.isEmpty() && state.editingQuestionId == null && !editingTitle,
        onLeft = if (existing) onSwipeNotebook else onSwipeResumeConversation,
        onRight = if (existing) onSwipeNewConversation else onSwipeHistory,
        leftLabel = if (existing) strings.text(R.string.ui_go_to_notebook_d61e9) else strings.text(R.string.ui_previous_conversation_bee47),
        rightLabel = if (existing) strings.text(R.string.ui_start_new_chat_4d374) else strings.text(R.string.ui_go_to_history_b3ca6)) {
        ConversationScaffold(title = state.title, onBack = onBack, pageMotion = pageMotion, ribbonState = ribbonState,
            conversationMode = state.conversationMode.takeIf { existing },
            newConversationMode = state.conversationMode.takeUnless { existing },
            modeSelectable = state.modeSelectable, onSelectMode = vm::selectConversationMode,
            editingTitle = editingTitle, onTitleEditingChange = { editingTitle = it },
            onRenameTitle = vm::renameTitle,
            onDismissEditing = dismissEditing.takeIf { state.editingQuestionId != null }, actions = {
            ConversationActions(
                existingConversation = state.conversationId != null || conversationId != null,
                onNewConversation = onNewConversation,
                onOpenHistory = onOpenHistory,
                onOpenSettings = onOpenSettings,
            )
        }, footer = {
            NoticeLine(state.notice, vm::dismissNotice,
                action = savedFavoriteId?.takeIf { state.notice == strings.text(R.string.ui_saved_to_notebook_32992) }?.let { id ->
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
                onPasteImages = vm::pasteImages, onCancelEditing = dismissEditing,
                switchingPage = pageMotion.switching)
        }) { padding ->
            val initialViewport = remember(observedId) { observedId?.let(readViewport) }
            SolveList(state, vm::retry, vm::regenerate,
                onOpenImages = { paths, index -> viewer = paths to index },
                onShare = { chooseQuestion(it, false) }, onSave = { chooseQuestion(it, true) },
                onResume = vm::resume,
                onEdit = vm::editQuestion, onSwitchVersion = vm::switchVersion,
                readBranchViewport = { key -> observedId?.let { readViewport(it + "__path__" + key) } },
                saveBranchViewport = { key, viewport -> observedId?.let { saveViewport(it + "__path__" + key, viewport) } },
                conversationId = observedId, initialViewport = initialViewport, active = pageMotion.active,
                saveViewport = { viewport -> observedId?.let { saveViewport(it, viewport) } },
                contentPadding = padding)
        }
    }
    cropQueue.firstOrNull()?.let { path ->
        val next = { cropQueue = cropQueue.drop(1); vm.addPhotos(listOf(path)) }
        PhotoCropDialog(path, onCropped = next,
            onDismiss = { cropQueue = cropQueue.drop(1); vm.discardPhoto(path) },
            onCropStarted = { vm.onPhotoCropStarted(path) },
            onCropClosed = { vm.onPhotoCropClosed(path) },
            saveCrop = { vm.savePhotoCrop(path, it) })
    }
    viewer?.let { (paths, index) -> PhotoViewer(paths, index.coerceAtLeast(0), onDismiss = { viewer = null },
        preferProcessed = paths.none { it in state.photos }) }
    exportContent?.let { content -> AnswerExportDialog(content, onDismiss = { exportContent = null }) }
    favorite?.let { target -> FavoriteDialog(target, onDismiss = { favorite = null }, onSaved = { id ->
        savedFavoriteId = id
        favorite = null; vm.showNotice(strings.text(R.string.ui_saved_to_notebook_32992))
    }) }
    selection?.let { pending ->
        AlertDialog(onDismissRequest = { selection = null },
            title = { Text(if (pending.collect) strings.text(R.string.ui_choose_question_to_save_d5dba) else strings.text(R.string.ui_choose_question_to_share_84103)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(strings.text(R.string.ui_this_follow_up_has_no_image_you_can_include_earlier_question_1b9ac))
                    TextButton(onClick = { selection = null; performAction(pending.answer, pending.current, pending.collect) }) {
                        Text(strings.text(R.string.ui_this_question_only_f03e2) + pending.current.text.take(45))
                    }
                    pending.alternatives.forEach { previous ->
                        TextButton(onClick = {
                            selection = null
                            performAction(pending.answer, withFollowUp(previous, pending.current), pending.collect)
                        }) { Text(strings.text(R.string.ui_original_question_and_follow_up_89027) + previous.text.ifBlank { strings.text(R.string.ui_photo_question_770f1) }.take(45)) }
                    }
                }
            }, confirmButton = {}, dismissButton = { TextButton(onClick = { selection = null }) { Text(strings.text(R.string.ui_cancel_4d0b4)) } })
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
    val strings = rememberAppStrings()
    Row {
        if (existingConversation) {
            IconButton(onClick = onNewConversation) { Icon(Icons.Outlined.AddComment, strings.text(R.string.ui_new_chat_1b7ab)) }
        } else {
            IconButton(onClick = onOpenHistory) { Icon(Icons.Outlined.History, strings.text(R.string.ui_history_68387)) }
        }
        IconButton(onClick = onOpenSettings) { Icon(Icons.Outlined.Settings, strings.text(R.string.ui_settings_7debf)) }
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
    onEdit: (String) -> Unit = {},
    onSwitchVersion: (String) -> Unit = {},
    readBranchViewport: (String) -> ConversationViewport? = { null },
    saveBranchViewport: (String, ConversationViewport) -> Unit = { _, _ -> },
    active: Boolean = true,
) {
    val scroll = rememberConversationListScroll(conversationId, state.items, initialViewport,
        contentPadding, active, saveViewport, readBranchViewport, saveBranchViewport)
    val listState = scroll.state
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val nativeView = LocalView.current
    val selectionScrollHost = remember(listState, conversationId, density.density) {
        SelectionScrollHost(scope, density.density) { priority, block -> listState.scroll(priority, block) }
    }
    DisposableEffect(selectionScrollHost) { onDispose { selectionScrollHost.dispose() } }
    if (state.items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            if (state.conversationId == null) {
                NewConversationPlaceholder(state.conversationMode)
            }
        }
        return
    }
    CompositionLocalProvider(LocalSelectionScrollHost provides selectionScrollHost) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().testTag("conversation-list")
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val screen = IntArray(2)
                val window = IntArray(2)
                nativeView.getLocationOnScreen(screen)
                nativeView.getLocationInWindow(window)
                val originX = screen[0] - window[0]
                val originY = screen[1] - window[1]
                // Overlay padding marks the unobscured reading area, rather than
                // the full list which extends behind the title and floating input.
                with(density) {
                    selectionScrollHost.viewport = android.graphics.RectF(
                        bounds.left + originX,
                        bounds.top + originY + contentPadding.calculateTopPadding().toPx(),
                        bounds.right + originX,
                        bounds.bottom + originY - contentPadding.calculateBottomPadding().toPx())
                }
            },
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.items, key = { it.id }) { item ->
                when (item) {
                    is SolveItem.Question -> if (item.isFirst && state.conversationMode == ConversationMode.SOLVE)
                        QuestionCard(item, onOpenImages) else FollowUpNote(item, onOpenImages = onOpenImages,
                        onEdit = onEdit, enabled = !state.submitting && !state.switchingBranch && !state.importingPhotos && state.editingQuestionId == null,
                        onSwitchVersion = { target ->
                            scroll.beforeVersionSwitch(target)
                            onSwitchVersion(target)
                        })
                    is SolveItem.Answer -> AnswerSheet(item, onRetry, onRegenerate,
                        actionsEnabled = !state.generating && !state.busyElsewhere && !state.submitting,
                        onOpenImages = onOpenImages,
                        answerFirst = state.answerFirst && state.conversationMode == ConversationMode.SOLVE,
                        conversationMode = state.conversationMode,
                        onResume = onResume,
                        onShare = { onShare(item) }, onSave = { onSave(item) })
                }
            }
        }
    }
}

@Composable
private fun NoticeLine(notice: String?, onTimeout: () -> Unit, action: (() -> Unit)? = null) {
    val strings = rememberAppStrings()
    if (notice == null) return
    LaunchedEffect(notice) { delay(4_000); onTimeout() }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(notice, style = MaterialTheme.typography.bodyMedium,
            color = if (action != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f).liveRegion())
        if (action != null) TextButton(onClick = action) { Text(strings.text(R.string.ui_view_notebook_aeb82)) }
    }
}
