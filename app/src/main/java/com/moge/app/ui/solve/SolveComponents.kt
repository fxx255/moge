package com.moge.app.ui.solve

import androidx.compose.ui.graphics.graphicsLayer


import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import com.moge.app.domain.ConversationMode

import androidx.compose.ui.draw.shadow

import com.moge.app.ui.components.excludePageSwipe
import com.moge.app.ui.components.PaperControlHorizontalTravel
import com.moge.app.ui.components.PaperControlVerticalTravel
import com.moge.app.ui.components.paperControlSwipeFraction
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.moge.app.ui.components.Stamp
import com.moge.app.ui.components.PhotoArrival
import com.moge.app.ui.components.PaperOverlayIconButton
import com.moge.app.ui.components.Tape
import com.moge.app.ui.components.TornPaperNote
import com.moge.app.ui.components.dashedBorder
import com.moge.app.ui.components.marginLine
import com.moge.app.ui.components.paperCard
import com.moge.app.data.parse.replaceFigureAnchors
import com.moge.app.ui.markdown.PreserveMarkdownReadingPosition
import com.moge.app.ui.markdown.AnswerMarkdownBody
import com.moge.app.ui.markdown.InlineFigure
import com.moge.app.ui.markdown.MarkdownAnswer
import com.moge.app.ui.photo.PhotoThumb
import com.moge.app.ui.photo.QuestionPhoto
import com.moge.app.ui.markdown.StreamingMarkdownBody
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.theme.MonoFamily

/** 输入框上方的快捷追问。 */

/** 屏幕阅读器会主动读出内容变化（提示行、生成状态）。 */
internal fun Modifier.liveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** 题目卡：胶带贴住的白纸，照片缩略图 + 用户文字 + 可折叠的识别文本。 */
@Composable
internal fun QuestionCard(item: SolveItem.Question, onOpenImages: (List<String>, Int) -> Unit) {
    val strings = rememberAppStrings()
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .paperCard(MaterialTheme.colorScheme.surface, MogeTheme.paper.cardStroke)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                strings.text(R.string.ui_question_bfd7c),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QuestionPhotos(item.photoPaths, onOpenImages)
            com.moge.app.ui.document.DocumentCards(item.documentPaths)
            if (item.text.isNotBlank()) {
                UserQuestionText(item.text)
            }
            if (item.transcript.isNotBlank()) Transcript(item.transcript)
        }
        Tape(Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun QuestionPhotos(paths: List<String>, onOpenImages: (List<String>, Int) -> Unit) {
    val strings = rememberAppStrings()
    if (paths.isEmpty()) return
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paths.forEachIndexed { index, path ->
            key(path) {
            PhotoArrival(path, surface = "question", Modifier.fillMaxWidth()) {
                QuestionPhoto(path, strings.text(R.string.ui_photo_1_s_a332e, index + 1), onClick = { onOpenImages(paths, index) })
            }
            }
        }
    }
}

/** 识题模型的转写，默认折叠，展开后供用户核对有没有认错。 */
@Composable
private fun Transcript(text: String) {
    val strings = rememberAppStrings()
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(if (MogeTheme.motionEnabled) Modifier.animateContentSize() else Modifier) {
        Text(
            text = if (expanded) strings.text(R.string.ui_hide_recognized_text_112f0) else strings.text(R.string.ui_view_recognized_text_8f759),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) strings.text(R.string.ui_expanded_fa83a) else strings.text(R.string.ui_collapsed_c8f3d) }
                .padding(vertical = 14.dp),
        )
        // 转写是 Markdown + LaTeX：走和解答一样的渲染，公式排版后才好核对有没有认错。
        if (expanded) MarkdownAnswer(text)
    }
}

