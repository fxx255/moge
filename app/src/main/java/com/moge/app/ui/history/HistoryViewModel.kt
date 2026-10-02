package com.moge.app.ui.history

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.HistoryEntry
import com.moge.app.runtime.DraftStore
import com.moge.app.runtime.GenerationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class HistoryUiState(
    val query: String = "",
    val entries: List<HistoryEntry> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val activeConversationId: String? = null,
) {
    val selecting: Boolean get() = selectedIds.isNotEmpty()
    val filtered: Boolean get() = query.isNotBlank()
}

internal const val SEARCH_DEBOUNCE_MS = 250L

/** 搜索和选中项跟随页面恢复；查询由 Room 驱动，生成期间返回历史也会实时刷新。 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val repository: ConversationRepository,
    private val drafts: DraftStore,
    private val manager: GenerationManager,
) : ViewModel() {
    private val _state = MutableStateFlow(HistoryUiState(
        query = savedState.get<String>(QUERY).orEmpty(),
        selectedIds = savedState.get<ArrayList<String>>(SELECTED)?.toSet().orEmpty(),
    ))
    val state = _state.asStateFlow()
    private val reload = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            // 打字时防抖，免得每个字都对全部消息做一次 LIKE；首次加载和清空搜索立即生效。
            val query = savedState.getStateFlow(QUERY, state.value.query).withIndex()
                .debounce { (index, text) -> if (index == 0 || text.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
                .map { it.value }
            combine(
                query,
                reload,
            ) { query, _ -> query }
                .flatMapLatest { query ->
                    repository.observeHistory(query)
                        .map { Load(entries = it) }
                        .onStart { emit(Load(loading = true)) }
                        .catch { error ->
                            if (error is CancellationException) throw error
                            emit(Load(error = "历史读取失败，请重试"))
                        }
                }.collect { load ->
                    _state.update { current ->
                        val entries = load.entries ?: current.entries
                        val ids = if (load.entries == null) current.selectedIds
                            else current.selectedIds.intersect(entries.map { it.conversation.id }.toSet())
                        current.copy(entries = entries, selectedIds = ids, loading = load.loading, error = load.error)
                    }
                    persistSelection()
                }
        }
        viewModelScope.launch {
            manager.active.collect { active ->
                _state.update { it.copy(activeConversationId = active.conversationId.takeIf { active.requestId != null }) }
            }
        }
    }

    fun setQuery(query: String) {
        if (state.value.busy) return
        _state.update { it.copy(query = query, selectedIds = emptySet()) }
        savedState[QUERY] = query
        persistSelection()
    }

    fun toggleSelection(id: String) {
        if (state.value.busy || state.value.entries.none { it.conversation.id == id }) return
        _state.update { it.copy(selectedIds = if (id in it.selectedIds) it.selectedIds - id else it.selectedIds + id) }
        persistSelection()
    }

    fun selectAll() {
        if (state.value.busy) return
        _state.update { it.copy(selectedIds = it.entries.map { entry -> entry.conversation.id }.toSet()) }
        persistSelection()
    }

    fun clearSelection() {
        if (state.value.busy) return
        _state.update { it.copy(selectedIds = emptySet()) }
        persistSelection()
    }

    fun retry() { reload.value++ }
    fun dismissMessage() { _state.update { it.copy(message = null) } }

    fun renameSelected(title: String) {
        val id = state.value.selectedIds.singleOrNull() ?: return
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val renamed = repository.rename(id, title)
                _state.update { it.copy(message = if (renamed) "标题已修改" else "这个对话已被删除", selectedIds = emptySet()) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: "标题修改失败，请重试") }
            } finally {
                _state.update { it.copy(busy = false) }
                persistSelection()
            }
        }
    }

    fun pinSelected() {
        val selected = state.value.entries.singleOrNull { it.conversation.id in state.value.selectedIds } ?: return
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                repository.setPinned(selected.conversation.id, !selected.conversation.pinned)
                _state.update { it.copy(selectedIds = emptySet(), message = if (selected.conversation.pinned) "已取消置顶" else "已置顶") }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(message = "置顶修改失败，请重试") }
            } finally {
                _state.update { it.copy(busy = false) }
                persistSelection()
            }
        }
    }

    fun deleteSelected() {
        val selected = state.value.selectedIds
        if (selected.isEmpty() || state.value.busy) return
        val protected = state.value.activeConversationId
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                // 用户已确认的删除即使页面退出也完成，避免数据库已删、旧草稿仍然恢复。
                withContext(NonCancellable) {
                    val deleted = repository.deleteIdle(selected - setOfNotNull(protected))
                    var draftFailed = false
                    deleted.forEach { id ->
                        try { drafts.clear(id) } catch (_: Exception) { draftFailed = true }
                    }
                    val kept = selected - deleted
                    val visibleKept = kept.intersect(state.value.entries.map { it.conversation.id }.toSet())
                    val message = when {
                        kept.isNotEmpty() -> "已删除 ${deleted.size} 个对话；生成中的对话已保留，请停止生成后再删除"
                        draftFailed -> "已删除 ${deleted.size} 个对话，部分草稿未能清理"
                        else -> "已删除 ${deleted.size} 个对话"
                    }
                    _state.update { it.copy(selectedIds = visibleKept, message = message) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(message = "删除失败，请重试") }
            } finally {
                _state.update { it.copy(busy = false) }
                persistSelection()
            }
        }
    }

    private fun persistSelection() { savedState[SELECTED] = ArrayList(state.value.selectedIds) }
    private data class Load(val entries: List<HistoryEntry>? = null, val loading: Boolean = false, val error: String? = null)
    private companion object {
        const val QUERY = "historyQuery"
        const val SELECTED = "historySelected"
    }
}
