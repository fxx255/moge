package com.moge.app.ui.solve

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
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
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import com.moge.app.ui.components.Tape
import com.moge.app.ui.components.dashedBorder
import com.moge.app.ui.components.marginLine
import com.moge.app.ui.components.paperCard
import com.moge.app.data.parse.replaceFigureAnchors
import com.moge.app.ui.markdown.AnswerMarkdownBody
import com.moge.app.ui.markdown.InlineFigure
import com.moge.app.ui.markdown.MarkdownAnswer
import com.moge.app.ui.photo.PhotoThumb
import com.moge.app.ui.markdown.StreamingMarkdownBody
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.theme.MonoFamily
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** 输入框上方的快捷追问。 */

/** 屏幕阅读器会主动读出内容变化（提示行、生成状态）。 */
internal fun Modifier.liveRegion(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

/** 题目卡：胶带贴住的白纸，照片缩略图 + 用户文字 + 可折叠的识别文本。 */
@Composable
internal fun QuestionCard(item: SolveItem.Question, onOpenImages: (List<String>, Int) -> Unit) {
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
                "题目",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QuestionPhotos(item.photoPaths, onOpenImages)
            if (item.text.isNotBlank()) {
                Text(item.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            if (item.transcript.isNotBlank()) Transcript(item.transcript)
        }
        Tape(Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun QuestionPhotos(paths: List<String>, onOpenImages: (List<String>, Int) -> Unit) {
    if (paths.isEmpty()) return
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paths.forEachIndexed { index, path ->
            key(path) {
            PhotoArrival(path, surface = "question", Modifier.size(width = 160.dp, height = 120.dp)) {
                PhotoThumb(path, "题目照片 ${index + 1}", onClick = { onOpenImages(paths, index) }, modifier = Modifier.matchParentSize())
            }
            }
        }
    }
}

/** 识题模型的转写，默认折叠，展开后供用户核对有没有认错。 */
@Composable
private fun Transcript(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(if (MogeTheme.motionEnabled) Modifier.animateContentSize() else Modifier) {
        Text(
            text = if (expanded) "收起识别文本" else "查看识别文本",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                .padding(vertical = 14.dp),
        )
        // 转写是 Markdown + LaTeX：走和解答一样的渲染，公式排版后才好核对有没有认错。
        if (expanded) MarkdownAnswer(text)
    }
}

/** 追问：右对齐的黄色便利贴。 */
@Composable
internal fun FollowUpNote(item: SolveItem.Question, onOpenImages: (List<String>, Int) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(MogeTheme.paper.stickyNote, RoundedCornerShape(4.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuestionPhotos(item.photoPaths, onOpenImages)
            Text(item.text, style = MaterialTheme.typography.bodyLarge, color = MogeTheme.paper.onStickyNote)
        }
    }
}
/**
 * 解答纸：白纸 + 红色页边线，页眉「解答 · 模型名 · 用时」。
 * 生成中走流式渲染（只把已闭合的块交给 Markwon），完成后整篇渲染并内嵌图表。
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
) {
    val paper = MogeTheme.paper
    val motion = MogeTheme.motionEnabled
    val generating = item.state == AnswerState.PREPARING || item.state == AnswerState.STREAMING
    val hasAnswer = item.finalAnswer.isNotBlank() && item.finalAnswer != item.text
    val foldable = answerFirst && hasAnswer
    var expanded by rememberSaveable(item.id, item.finalAnswer) { mutableStateOf(false) }
    val lineProgress = remember(item.id) { Animatable(if (generating && motion) 0f else 1f) }
    LaunchedEffect(item.id, motion, generating) {
        if (motion && generating) lineProgress.animateTo(1f, tween(1200)) else lineProgress.snapTo(1f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .paperCard(MaterialTheme.colorScheme.surface, paper.cardStroke)
            .marginLine(paper.marginLine, progress = lineProgress.value)
            .padding(start = 40.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AnswerHeader(item)
        if (item.reasoning.isNotBlank() && item.state == AnswerState.STREAMING) {
            ScratchPad(item.id, item.reasoning, thinking = item.text.isEmpty())
        }
        if (hasAnswer && item.state in setOf(AnswerState.COMPLETED, AnswerState.STREAMING)) {
            Text("答案", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            // The full lecture already includes all figure slots. Keep summary references
            // readable without showing each image twice; a folded answer retains its images.
            val summary = if (item.state == AnswerState.COMPLETED && (!foldable || expanded)) {
                replaceFigureAnchors(item.finalAnswer) { "图 ${it.numberText.trimStart('0').ifEmpty { "0" }}" }
            } else item.finalAnswer
            AnswerMarkdownBody(summary, item.figurePaths, onOpenImages, appendUnreferencedImages = false)
        }
        if (foldable && item.state in setOf(AnswerState.COMPLETED, AnswerState.STREAMING)) {
            PaperExplanationFold(expanded, onToggle = { expanded = !expanded }) {
                if (item.state == AnswerState.COMPLETED) {
                    AnswerMarkdownBody(item.text, item.figurePaths, onOpenImages)
                } else if (item.text.isEmpty()) GeneratingHint("正在整理完整解答…")
                else StreamingMarkdownBody(item.text)
            }
            if (generating && !expanded) GeneratingHint("正在整理完整解答…")
        } else when (item.state) {
            AnswerState.COMPLETED -> AnswerMarkdownBody(item.text, item.figurePaths, onOpenImages)
            AnswerState.PREPARING -> GeneratingHint("正在读题…")
            AnswerState.STREAMING -> when {
                answerFirst && !hasAnswer -> GeneratingHint("正在生成答案…")
                item.text.isEmpty() -> GeneratingHint("正在思考…")
                else -> StreamingMarkdownBody(item.text)
            }
            AnswerState.FAILED, AnswerState.STOPPED ->
                if (item.text.isNotBlank()) StreamingMarkdownBody(item.text)
        }
        AnswerFooter(item, onRetry, onRegenerate, actionsEnabled, onShare, onSave)
    }
}

@Composable
private fun AnswerHeader(item: SolveItem.Answer) {
    val parts = buildList {
        add("解答")
        if (item.modelLabel.isNotBlank()) add(item.modelLabel)
        if (item.durationMs > 0) add("${(item.durationMs + 500) / 1000}s")
    }
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.labelLarge.copy(fontFamily = MonoFamily),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 草稿纸：思考过程放在虚线框里，默认折叠成一行「草稿中… 38s」，点开看全文。
 * 计时从这张解答纸第一次出现推理开始；正文出来后停表，显示「草稿 · 38s」。
 * 推理不落库，只在生成期间可见。
 */
@Composable
private fun ScratchPad(answerId: String, reasoning: String, thinking: Boolean) {
    var expanded by rememberSaveable(answerId) { mutableStateOf(false) }
    val startedAt = rememberSaveable(answerId) { SystemClock.elapsedRealtime() }
    var elapsedSec by remember(answerId) { mutableLongStateOf((SystemClock.elapsedRealtime() - startedAt) / 1000) }
    LaunchedEffect(answerId, thinking) {
        while (thinking) {
            elapsedSec = (SystemClock.elapsedRealtime() - startedAt) / 1000
            delay(1_000)
        }
    }
    val label = if (thinking) "草稿中… ${elapsedSec}s" else "草稿 · ${elapsedSec}s"
    Column(
        Modifier
            .fillMaxWidth()
            .background(MogeTheme.paper.scratch, RoundedCornerShape(6.dp))
            .dashedBorder(MaterialTheme.colorScheme.outline)
            .then(if (MogeTheme.motionEnabled) Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)) else Modifier)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "收起草稿" else "展开草稿") {
                expanded = !expanded
            }
            .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
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
private fun AnswerFooter(
    item: SolveItem.Answer,
    onRetry: (String) -> Unit,
    onRegenerate: (String) -> Unit,
    actionsEnabled: Boolean,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    when (item.state) {
        AnswerState.COMPLETED -> AnswerActions(item, onRegenerate, actionsEnabled, onShare, onSave)
        AnswerState.FAILED -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stamp("未完成")
            Text(
                item.failureMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            item.retryRequestId?.let { requestId ->
                OutlinedButton(
                    onClick = { onRetry(requestId) },
                    enabled = actionsEnabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("重新发送")
                }
            }
        }
        AnswerState.STOPPED -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "已停止",
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

/** 页脚操作行：用量 · 复制 · 重新生成 · 分享。 */
@Composable
private fun AnswerActions(item: SolveItem.Answer, onRegenerate: (String) -> Unit, actionsEnabled: Boolean, onShare: () -> Unit, onSave: () -> Unit) {
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = usageLabel(item.usageJson).orEmpty(),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (item.text.isNotBlank()) {
            IconButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val copied = if (item.finalAnswer.isBlank() || item.text.contains(item.finalAnswer)) item.text
                    else "${item.finalAnswer}\n\n${item.text}"
                clipboard.setPrimaryClip(ClipData.newPlainText("解答", copied))
                // Android 13 起系统自己会弹复制提示，再弹一次就重复了。
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制解答")
            }
        }
        item.regenerateRequestId?.let { requestId ->
            RegenerateButton(enabled = actionsEnabled) { onRegenerate(requestId) }
        }
        if (item.text.isNotBlank()) {
            IconButton(onClick = onSave) {
                Icon(Icons.Outlined.BookmarkBorder, contentDescription = "收藏到题册")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Outlined.Share, contentDescription = "分享题目与解答图片")
            }
        }
    }
}

@Composable
private fun RegenerateButton(enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(Icons.Outlined.Refresh, contentDescription = "重新生成")
    }
}