/** 追问：右对齐的撕边纸条。 */
@Composable
internal fun FollowUpNote(item: SolveItem.Question,
    onEdit: (String) -> Unit = {}, onSwitchVersion: (String) -> Unit = {}, enabled: Boolean = true,
    onOpenImages: (List<String>, Int) -> Unit) {
    val strings = rememberAppStrings()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(horizontalAlignment = Alignment.End) {
        TornPaperNote(
            Modifier.widthIn(max = 300.dp), enabled = enabled,
            onClickLabel = strings.text(R.string.ui_edit_input_695fa), onClick = { onEdit(item.id) },
        ) {
            QuestionPhotos(item.photoPaths, onOpenImages)
            com.moge.app.ui.document.DocumentCards(item.documentPaths)
            UserQuestionText(item.text, color = MogeTheme.paper.onStickyNote,
                showSourceCopy = false,
                onClick = if (enabled) ({ onEdit(item.id) }) else null)
        }
        if (item.versionIds.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("question-versions")) {
                IconButton(onClick = { onSwitchVersion(item.versionIds[item.versionIndex - 1]) },
                    enabled = enabled && item.versionIndex > 0) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, strings.text(R.string.ui_previous_input_version_75728))
                }
                Text("${item.versionIndex + 1} / ${item.versionIds.size}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = { onSwitchVersion(item.versionIds[item.versionIndex + 1]) },
                    enabled = enabled && item.versionIndex < item.versionIds.lastIndex) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, strings.text(R.string.ui_next_input_version_a20f3))
                }
            }
        }
        }
    }
}
/**
 * 解答纸：白纸 + 红色页边线，页眉「解答 · 模型名 · 用时」。
 * 同一正文渲染树跨越生成与完成，结束时保持屏幕中的阅读位置。
 */
