package com.moge.app.ui.solve

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.moge.app.data.db.NotebookEntryEntity
import com.moge.app.data.db.NotebookRepository
import com.moge.app.data.db.RequestRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class FavoriteTarget(
    val conversationId: String,
    val title: String,
    val question: SolveItem.Question,
    val answer: SolveItem.Answer,
)

@HiltViewModel
class FavoriteViewModel @Inject constructor(private val notebook: NotebookRepository) : ViewModel() {
    val categories = notebook.observeCategories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val existing = MutableStateFlow<NotebookEntryEntity?>(null)
    val busy = MutableStateFlow(false)
    val loading = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private var selectedAnswerId = ""

    fun open(answerId: String) {
        selectedAnswerId = answerId
        loading.value = true
        existing.value = null
        error.value = null
        viewModelScope.launch {
            try {
                val entry = notebook.favorite(answerId)
                if (selectedAnswerId == answerId) existing.value = entry
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (selectedAnswerId == answerId) error.value = e.message ?: "收藏读取失败" }
            finally { if (selectedAnswerId == answerId) loading.value = false }
        }
    }

    internal fun save(target: FavoriteTarget, categoryId: String?, newCategory: String, onSaved: () -> Unit) {
        if (busy.value || loading.value) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                val category = if (newCategory.isNotBlank()) notebook.createCategory(newCategory).id else categoryId
                notebook.save(NotebookEntryEntity(
                    categoryId = category,
                    sourceConversationId = target.conversationId,
                    sourceQuestionId = target.question.id,
                    sourceAnswerId = target.answer.id,
                    title = target.title.ifBlank { target.question.text.take(60).ifBlank { "收藏题目" } },
                    questionText = target.question.text,
                    questionTranscript = target.question.transcript,
                    questionImagePaths = RequestRepository.encodePathList(target.question.photoPaths),
                    answerText = target.answer.text,
                    finalAnswer = target.answer.finalAnswer,
                    figurePaths = RequestRepository.encodePathList(target.answer.figurePaths),
                ))
                onSaved()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error.value = e.message ?: "收藏失败，请重试" }
            finally { busy.value = false }
        }
    }
}

@Composable
internal fun FavoriteDialog(target: FavoriteTarget, onDismiss: () -> Unit, onSaved: () -> Unit, vm: FavoriteViewModel = hiltViewModel()) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val existing by vm.existing.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var categoryId by rememberSaveable(target.answer.id) { mutableStateOf<String?>(null) }
    var newCategory by rememberSaveable(target.answer.id) { mutableStateOf("") }
    var categoryEdited by rememberSaveable(target.answer.id) { mutableStateOf(false) }
    LaunchedEffect(target.answer.id) { vm.open(target.answer.id) }
    LaunchedEffect(existing?.id) { if (!categoryEdited) categoryId = existing?.categoryId }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (existing == null) "收藏到题册" else "更新收藏") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(target.question.text.ifBlank { "图片题目" }.take(100))
                if (existing != null) Text("保存将更新这条收藏的题目与解答。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { categoryId = null; categoryEdited = true; newCategory = "" }, enabled = !busy) {
                    Text((if (categoryId == null && newCategory.isBlank()) "✓ " else "") + "未分类")
                }
                categories.forEach { category ->
                    TextButton(onClick = { categoryId = category.id; categoryEdited = true; newCategory = "" }, enabled = !busy) {
                        Text((if (categoryId == category.id && newCategory.isBlank()) "✓ " else "") + category.name)
                    }
                }
                OutlinedTextField(newCategory, { newCategory = it; categoryEdited = true }, label = { Text("或新建分类") },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.save(target, categoryId, newCategory, onSaved) }, enabled = !busy && !loading) {
            Text(if (loading) "读取收藏…" else if (busy) "保存中…" else "保存收藏")
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}
