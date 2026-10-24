package com.moge.app.ui.notebook

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import com.moge.app.ui.components.excludePageSwipe
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moge.app.data.db.NotebookEntryEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.ui.markdown.AnswerMarkdownBody
import com.moge.app.ui.photo.PhotoThumb
import com.moge.app.ui.viewer.PhotoViewer

/** 只读取收藏快照；源历史消失后题目、识别文本、公式与图槽仍可完整阅读。 */
@Composable
internal fun NotebookEntryDetail(
    entry: NotebookEntryEntity, sourceExists: Boolean, category: String,
    onOpenConversation: (String) -> Unit, modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val strings = rememberAppStrings()
    val photos = remember(entry.questionImagePaths) { runCatching {
        RequestRepository.decodePathListStrict(entry.questionImagePaths).filter { it.isNotBlank() }
    } }
    val figures = remember(entry.figurePaths) { runCatching { RequestRepository.decodePathListStrict(entry.figurePaths) } }
    var viewer by remember(entry.id) { mutableStateOf<Pair<List<String>, Int>?>(null) }
    val onImage: (List<String>, Int) -> Unit = { paths, index -> viewer = paths to index }
    Column(modifier.verticalScroll(rememberScrollState()).padding(contentPadding).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(strings.text(R.string.ui_question_bfd7c), style = MaterialTheme.typography.titleMedium)
        com.moge.app.ui.document.DocumentCards(RequestRepository.decodePathList(entry.documentPaths))
        if (entry.questionText.isNotBlank()) AnswerMarkdownBody(entry.questionText, emptyList(), onImage)
        if (photos.isFailure) Text(strings.text(R.string.ui_could_not_read_saved_question_photos_c47fe), color = MaterialTheme.colorScheme.error)
        photos.getOrDefault(emptyList()).let { paths ->
            if (paths.isNotEmpty()) Row(Modifier.fillMaxWidth().excludePageSwipe().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                paths.forEachIndexed { index, path ->
                    PhotoThumb(path, strings.text(R.string.ui_question_photo_1_s_22570, index + 1), { onImage(paths, index) }, Modifier.size(112.dp), preferProcessed = true)
                }
            }
        }
        if (entry.questionTranscript.isNotBlank() && entry.questionTranscript != entry.questionText) {
            Text(strings.text(R.string.ui_recognized_text_e601e), style = MaterialTheme.typography.titleSmall)
            AnswerMarkdownBody(entry.questionTranscript, emptyList(), onImage)
        }
        if (figures.isFailure) Text(strings.text(R.string.ui_could_not_read_saved_charts_fb94a), color = MaterialTheme.colorScheme.error)
        Text(strings.text(R.string.ui_saved_solution_3d6d8), style = MaterialTheme.typography.titleMedium)
        // Render both fields together: they share slots, and unanchored figures appear only once at the end.
        val fullAnswer = remember(entry.finalAnswer, entry.answerText) {
            if (entry.finalAnswer.isBlank()) entry.answerText
            else strings.text(R.string.ui_final_answer_n_n_1_s_n_n_full_solution_n_n_2_s_4d0d6, entry.finalAnswer, entry.answerText)
        }
        AnswerMarkdownBody(fullAnswer, figures.getOrDefault(emptyList()), onImage)
        if (sourceExists) OutlinedButton(onClick = { onOpenConversation(entry.sourceConversationId) }) { Text(strings.text(R.string.ui_return_to_original_conversation_6b2f9)) }
        else Text(strings.text(R.string.ui_the_original_conversation_is_unavailable_you_can_still_read_e1b58), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    viewer?.let { (paths, index) -> PhotoViewer(paths, index, { viewer = null }) }
}