@Composable
internal fun AnswerSheet(
    item: SolveItem.Answer,
    onRetry: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    actionsEnabled: Boolean,
    onOpenImages: (List<String>, Int) -> Unit,
    answerFirst: Boolean = false,
    onShare: () -> Unit = {},
    onSave: () -> Unit = {},
    onResume: (String) -> Unit = {},
    conversationMode: ConversationMode = ConversationMode.SOLVE,
) {
    val strings = rememberAppStrings()
    val paper = MogeTheme.paper
    val motion = MogeTheme.motionEnabled
    val generating = item.state == AnswerState.PREPARING || item.state == AnswerState.STREAMING
    val elapsedMs = rememberAnswerElapsedMs(item.startedAtMs.takeIf { generating }, item.durationMs)
    val hasAnswer = item.finalAnswer.isNotBlank() && item.finalAnswer != item.text
    val foldable = answerFirst && hasAnswer
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val showSummary = hasAnswer && item.state in setOf(AnswerState.COMPLETED, AnswerState.STREAMING)
    // The full lecture owns the figures when it is visible; summary references
    // stay readable without inserting the same figures twice.
    val summary = if (item.state == AnswerState.COMPLETED && (!foldable || expanded)) {
        replaceFigureAnchors(item.finalAnswer) { strings.text(R.string.ui_figure_1_s_a25b7, it.numberText.trimStart('0').ifEmpty { "0" }) }
    } else item.finalAnswer
    val lineProgress = remember(item.id) { Animatable(if (generating && motion) 0f else 1f) }
    LaunchedEffect(item.id, motion, generating) {
        if (motion && generating) lineProgress.animateTo(1f, tween(1200)) else lineProgress.snapTo(1f)
    }
    PreserveMarkdownReadingPosition(generating) {
    Column(
        Modifier
            .fillMaxWidth()
            .paperCard(MaterialTheme.colorScheme.surface, paper.cardStroke)
            .marginLine(paper.marginLine, progress = lineProgress.value)
            .padding(start = 40.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AnswerHeader(item, conversationMode, elapsedMs)
        if (generating && item.networkStatus != null) GeneratingHint(strings.text(when (item.networkStatus) {
            com.moge.app.data.llm.NetworkRecoveryStatus.WAITING -> R.string.ui_network_waiting
            com.moge.app.data.llm.NetworkRecoveryStatus.RETRYING -> R.string.ui_network_retrying
            com.moge.app.data.llm.NetworkRecoveryStatus.CONTINUING -> R.string.ui_network_continuing
        }))
        if (item.reasoning.isNotBlank() && generating) {
            ScratchPad(item.id, item.reasoning, thinking = item.text.isEmpty(), elapsedMs = elapsedMs)
        }
        if (showSummary) {
            Text(strings.text(R.string.ui_answer_8784e), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (foldable) StreamingMarkdownBody(summary, streaming = generating, imagePaths = item.figurePaths,
                appendUnreferencedImages = false, onImageClick = onOpenImages)
        }
        if (foldable && item.state in setOf(AnswerState.COMPLETED, AnswerState.STREAMING)) {
            PaperExplanationFold(expanded, onToggle = { expanded = !expanded }) {
                if (item.text.isEmpty()) GeneratingHint(strings.text(R.string.ui_preparing_full_solution_9a111))
                else StreamingMarkdownBody(item.text, streaming = generating, imagePaths = item.figurePaths, onImageClick = onOpenImages)
            }
            if (generating && !expanded) GeneratingHint(strings.text(R.string.ui_preparing_full_solution_9a111))
        } else if ((showSummary || item.text.isNotBlank() || item.figurePaths.isNotEmpty()) && !(generating && answerFirst && !hasAnswer)) {
            // Continuous reply text needs one native selection surface. Separate
            // TextViews trap both handles at the summary/explanation boundary.
            val readingText = if (showSummary) listOf(summary, item.text)
                .filter(String::isNotBlank).joinToString("\n\n") else item.text
            StreamingMarkdownBody(readingText, streaming = generating, imagePaths = item.figurePaths, onImageClick = onOpenImages)
        } else if (generating) {
            GeneratingHint(when {
                answerFirst && !hasAnswer -> strings.text(R.string.ui_generating_answer_1ee41)
                item.state == AnswerState.STREAMING || item.reasoning.isNotBlank() -> strings.text(R.string.ui_thinking_4cc57)
                conversationMode == ConversationMode.CHAT -> strings.text(R.string.ui_preparing_57233)
                else -> strings.text(R.string.ui_reading_question_fa9b9)
            })
        }
        AnswerFooter(item, onRetry, onRegenerate, actionsEnabled, onShare, onSave, onResume)
    }
    }
}

@Composable
private fun AnswerHeader(item: SolveItem.Answer, conversationMode: ConversationMode, elapsedMs: Long) {
    val strings = rememberAppStrings()
    val parts = buildList {
        add(if (conversationMode == ConversationMode.CHAT) strings.text(R.string.ui_reply_065b4) else strings.text(R.string.ui_solution_d5d01))
        if (item.modelLabel.isNotBlank()) add(item.modelLabel)
        if (elapsedMs > 0) add("${elapsedMs / 1000}s")
    }
    Text(
        text = parts.joinToString(" · "),
        modifier = Modifier.testTag("answer-header"),
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = MonoFamily),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 草稿纸：思考过程放在虚线框里，默认折叠成一行「草稿中… 38s」，点开看全文。
 * 计时与标题共用应用任务的起点，正文出现后仍持续更新到保存完成。
 * 推理不落库，只在生成期间可见。
 */
@Composable
private fun ScratchPad(answerId: String, reasoning: String, thinking: Boolean, elapsedMs: Long) {
    val strings = rememberAppStrings()
    var expanded by rememberSaveable(answerId) { mutableStateOf(false) }
    val elapsedSec = elapsedMs / 1000
    val label = if (thinking) strings.text(R.string.ui_drafting_1_ss_352e8, elapsedSec) else strings.text(R.string.ui_draft_1_ss_0ecde, elapsedSec)
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("answer-scratch-pad")
            .background(MogeTheme.paper.scratch, RoundedCornerShape(6.dp))
            .dashedBorder(MaterialTheme.colorScheme.outline)
            .then(if (MogeTheme.motionEnabled) Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)) else Modifier)
            .clickable(role = Role.Button, onClickLabel = if (expanded) strings.text(R.string.ui_hide_draft_6ffaf) else strings.text(R.string.ui_show_draft_a5e97)) {
                expanded = !expanded
            }
            .semantics { stateDescription = if (expanded) strings.text(R.string.ui_expanded_fa83a) else strings.text(R.string.ui_collapsed_c8f3d) }
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontFamily = MonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (expanded) reasoning else reasoning.takeLast(SCRATCH_TAIL_CHARS).replace('\n', ' '),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SCRATCH_TAIL_CHARS = 120

@Composable
private fun GeneratingHint(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.liveRegion(),
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun AnswerFooter(
    item: SolveItem.Answer,
    onRetry: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    actionsEnabled: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onResume: (String) -> Unit,
) {
    val strings = rememberAppStrings()
    when (item.state) {
        AnswerState.COMPLETED -> AnswerActions(item, onRegenerate, actionsEnabled, onShare, onSave)
        AnswerState.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stamp(strings.text(R.string.ui_incomplete_b61b0))
            Text(
                item.failureMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            item.retryRequestId?.let { requestId ->
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item.resumeRequestId?.let { resumeId ->
                        OutlinedButton(onClick = { onResume(resumeId) }, enabled = actionsEnabled,
                            modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(R.string.ui_continue_de5bb)) }
                    }
                    OutlinedButton(onClick = { onRetry(requestId) }, enabled = actionsEnabled,
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(strings.text(R.string.ui_resend_a4e6f)) }
                }
            }
        }
        AnswerState.STOPPED -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                strings.text(R.string.ui_stopped_75ddd),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            item.regenerateRequestId?.let { requestId ->
                RegenerateButton(enabled = actionsEnabled) { onRegenerate(requestId) }
            }
        }
        AnswerState.PREPARING, AnswerState.STREAMING -> Unit
    }
}

