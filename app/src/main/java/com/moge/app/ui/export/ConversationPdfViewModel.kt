package com.moge.app.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moge.app.R
import com.moge.app.core.LocalizedText
import com.moge.app.data.db.ConversationExportRepository
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class ConversationPdfUiState(
    val items: List<ConversationExportItem> = emptyList(),
    val categories: List<NotebookCategoryEntity> = emptyList(),
    val filter: ConversationExportFilter = ConversationExportFilter(),
    val selectedIds: Set<String> = emptySet(),
    val loading: Boolean = true,
    val progress: String? = null,
    val error: String? = null,
    val ready: ConversationPdfResult? = null,
    val sharePending: Boolean = false,
) {
    val busy: Boolean get() = progress != null
}

@HiltViewModel
class ConversationPdfViewModel @Inject constructor(
    private val repository: ConversationExportRepository,
    private val exporter: ConversationPdfExporter,
    private val strings: LocalizedText,
) : ViewModel() {
    private val _state = MutableStateFlow(ConversationPdfUiState())
    internal val state = _state.asStateFlow()
    private var source: ConversationExportSource? = null
    private var job: Job? = null

    internal fun open(source: ConversationExportSource, initialIds: Set<String>) {
        if (this.source != null) return
        this.source = source
        load(initialIds)
    }

    private fun load(initialIds: Set<String>) {
        val origin = source ?: return
        val ready = state.value.ready
        _state.update { it.copy(loading = true, error = null, ready = null, sharePending = false) }
        release(ready)
        job = viewModelScope.launch {
            try {
                val items = repository.catalogue(origin)
                val categories = repository.categories()
                _state.update { it.copy(items = items, categories = categories,
                    selectedIds = initialIds.intersect(items.map { item -> item.id }.toSet()), loading = false) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.update { it.copy(loading = false, error = strings.text(R.string.pdf_load_failed)) } }
        }
    }

    internal fun retry() { if (!state.value.busy) load(state.value.selectedIds) }

    internal fun filter(filter: ConversationExportFilter) {
        if (state.value.busy) return
        val ready = state.value.ready.takeIf { filter.order != state.value.filter.order }
        _state.update { it.copy(filter = filter, ready = if (ready != null) null else it.ready,
            sharePending = if (ready != null) false else it.sharePending) }
        release(ready)
    }

    internal fun toggle(id: String) {
        if (state.value.busy || state.value.items.none { it.id == id }) return
        changeSelection { if (id in it) it - id else it + id }
    }

    internal fun selectAll() = changeSelection { state.value.items.map { it.id }.toSet() }
    internal fun selectMatching() = changeSelection { it + filterConversationExports(state.value.items, state.value.filter).map { item -> item.id } }
    internal fun clear() = changeSelection { emptySet() }

    private fun changeSelection(change: (Set<String>) -> Set<String>) {
        if (state.value.busy) return
        val ready = state.value.ready
        _state.update { it.copy(selectedIds = change(it.selectedIds), error = null, ready = null, sharePending = false) }
        release(ready)
    }

    internal fun export() {
        val origin = source ?: return
        val current = state.value
        if (current.busy || current.loading || current.selectedIds.isEmpty()) return
        current.ready?.let { _state.update { state -> state.copy(sharePending = true, error = null) }; return }
        val ordered = filterConversationExports(current.items, ConversationExportFilter(order = current.filter.order))
            .filter { it.id in current.selectedIds }
        _state.update { it.copy(progress = strings.text(R.string.pdf_saving), error = null) }
        job = viewModelScope.launch {
            try {
                val result = exporter.export(origin, ordered, current.categories) { message -> _state.update { it.copy(progress = message) } }
                _state.update { it.copy(ready = result, progress = null, sharePending = true) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.update { it.copy(progress = null, error = strings.text(R.string.pdf_export_failed)) } }
        }
    }

    internal fun cancelExport() {
        if (!state.value.busy) return
        job?.cancel()
        _state.update { it.copy(progress = null) }
    }

    /** Shared files remain readable after the dialog is closed or the receiving app opens. */
    internal fun retainForSharing() { state.value.ready?.files?.retainForSharing() }
    internal fun shared() { _state.update { it.copy(sharePending = false) } }
    internal fun shareFailed() { _state.update { it.copy(sharePending = false, error = strings.text(R.string.pdf_share_failed)) } }

    internal fun close() {
        job?.cancel(); job = null
        val result = state.value.ready
        _state.value = ConversationPdfUiState()
        source = null
        release(result)
    }

    private fun release(result: ConversationPdfResult?) {
        if (result != null) viewModelScope.launch(NonCancellable + Dispatchers.IO) { result.files.close() }
    }

    override fun onCleared() { release(state.value.ready) }
}
