package com.moge.app.ui.solve

import androidx.lifecycle.ViewModel
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
