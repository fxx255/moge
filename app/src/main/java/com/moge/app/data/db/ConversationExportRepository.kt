package com.moge.app.data.db

import androidx.room.withTransaction
import com.moge.app.core.IoDispatcher
import com.moge.app.domain.ConversationExportItem
import com.moge.app.domain.ConversationExportSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ConversationExportSnapshot(
    val title: String,
    val messages: List<MessageEntity> = emptyList(),
    val notebook: NotebookEntryEntity? = null,
    val requests: List<RequestEntity> = emptyList(),
)

/** Read-only snapshots. Export never changes branch selections or the saved notebook answer. */
class ConversationExportRepository @Inject constructor(
    private val db: MogeDatabase,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun catalogue(source: ConversationExportSource): List<ConversationExportItem> = withContext(io) {
        when (source) {
            ConversationExportSource.NOTEBOOK -> db.notebookDao().exportCatalogue()
            ConversationExportSource.HISTORY -> db.conversationDao().exportCatalogue()
        }
    }

    suspend fun categories(): List<NotebookCategoryEntity> = withContext(io) { db.notebookDao().getCategories() }

    suspend fun snapshot(source: ConversationExportSource, id: String): ConversationExportSnapshot = withContext(io) {
        db.withTransaction {
            when (source) {
                ConversationExportSource.NOTEBOOK -> {
                    val entry = checkNotNull(db.notebookDao().getEntry(id)) { "所选收藏已被移除，请重新选择" }
                    ConversationExportSnapshot(entry.title, notebook = entry)
                }
                ConversationExportSource.HISTORY -> {
                    val dao = db.conversationDao()
                    val conversation = checkNotNull(dao.getConversation(id)) { "所选对话已被移除，请重新选择" }
                    val messages = ConversationBranches.visible(dao.getMessages(id), dao.getBranchSelections(id))
                    ConversationExportSnapshot(conversation.title, messages = messages,
                        requests = dao.exportRequests(id))
                }
            }
        }
    }
}