/** 页脚累计用量占满宽度，操作按钮独立排列，兼容较长统计和大字体。 */
@Composable
private fun AnswerActions(item: SolveItem.Answer, onRegenerate: (String) -> Unit, actionsEnabled: Boolean, onShare: () -> Unit, onSave: () -> Unit) {
    val strings = rememberAppStrings()
    Column(Modifier.fillMaxWidth()) {
        usageLabel(item.usageJson, strings)?.let { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            item.regenerateRequestId?.let { requestId ->
                RegenerateButton(enabled = actionsEnabled) { onRegenerate(requestId) }
            }
            if (item.text.isNotBlank()) {
                IconButton(onClick = onSave) {
                    Icon(Icons.Outlined.BookmarkBorder, contentDescription = strings.text(R.string.ui_save_to_notebook_fd727))
                }
                IconButton(onClick = onShare) {
                    Icon(Icons.Outlined.Share, contentDescription = strings.text(R.string.ui_share_conversation_image_e5c0f))
                }
            }
        }
    }
}

@Composable
private fun RegenerateButton(enabled: Boolean, onClick: () -> Unit) {
    val strings = rememberAppStrings()
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Outlined.Refresh, contentDescription = strings.text(R.string.ui_regenerate_2e190))
    }
}

/** 文字、附件与发送；生成中发送键变成停止键。 */
@Composable
internal fun FollowUpBar(
    state: SolveUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickPhotos: () -> Unit,
    onOpenPendingPhoto: (Int) -> Unit,
    onRemovePendingPhoto: (String) -> Unit,
    onPickDocument: () -> Unit = {},
    onRemoveDocument: (String) -> Unit = {},
    onPasteImages: (List<android.net.Uri>) -> Unit = {},
    onCancelEditing: () -> Unit = {},
    switchingPage: Boolean = false,
    swipeDirection: Int = 0,
    swipeProgress: (() -> Float)? = null,
    scrollSway: () -> Float = { 0f },
) {
    val strings = rememberAppStrings()
    val motionEnabled = MogeTheme.motionEnabled
    val elevation by androidx.compose.animation.core.animateDpAsState(
        if (switchingPage && swipeProgress == null && motionEnabled) 14.dp else 8.dp,
        tween(if (MogeTheme.motionEnabled) 160 else 0), label = "composer-page-shadow")
    val drift by androidx.compose.animation.core.animateDpAsState(
        if (switchingPage && swipeProgress == null && motionEnabled) PaperControlHorizontalTravel * swipeDirection.coerceIn(-1, 1) else 0.dp,
        tween(if (MogeTheme.motionEnabled) 160 else 0), label = "composer-page-drift")
    Column(Modifier.fillMaxWidth().excludePageSwipe().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (state.editingQuestionId != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PaperOverlayIconButton(onClick = onCancelEditing, enabled = !state.submitting && !state.switchingBranch && !state.importingPhotos) {
                    Icon(Icons.Outlined.Close, strings.text(R.string.ui_cancel_edit_11eec))
                }
            }
        }
        if (state.photos.isNotEmpty()) PendingPhotos(state.photos, onOpenPendingPhoto, onRemovePendingPhoto)
        com.moge.app.ui.document.PendingDocumentChips(state.documentPaths, onRemoveDocument)
        if (state.importingPhotos) {
            Text(strings.text(R.string.ui_adding_attachments_c3255), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, bottom = 4.dp).liveRegion())
        }
        Column(
            Modifier.fillMaxWidth().testTag("composer-surface")
                .graphicsLayer {
                    val progress = swipeProgress?.invoke()
                    val depth = if (progress != null && motionEnabled) {
                        paperControlSwipeFraction(progress)
                    } else null
                    translationX = if (depth != null) PaperControlHorizontalTravel.toPx() * swipeDirection * depth else drift.toPx()
                    translationY = if (motionEnabled) PaperControlVerticalTravel.toPx() * scrollSway() else 0f
                    shadowElevation = if (depth != null) (8.dp.toPx() + 6.dp.toPx() * depth) else elevation.toPx()
                    shape = RoundedCornerShape(32.dp)
                }
                .paperCard(MaterialTheme.colorScheme.surface, MogeTheme.paper.cardStroke, radius = 32.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ComposerTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f).testTag("conversation-input"),
                    placeholder = if (state.editingQuestionId != null) strings.text(R.string.ui_edit_this_input_ea8a7)
                        else if (state.conversationMode == ConversationMode.CHAT) strings.text(R.string.ui_message_94306)
                        else if (state.conversationId == null) strings.text(R.string.ui_enter_a_question_86b9d) else strings.text(R.string.ui_continue_chatting_88af8),
                    enabled = !state.switchingBranch,
                    editFocusKey = state.editingQuestionId,
                    onPasteImages = onPasteImages,
                )
                ComposerAttachmentMenu(
                    enabled = !state.generating && !state.submitting && !state.switchingBranch,
                    photosEnabled = state.canAddPhoto,
                    onTakePhoto = onTakePhoto,
                    onPickPhotos = onPickPhotos,
                    onPickDocument = onPickDocument,
                )
                if (state.generating) {
                    FilledIconButton(onClick = onStop,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) { Icon(Icons.Outlined.Stop, contentDescription = strings.text(R.string.ui_stop_generation_76349)) }
                } else {
                    FilledIconButton(onClick = onSend, enabled = state.canSend) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.text(R.string.ui_send_1214d))
                    }
                }
            }
        }
        if (state.busyElsewhere) {
            Text(strings.text(R.string.ui_another_version_or_conversation_is_generating_wait_before_se_40a37), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp).liveRegion())
        }
    }
}

/** 待随下一问发出的照片：输入框上方一行小缩略图，每张可点开查看、可删。 */
@Composable
private fun PendingPhotos(paths: List<String>, onOpen: (Int) -> Unit, onRemove: (String) -> Unit) {
    val strings = rememberAppStrings()
    Row(
        Modifier.fillMaxWidth().excludePageSwipe().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paths.forEachIndexed { index, path ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PhotoThumb(path, strings.text(R.string.ui_photo_1_s_to_send_b8dbb, index + 1), onClick = { onOpen(index) }, modifier = Modifier.size(64.dp))
                PaperOverlayIconButton(
                    onClick = { onRemove(path) },
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = strings.text(R.string.ui_remove_photo_1_s_32a36, index + 1))
                }
            }
        }
    }
}
