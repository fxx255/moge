package com.moge.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moge.app.data.db.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import com.moge.app.ui.solve.ConversationViewport
import com.moge.app.ui.solve.ConversationViewports

/** Share at the NavHost/activity owner so opening a new page retains the last conversation. */
@HiltViewModel
class ConversationNavigationViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    repository: ConversationRepository,
) : ViewModel() {
    private val viewports = ConversationViewports(savedState)
    fun readViewport(id: String): ConversationViewport? = viewports.read(id)
    fun saveViewport(id: String, viewport: ConversationViewport) = viewports.save(id, viewport)
    private val preferredConversationId = savedState.getStateFlow<String?>(PREVIOUS_CONVERSATION_ID, null)

    val previousConversationId: StateFlow<String?> = combine(
        preferredConversationId,
        repository.observeHistory(),
    ) { preferred, history ->
        preferred?.takeIf { id -> id.isNotBlank() && history.any { it.conversation.id == id } }
            // History is pin-sorted; the fallback must compare update times instead of taking its first row.
            ?: history.maxByOrNull { it.conversation.updatedAt }?.conversation?.id
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        preferredConversationId.value?.takeIf { it.isNotBlank() },
    )

    /** A new page has no ID; keep the existing preference until another conversation opens. */
    fun rememberConversation(id: String?) {
        if (!id.isNullOrBlank()) savedState[PREVIOUS_CONVERSATION_ID] = id
    }

    private companion object {
        const val PREVIOUS_CONVERSATION_ID = "previousConversationId"
    }
}