/**
 * 页脚的用量文案：「输入 1.2k · 缓存 900 · 输出 860」。
 * 供应商没给的字段不显示（不冒充 0）；全都没有返回 null。
 */
internal fun usageLabel(usageJson: String): String? {
    if (usageJson.isBlank()) return null
    val obj = runCatching { Json.parseToJsonElement(usageJson).jsonObject }.getOrNull() ?: return null
    fun field(name: String) = (obj[name] as? JsonPrimitive)?.longOrNull
    val parts = buildList {
        field("inputTokens")?.let { add("输入 ${compactCount(it)}") }
        field("cachedInputTokens")?.takeIf { it > 0 }?.let { add("缓存 ${compactCount(it)}") }
        field("outputTokens")?.let { add("输出 ${compactCount(it)}") }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

internal fun compactCount(value: Long): String = when {
    value < 1_000 -> value.toString()
    value < 100_000 -> String.format(Locale.ROOT, "%.1fk", value / 1_000.0).replace(".0k", "k")
    else -> "${value / 1_000}k"
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
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (state.photos.isNotEmpty()) PendingPhotos(state.photos, onOpenPendingPhoto, onRemovePendingPhoto)
        Row(
            Modifier.fillMaxWidth()
                .paperCard(MaterialTheme.colorScheme.surface, MogeTheme.paper.cardStroke, radius = 28.dp)
                .padding(start = 4.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = state.input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (state.conversationId == null) "输入问题" else "继续对话") },
                maxLines = 5,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (!state.generating) {
                IconButton(onClick = onTakePhoto, enabled = state.canAddPhoto) {
                    Icon(Icons.Outlined.PhotoCamera, contentDescription = "拍照")
                }
                IconButton(onClick = onPickPhotos, enabled = state.canAddPhoto) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = "从相册选图")
                }
            }
            if (state.generating) {
                FilledIconButton(onClick = onStop,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    ),
                ) { Icon(Icons.Outlined.Stop, contentDescription = "停止生成") }
            } else {
                FilledIconButton(onClick = onSend, enabled = state.canSend) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
        if (state.busyElsewhere) {
            Text("另一段对话正在生成，结束后才能发送", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp).liveRegion())
        }
    }
}

/** 待随下一问发出的照片：输入胶囊上方一行小缩略图，每张可点开查看、可删。 */
@Composable
private fun PendingPhotos(paths: List<String>, onOpen: (Int) -> Unit, onRemove: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paths.forEachIndexed { index, path ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PhotoThumb(path, "待发的第 ${index + 1} 张照片", onClick = { onOpen(index) }, modifier = Modifier.size(64.dp))
                IconButton(
                    onClick = { onRemove(path) },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = "删掉第 ${index + 1} 张照片", Modifier.size(16.dp))
                }
            }
        }
    }
}
