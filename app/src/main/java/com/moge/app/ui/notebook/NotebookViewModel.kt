package com.moge.app.ui.notebook

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.data.db.NotebookEntryEntity
import com.moge.app.data.db.NotebookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NotebookUiState(
    val query: String = "",
    val categoryId: String? = null,
    val uncategorizedOnly: Boolean = false,
    val categories: List<NotebookCategoryEntity> = emptyList(),
    val entries: List<NotebookEntryEntity> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val openEntryId: String? = null,
    val detailEntry: NotebookEntryEntity? = null,
    val sourceExists: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
    val categoryError: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
) {
    val selecting: Boolean get() = selectedIds.isNotEmpty()
    val filtered: Boolean get() = query.isNotBlank() || categoryId != null || uncategorizedOnly
}

internal const val SEARCH_DEBOUNCE_MS = 250L
private const val UNCATEGORIZED = "__uncategorized__"

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class NotebookViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: NotebookRepository,
    private val conversations: ConversationRepository,
) : ViewModel() {
    private val restoredCategory = savedState.get<String>(CATEGORY).orEmpty()
    private val _state = MutableStateFlow(NotebookUiState(
        query = savedState.get<String>(QUERY).orEmpty(),
        categoryId = restoredCategory.takeIf { it.isNotEmpty() && it != UNCATEGORIZED },
        uncategorizedOnly = restoredCategory == UNCATEGORIZED,
        selectedIds = savedState.get<ArrayList<String>>(SELECTED)?.toSet().orEmpty(),
        openEntryId = savedState.get<String>(OPEN)?.takeIf { it.isNotEmpty() },
    ))
    val state = _state.asStateFlow()
    private val reload = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            reload.flatMapLatest {
                repository.observeCategories().map { Categories(it) }.catch { error ->
                    if (error is CancellationException) throw error
                    emit(Categories(error = "分类读取失败，请重试"))
                }
            }.collect { loaded ->
                _state.update { it.copy(categories = loaded.values ?: it.categories, categoryError = loaded.error) }
                if (loaded.values != null && state.value.categoryId != null &&
                    loaded.values.none { it.id == state.value.categoryId }) setCategory(null)
            }
        }
        viewModelScope.launch {
            val query = savedState.getStateFlow(QUERY, state.value.query).withIndex()
                .debounce { (index, text) -> if (index == 0 || text.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
                .map { it.value }
            combine(query, savedState.getStateFlow(CATEGORY, restoredCategory), reload) { text, category, _ -> text to category }
                .flatMapLatest { (text, category) ->
                    repository.observeEntries(text, category.takeIf { it.isNotEmpty() && it != UNCATEGORIZED },
                        category == UNCATEGORIZED)
                        .map { Entries(values = it) }.onStart { emit(Entries(loading = true)) }
                        .catch { error ->
                            if (error is CancellationException) throw error
                            emit(Entries(error = "题册读取失败，请重试"))
                        }
                }.collect { loaded ->
                    _state.update { current ->
                        val entries = loaded.values ?: current.entries
                        current.copy(entries = entries, loading = loaded.loading, error = loaded.error,
                            selectedIds = if (loaded.values == null) current.selectedIds
                                else current.selectedIds.intersect(entries.map { it.id }.toSet()))
                    }
                    persistSelection()
                }
        }
        viewModelScope.launch {
            combine(savedState.getStateFlow(OPEN, state.value.openEntryId.orEmpty()), reload) { id, _ -> id }
                .flatMapLatest { id -> if (id.isBlank()) flowOf(null) else repository.observeEntry(id)
                    .catch { error ->
                        if (error is CancellationException) throw error
                        _state.update { it.copy(message = "收藏读取失败，请重试") }
                        emit(null)
                    } }
                .flatMapLatest { entry ->
                    _state.update { it.copy(detailEntry = entry, sourceExists = false) }
                    if (entry == null) flowOf(false)
                    else conversations.observeConversation(entry.sourceConversationId).map { it != null }
                        .catch { error -> if (error is CancellationException) throw error; emit(false) }
                }.collect { exists -> _state.update { it.copy(sourceExists = exists) } }
        }
    }

    fun setQuery(query: String) {
        if (state.value.busy) return
        _state.update { it.copy(query = query, selectedIds = emptySet()) }
        savedState[QUERY] = query
        persistSelection()
    }
    fun setCategory(id: String?, uncategorizedOnly: Boolean = false) {
        if (state.value.busy) return
        _state.update { it.copy(categoryId = id, uncategorizedOnly = uncategorizedOnly, selectedIds = emptySet()) }
        savedState[CATEGORY] = if (uncategorizedOnly) UNCATEGORIZED else id.orEmpty()
        persistSelection()
    }
    fun openEntry(id: String) {
        if (state.value.busy) return
        _state.update { it.copy(openEntryId = id, detailEntry = null, sourceExists = false, selectedIds = emptySet()) }
        savedState[OPEN] = id
        persistSelection()
    }
    fun closeEntry() {
        _state.update { it.copy(openEntryId = null, detailEntry = null, sourceExists = false) }
        savedState[OPEN] = ""
    }
    fun toggleSelection(id: String) {
        if (state.value.busy || state.value.entries.none { it.id == id }) return
        _state.update { it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id) }
        persistSelection()
    }
    fun selectAll() {
        if (state.value.busy) return
        _state.update { it.copy(selectedIds = it.entries.map { entry -> entry.id }.toSet()) }
        persistSelection()
    }
    fun clearSelection() {
        if (state.value.busy) return
        _state.update { it.copy(selectedIds = emptySet()) }
        persistSelection()
    }
    fun createCategory(name: String) = mutate { repository.createCategory(name); "分类已创建" }
    fun renameCategory(id: String, name: String) = mutate {
        if (repository.renameCategory(id, name)) "分类已改名" else "分类已被删除"
    }
    fun deleteCategory(id: String) = mutate {
        repository.deleteCategory(id)
        if (state.value.categoryId == id) {
            _state.update { it.copy(categoryId = null, uncategorizedOnly = true) }
            savedState[CATEGORY] = UNCATEGORIZED
        }
        "分类已删除，收藏已移至未分类"
    }
    fun reorderCategory(id: String, offset: Int) {
        val order = state.value.categories.map { it.id }.toMutableList()
        val from = order.indexOf(id)
        val to = from + offset
        if (from !in order.indices || to !in order.indices) return
        order.add(to, order.removeAt(from))
        mutate { repository.reorderCategories(order); "分类顺序已修改" }
    }
    fun moveSelected(categoryId: String?) {
        val ids = actionIds()
        if (ids.isEmpty()) return
        mutate {
            val moved = repository.moveEntries(ids, categoryId)
            _state.update { it.copy(selectedIds = emptySet()) }
            persistSelection()
            "已移动 $moved 条收藏"
        }
    }
    fun deleteSelected() {
        val ids = actionIds()
        if (ids.isEmpty()) return
        mutate {
            val deleted = repository.deleteFavorites(ids)
            if (state.value.openEntryId in ids) closeEntry()
            _state.update { it.copy(selectedIds = emptySet()) }
            persistSelection()
            "已移出 $deleted 条收藏，历史对话仍保留"
        }
    }
    fun retry() { reload.value++ }
    fun dismissMessage() { _state.update { it.copy(message = null) } }
    private fun actionIds(): Set<String> = state.value.openEntryId?.let { setOf(it) } ?: state.value.selectedIds
    private fun mutate(action: suspend () -> String) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { val message = action(); _state.update { it.copy(message = message) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(message = error.message ?: "操作失败，请重试") } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    private fun persistSelection() { savedState[SELECTED] = ArrayList(state.value.selectedIds) }
    private data class Entries(val values: List<NotebookEntryEntity>? = null, val loading: Boolean = false, val error: String? = null)
    private data class Categories(val values: List<NotebookCategoryEntity>? = null, val error: String? = null)
    private companion object {
        const val QUERY = "notebookQuery"
        const val CATEGORY = "notebookCategory"
        const val SELECTED = "notebookSelected"
        const val OPEN = "notebookOpenEntry"
    }
}
