package com.moge.app.ui.share

import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.HistoryEntry
import com.moge.app.domain.ConversationMode
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedConversationTargetTest {
    @Test fun newestActivityComesFirstEvenWhenOldConversationsArePinnedOrStillStreaming() {
        fun entry(id: String, seconds: Long, pinned: Boolean = false, active: Long = seconds) =
            HistoryEntry(ConversationEntity(id = id, title = id, pinned = pinned, updatedAt = Instant.ofEpochSecond(seconds),
                conversationMode = ConversationMode.CHAT.name), "", retentionUpdatedAt = Instant.ofEpochSecond(active))
        val targets = sharedConversationTargets(listOf(entry("pinned-old", 1, true), entry("new", 20),
            entry("streaming", 10, active = 30), entry("same-b", 20), entry("same-a", 20)))
        assertEquals(listOf("streaming", "new", "same-a", "same-b", "pinned-old"), targets.map { it.id })
        assertEquals(ConversationMode.CHAT, targets.first().mode)
    }
}
