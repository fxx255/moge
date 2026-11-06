package com.moge.app.ui.share

import com.moge.app.data.db.HistoryEntry
import com.moge.app.domain.ConversationMode
import java.time.Instant

data class SharedConversationTarget(
    val id: String,
    val title: String,
    val mode: ConversationMode,
    val lastActivity: Instant,
)

/** Sharing is chronological even when the history screen has pinned conversations. */
internal fun sharedConversationTargets(entries: List<HistoryEntry>): List<SharedConversationTarget> = entries
    .map { entry ->
        val conversation = entry.conversation
        SharedConversationTarget(conversation.id, conversation.title,
            ConversationMode.fromName(conversation.conversationMode),
            maxOf(conversation.updatedAt, entry.retentionUpdatedAt ?: conversation.updatedAt))
    }
    .sortedWith(compareByDescending<SharedConversationTarget> { it.lastActivity }.thenBy { it.id })
