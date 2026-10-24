package com.moge.app.ui.export


import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

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
    val strings = rememberAppStrings()
    val context = LocalContext.current
    val resolver = LocalFigurePathResolver.current
    val bodyTypeface = com.moge.app.ui.theme.LocalAppTypeface.current
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
        busy = true; message = null; progress = strings.text(R.string.ui_preparing_pages_3a5cb); page = 0
        session.job = scope.launch {
            try {
                session.result?.files?.close()
                session.result = null; result = null
                val next = renderAnswerExport(context, content, choice, resolver, bodyTypeface) { progress = it }
                session.result = next
                result = next
                progress = strings.text(R.string.ui_generated_1_s_consecutive_images_8696d, next.pages.size)
            } catch (_: CancellationException) {
                if (session.active) message = strings.text(R.string.ui_cancelled_temporary_images_removed_a282c)
            } catch (_: Throwable) {
                if (session.active) message = strings.text(R.string.ui_export_failed_check_storage_and_attachments_then_retry_2bb8d)
            } finally { if (session.active) busy = false }
        }
    }

    fun save() {
        val ready = result ?: return
        if (busy) return
        busy = true; message = null; progress = strings.text(R.string.ui_saving_images_9fd19)
        session.job = scope.launch {
            try {
                val album = saveImagesToGallery(context, ready.pages.map { it.absolutePath }) { saved, total ->
                    progress = strings.text(R.string.ui_saving_image_1_s_2_s_3d978, saved, total)
                }
                message = strings.text(R.string.ui_saved_all_1_s_images_to_2_s_d3eef, ready.pages.size, album)
            } catch (_: CancellationException) {
                if (session.active) message = strings.text(R.string.ui_cancelled_images_from_this_save_were_removed_a9e37)
            } catch (_: Throwable) {
                if (session.active) message = strings.text(R.string.ui_save_failed_and_was_rolled_back_try_again_e1e00)
            } finally { if (session.active) busy = false }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && session.active) save()
        else if (session.active) message = strings.text(R.string.ui_storage_permission_is_needed_to_save_you_can_still_share_7c76a)
    }

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        GridPaper(Modifier.fillMaxSize().testTag("answer-export-dialog")) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HighlightedTitle(strings.text(R.string.ui_share_images_2a61d), modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, if (busy) strings.text(R.string.ui_cancel_close_2475f) else strings.text(R.string.ui_close_sharing_59808)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ExportChoiceCard(strings.text(R.string.ui_question_full_solution_0edff), selected = choice == ExportChoice.FULL, enabled = !busy,
                        onClick = { choice = ExportChoice.FULL; confirmed = false }, modifier = Modifier.weight(1f))
                    ExportChoiceCard(strings.text(R.string.ui_question_answer_4b1b0), selected = choice == ExportChoice.ANSWER_ONLY,
                        enabled = !busy && content.finalAnswer.isNotBlank(),
                        onClick = { choice = ExportChoice.ANSWER_ONLY; confirmed = false }, modifier = Modifier.weight(1f))
                }
                if (content.finalAnswer.isBlank()) Text(strings.text(R.string.ui_no_separate_answer_is_available_export_the_full_solution_67b7a), style = MaterialTheme.typography.bodySmall)
                if (!confirmed) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .paperCard(MaterialTheme.colorScheme.surface, MogeTheme.paper.cardStroke)
                        .marginLine(MogeTheme.paper.marginLine, inset = 16.dp)
                        .padding(start = 28.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(strings.text(R.string.ui_check_question_ac111), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(strings.text(R.string.ui_check_the_question_and_photos_before_creating_pages_to_share_ce046), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(content.title.ifBlank { strings.text(R.string.ui_question_solution_94121) }, style = MaterialTheme.typography.titleMedium)
                        if (content.questionText.isNotBlank()) Text(content.questionText)
                        // Display one selected photo at a time so proofing many attachments
                        // never retains one decoded bitmap per question photo.
                        if (content.questionPhotos.isNotEmpty()) QuestionPhotos(content.questionPhotos)
                        if (content.questionTranscript.isNotBlank()) {
                            Text(strings.text(R.string.ui_recognized_text_available_145e8), style = MaterialTheme.typography.titleSmall)
                            Text(content.questionTranscript)
                        }
                        Text(strings.text(R.string.ui_export_on_light_paper_as_one_long_image_or_numbered_pages_fo_097fd), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { confirmed = true; render() }, enabled = !busy,
                        modifier = Modifier.fillMaxWidth().testTag("export-confirm-question")) { Text(strings.text(R.string.ui_confirm_preview_3c275)) }
                } else {
                    val ready = result
                    if (ready != null) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { page-- }, enabled = !busy && page > 0) { Text(strings.text(R.string.ui_previous_image_b9f73)) }
                            Text(strings.text(R.string.ui_1_s_2_s_read_in_page_order_134f7, page + 1, ready.pages.size), modifier = Modifier.weight(1f))
                            TextButton(onClick = { page++ }, enabled = !busy && page < ready.pages.lastIndex) { Text(strings.text(R.string.ui_next_image_9a3dc)) }
                        }
                        key(ready, page) {
                            ready.pages.getOrNull(page)?.let { selected ->
                                ExportLongImagePreview(selected.absolutePath, strings.text(R.string.ui_export_preview_image_1_s_e4532, page + 1),
                                    Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                        Text(strings.text(R.string.ui_double_tap_to_zoom_drag_to_explore_19c6c), style = MaterialTheme.typography.bodySmall)
                        if (ready.warnings.isNotEmpty()) {
                            Text(strings.text(R.string.ui_marked_in_preview_f8135) + ready.warnings.take(3).joinToString("；") +
                                if (ready.warnings.size > 3) strings.text(R.string.ui_1_s_more_check_every_page_4fb08, ready.warnings.size - 3) else "",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    } else Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(if (busy) strings.text(R.string.ui_laying_out_pages_14c21) else strings.text(R.string.ui_images_not_generated_try_again_693df))
                    }
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text(progress, modifier = Modifier.weight(1f))
                            TextButton(onClick = { session.job?.cancel() }) { Text(strings.text(R.string.ui_cancel_4d0b4)) }
                        }
                    } else if (ready == null) Button(onClick = ::render, modifier = Modifier.fillMaxWidth()) { Text(strings.text(R.string.ui_retry_export_a8289)) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = !busy && ready != null, modifier = Modifier.weight(1f), onClick = {
                            if (galleryNeedsLegacyPermission() && ContextCompat.checkSelfPermission(context,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                                permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else save()
                        }) { Text(strings.text(R.string.ui_save_all_images_d22e2)) }
                        Button(enabled = !busy && ready != null, modifier = Modifier.weight(1f), onClick = {
                            try {
                                val exported = checkNotNull(ready)
                                shareImages(context, exported.pages.map { it.absolutePath })
                                exported.files.retainForSharing()
                                message = strings.text(R.string.ui_opened_sharing_for_1_s_images_b6fe7, exported.pages.size)
                            } catch (_: Exception) { message = strings.text(R.string.ui_could_not_open_sharing_try_again_or_save_images_first_1f340) }
                        }) { Text(strings.text(R.string.ui_share_via_system_7bbac)) }
                    }
                    TextButton(enabled = !busy, onClick = { confirmed = false }) { Text(strings.text(R.string.ui_review_question_export_scope_bfbee)) }
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
    val strings = rememberAppStrings()
    if (paths.isEmpty()) return
    var index by remember(paths) { mutableIntStateOf(0) }
    val current = index.coerceIn(paths.indices)
    Text(strings.text(R.string.ui_question_photo_1_s_2_s_9fff7, current + 1, paths.size), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary)
    Box(Modifier.fillMaxWidth()) {
        ExportPreviewImage(paths[current], strings.text(R.string.ui_question_photo_1_s_to_confirm_df025, current + 1))
        Tape(Modifier.align(Alignment.TopEnd).padding(end = 12.dp), width = 42.dp, height = 14.dp)
    }
    if (paths.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(enabled = current > 0, onClick = { index = (current - 1).coerceAtLeast(0) }) { Text(strings.text(R.string.ui_previous_photo_c1669)) }
        TextButton(enabled = current < paths.lastIndex, onClick = { index = (current + 1).coerceAtMost(paths.lastIndex) }) { Text(strings.text(R.string.ui_next_photo_6d008)) }
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
    val strings = rememberAppStrings()
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
                PreviewImage.Loading -> Text(strings.text(R.string.ui_loading_image_74b7a), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                PreviewImage.Missing -> Text(strings.text(R.string.ui_could_not_read_image_check_the_original_question_or_regenera_75ee8), Modifier.padding(12.dp),
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
