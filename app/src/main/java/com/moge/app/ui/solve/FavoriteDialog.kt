package com.moge.app.ui.solve

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

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
import kotlinx.coroutines.Job
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
    private var openRevision = 0L
    private var openJob: Job? = null
    private var saveJob: Job? = null

    fun open(answerId: String) {
        val revision = ++openRevision
        selectedAnswerId = answerId
        openJob?.cancel()
        saveJob?.cancel()
        busy.value = false
        loading.value = true
        existing.value = null
        error.value = null
        openJob = viewModelScope.launch {
            try {
                val entry = notebook.favorite(answerId)
                if (openRevision == revision) existing.value = entry
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (openRevision == revision) error.value = e.message ?: "收藏读取失败" }
            finally { if (openRevision == revision) loading.value = false }
        }
    }

    internal fun save(target: FavoriteTarget, categoryId: String?, newCategory: String, onSaved: (String) -> Unit) {
        if (busy.value || loading.value || target.answer.id != selectedAnswerId) return
        val revision = openRevision
        busy.value = true
        error.value = null
        saveJob = viewModelScope.launch {
            try {
                val category = if (newCategory.isNotBlank()) notebook.createCategory(newCategory).id else categoryId
                val savedId = notebook.save(NotebookEntryEntity(
                    categoryId = category,
                    sourceConversationId = target.conversationId,
                    sourceQuestionId = target.question.id,
                    sourceAnswerId = target.answer.id,
                    title = target.title.ifBlank { target.question.text.take(60).ifBlank { "收藏题目" } },
                    questionText = target.question.text,
                    questionTranscript = target.question.transcript,
                    questionImagePaths = RequestRepository.encodePathList(target.question.photoPaths),
                    documentPaths = RequestRepository.encodePathList((target.question.documentPaths + target.answer.documentPaths).distinct()),
                    answerText = target.answer.text,
                    finalAnswer = target.answer.finalAnswer,
                    figurePaths = RequestRepository.encodePathList(target.answer.figurePaths),
                ))
                if (openRevision == revision) onSaved(savedId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (openRevision == revision) error.value = e.message ?: "收藏失败，请重试" }
            finally { if (openRevision == revision) busy.value = false }
        }
    }
}

@Composable
internal fun FavoriteDialog(target: FavoriteTarget, onDismiss: () -> Unit, onSaved: (String) -> Unit, vm: FavoriteViewModel = hiltViewModel()) {
    val strings = rememberAppStrings()
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
        title = { Text(if (existing == null) strings.text(R.string.ui_save_to_notebook_fd727) else strings.text(R.string.ui_update_saved_item_e37c0)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(target.question.text.ifBlank { strings.text(R.string.ui_photo_question_770f1) }.take(100))
                if (existing != null) Text(strings.text(R.string.ui_saving_updates_this_item_s_question_and_answer_67ad3), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { categoryId = null; categoryEdited = true; newCategory = "" }, enabled = !busy) {
                    Text((if (categoryId == null && newCategory.isBlank()) "✓ " else "") + strings.text(R.string.ui_uncategorized_b28f1))
                }
                categories.forEach { category ->
                    TextButton(onClick = { categoryId = category.id; categoryEdited = true; newCategory = "" }, enabled = !busy) {
                        Text((if (categoryId == category.id && newCategory.isBlank()) "✓ " else "") + category.name)
                    }
                }
                OutlinedTextField(newCategory, { newCategory = it; categoryEdited = true }, label = { Text(strings.text(R.string.ui_or_create_a_category_b0857)) },
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { vm.save(target, categoryId, newCategory, onSaved) }, enabled = !busy && !loading) {
            Text(if (loading) strings.text(R.string.ui_loading_saved_item_4bda3) else if (busy) strings.text(R.string.ui_saving_6644f) else strings.text(R.string.ui_save_item_f699e))
        } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(strings.text(R.string.ui_cancel_4d0b4)) } },
    )
}
