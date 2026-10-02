package com.moge.app.ui.notebook

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
) {
    val photos = remember(entry.questionImagePaths) { runCatching {
        RequestRepository.decodePathListStrict(entry.questionImagePaths).filter { it.isNotBlank() }
    } }
    val figures = remember(entry.figurePaths) { runCatching { RequestRepository.decodePathListStrict(entry.figurePaths) } }
    var viewer by remember(entry.id) { mutableStateOf<Pair<List<String>, Int>?>(null) }
    val onImage: (List<String>, Int) -> Unit = { paths, index -> viewer = paths to index }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(category, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("题目", style = MaterialTheme.typography.titleMedium)
        if (entry.questionText.isNotBlank()) AnswerMarkdownBody(entry.questionText, emptyList(), onImage)
        if (photos.isFailure) Text("题目照片记录无法读取", color = MaterialTheme.colorScheme.error)
        photos.getOrDefault(emptyList()).let { paths ->
            if (paths.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                paths.forEachIndexed { index, path ->
                    PhotoThumb(path, "题目照片 ${index + 1}", { onImage(paths, index) }, Modifier.size(112.dp))
                }
            }
        }
        if (entry.questionTranscript.isNotBlank() && entry.questionTranscript != entry.questionText) {
            Text("识别文本", style = MaterialTheme.typography.titleSmall)
            AnswerMarkdownBody(entry.questionTranscript, emptyList(), onImage)
        }
        if (figures.isFailure) Text("图表记录无法读取", color = MaterialTheme.colorScheme.error)
        Text("保存的解答", style = MaterialTheme.typography.titleMedium)
        // Render both fields together: they share slots, and unanchored figures appear only once at the end.
        val fullAnswer = remember(entry.finalAnswer, entry.answerText) {
            if (entry.finalAnswer.isBlank()) entry.answerText
            else "## 最终答案\n\n${entry.finalAnswer}\n\n## 完整解答\n\n${entry.answerText}"
        }
        AnswerMarkdownBody(fullAnswer, figures.getOrDefault(emptyList()), onImage)
        if (sourceExists) OutlinedButton(onClick = { onOpenConversation(entry.sourceConversationId) }) { Text("回到原对话") }
        else Text("原对话已删除或暂不可用，保存的题目与解答仍可阅读", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    viewer?.let { (paths, index) -> PhotoViewer(paths, index, { viewer = null }) }
}
