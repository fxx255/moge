package com.moge.app.ui.history

import com.moge.app.R
import com.moge.app.core.LocalizedText

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.HistoryEntry
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.data.db.NotebookRepository
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
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
    val categories: List<NotebookCategoryEntity> = emptyList(),
    val categoryId: String? = null,
    val uncategorizedOnly: Boolean = false,
    val categoryError: String? = null,
    val selectedIds: Set<String> = emptySet(),
    val loading: Boolean = true,
    val error: String? = null,
    val message: String? = null,
    val busy: Boolean = false,
    val activeConversationId: String? = null,
    val savedFavoriteId: String? = null,
    val autoCleanupEnabled: Boolean = true,
    val retentionDays: Int = UserSettings.DEFAULT_HISTORY_RETENTION_DAYS,
) {
    val selecting: Boolean get() = selectedIds.isNotEmpty()
    val filtered: Boolean get() = query.isNotBlank() || categoryId != null || uncategorizedOnly
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
    private val notebooks: NotebookRepository,
    private val settings: SettingsRepository,
    private val localizedText: LocalizedText,
) : ViewModel() {
    private val restoredCategory = savedState.get<String>(CATEGORY).orEmpty()
    private val _state = MutableStateFlow(HistoryUiState(
        categoryId = restoredCategory.takeIf { it.isNotBlank() && it != UNCATEGORIZED },
        uncategorizedOnly = restoredCategory == UNCATEGORIZED,
        query = savedState.get<String>(QUERY).orEmpty(),
        selectedIds = savedState.get<ArrayList<String>>(SELECTED)?.toSet().orEmpty(),
    ))
    val state = _state.asStateFlow()
    private val reload = MutableStateFlow(0)

    init {
        viewModelScope.launch {
            settings.settings.catch { error ->
                if (error is CancellationException) throw error
                emit(UserSettings(historyAutoCleanupEnabled = false))
            }.collect { prefs ->
                _state.update { it.copy(autoCleanupEnabled = prefs.historyAutoCleanupEnabled, retentionDays = prefs.historyRetentionDays) }
            }
        }
        viewModelScope.launch {
            // 打字时防抖，免得每个字都对全部消息做一次 LIKE；首次加载和清空搜索立即生效。
            val query = savedState.getStateFlow(QUERY, state.value.query).withIndex()
                .debounce { (index, text) -> if (index == 0 || text.isBlank()) 0L else SEARCH_DEBOUNCE_MS }
                .map { it.value }
            combine(
                query,
                reload,
                savedState.getStateFlow(CATEGORY, restoredCategory),
            ) { query, _, category -> query to category }
                .flatMapLatest { (query, category) ->
                    repository.observeHistory(query, category.takeIf { it.isNotBlank() && it != UNCATEGORIZED }, category == UNCATEGORIZED)
                        .map { Load(entries = it) }
                        .onStart { emit(Load(loading = true)) }
                        .catch { error ->
                            if (error is CancellationException) throw error
                            emit(Load(error = localizedText.text(R.string.ui_could_not_load_history_try_again_3c82a)))
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
            reload.flatMapLatest {
                notebooks.observeCategories().catch { error ->
                    if (error is CancellationException) throw error
                    _state.update { it.copy(categoryError = localizedText.text(R.string.ui_could_not_load_categories_try_again_9a7e8)) }
                }
            }.collect { categories ->
                _state.update { it.copy(categories = categories, categoryError = null) }
                val current = state.value.categoryId
                if (current != null && categories.none { it.id == current }) setCategory(null, true)
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

    fun openConversation(id: String, onOpen: (String, String?) -> Unit) {
        if (state.value.busy) return
        val query = state.value.query
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { onOpen(id, repository.revealSearchMatch(id, query)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(message = e.message ?: localizedText.text(R.string.ui_could_not_open_conversation_try_again_5b0f0)) } }
            finally { _state.update { it.copy(busy = false) } }
        }
    }

    fun setCategory(id: String?, uncategorized: Boolean) {
        if (state.value.busy) return
        _state.update { it.copy(categoryId = id, uncategorizedOnly = uncategorized, selectedIds = emptySet()) }
        savedState[CATEGORY] = if (uncategorized) UNCATEGORIZED else id.orEmpty()
        persistSelection()
    }

    fun createCategory(name: String) = mutate { notebooks.createCategory(name); localizedText.text(R.string.ui_category_created_183f5) }
    fun renameCategory(id: String, name: String) = mutate {
        if (notebooks.renameCategory(id, name)) localizedText.text(R.string.ui_category_renamed_cbf09) else localizedText.text(R.string.ui_category_deleted_00cab)
    }
    fun deleteCategory(id: String) = mutate {
        notebooks.deleteCategory(id)
        if (state.value.categoryId == id) {
            _state.update { it.copy(categoryId = null, uncategorizedOnly = true) }
            savedState[CATEGORY] = UNCATEGORIZED
        }
        localizedText.text(R.string.ui_category_deleted_questions_and_conversations_moved_to_uncate_86a78)
    }
    fun reorderCategory(id: String, offset: Int) {
        val order = state.value.categories.map { it.id }.toMutableList()
        val from = order.indexOf(id)
        val to = from + offset
        if (from !in order.indices || to !in order.indices) return
        order.add(to, order.removeAt(from))
        mutate { notebooks.reorderCategories(order); localizedText.text(R.string.ui_category_order_updated_1b019) }
    }
    fun moveItems(ids: Set<String>, categoryId: String?) {
        if (ids.isEmpty()) return
        mutate {
            val moved = repository.moveToCategory(ids, categoryId)
            var notebookSyncFailed = false
            val saved = try {
                notebooks.saveConversations(ids)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                notebookSyncFailed = true
                emptyList()
            }
            try {
                notebooks.syncConversationCategories(ids)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                notebookSyncFailed = true
            }
            _state.update { it.copy(selectedIds = it.selectedIds - ids) }
            persistSelection()
            if (notebookSyncFailed) localizedText.text(R.string.ui_moved_1_s_conversations_notebook_sync_failed_retry_later_b8c35, moved)
            else if (saved.isEmpty()) localizedText.text(R.string.ui_moved_1_s_conversations_no_complete_solutions_to_save_yet_ec22e, moved)
            else localizedText.text(R.string.ui_moved_1_s_conversations_and_saved_2_s_items_a648d, moved, saved.size)
        }
    }
    private fun mutate(action: suspend () -> String) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { val message = action(); _state.update { it.copy(message = message) } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { _state.update { it.copy(message = error.message ?: localizedText.text(R.string.ui_operation_failed_try_again_51d3c)) } }
            finally { _state.update { it.copy(busy = false) }; persistSelection() }
        }
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

    fun collectSelected() {
        val ids = state.value.selectedIds
        if (ids.isEmpty() || state.value.busy) return
        _state.update { it.copy(busy = true, message = null, savedFavoriteId = null) }
        viewModelScope.launch {
            try {
                val saved = notebooks.saveConversations(ids)
                _state.update { it.copy(
                    selectedIds = emptySet(),
                    savedFavoriteId = saved.singleOrNull(),
                    message = if (saved.isEmpty()) localizedText.text(R.string.ui_selected_conversations_have_no_complete_solutions_to_save_7bfb5) else localizedText.text(R.string.ui_saved_1_s_questions_aa22e, saved.size),
                ) }
                persistSelection()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: localizedText.text(R.string.ui_could_not_save_try_again_3a2cb)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissSavedFavorite() { _state.update { it.copy(savedFavoriteId = null) } }
    fun retry() { reload.value++ }
    fun dismissMessage() { _state.update { it.copy(message = null) } }

    fun renameSelected(title: String) {
        val id = state.value.selectedIds.singleOrNull() ?: return
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val renamed = repository.rename(id, title)
                _state.update { it.copy(message = if (renamed) localizedText.text(R.string.ui_title_updated_83c2f) else localizedText.text(R.string.ui_this_conversation_was_deleted_f7d12), selectedIds = emptySet()) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(message = error.message ?: localizedText.text(R.string.ui_could_not_update_title_try_again_df9d1)) }
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
                _state.update { it.copy(selectedIds = emptySet(), message = if (selected.conversation.pinned) localizedText.text(R.string.ui_unpinned_64ca5) else localizedText.text(R.string.ui_pinned_d7d97)) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(message = localizedText.text(R.string.ui_could_not_change_pin_try_again_da7cb)) }
            } finally {
                _state.update { it.copy(busy = false) }
                persistSelection()
            }
        }
    }

    fun deleteSelected() = deleteItems(state.value.selectedIds)

    fun deleteItems(selected: Set<String>) {
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
                        try { drafts.clearConversation(id) } catch (_: Exception) { draftFailed = true }
                    }
                    val kept = selected - deleted
                    val message = when {
                        kept.isNotEmpty() -> localizedText.text(R.string.ui_deleted_1_s_conversations_active_conversations_were_kept_sto_e691f, deleted.size)
                        draftFailed -> localizedText.text(R.string.ui_deleted_1_s_conversations_some_drafts_could_not_be_removed_b93a8, deleted.size)
                        else -> localizedText.text(R.string.ui_deleted_1_s_conversations_dc84a, deleted.size)
                    }
                    _state.update { it.copy(selectedIds = (it.selectedIds - deleted).intersect(it.entries.map { entry -> entry.conversation.id }.toSet()), message = message) }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _state.update { it.copy(message = localizedText.text(R.string.ui_could_not_delete_try_again_a5eae)) }
            } finally {
                _state.update { it.copy(busy = false) }
                persistSelection()
            }
        }
    }

    private fun persistSelection() { savedState[SELECTED] = ArrayList(state.value.selectedIds) }
    private data class Load(val entries: List<HistoryEntry>? = null, val loading: Boolean = false, val error: String? = null)
    private companion object {
        const val CATEGORY = "historyCategory"
        const val UNCATEGORIZED = "__uncategorized__"
        const val QUERY = "historyQuery"
        const val SELECTED = "historySelected"
    }
}
