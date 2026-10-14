package com.moge.app.ui.history

import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.HistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class HistoryRetentionLabelTest {
    private val now = Instant.parse("2026-10-14T12:00:00Z")
    private val entry = HistoryEntry(ConversationEntity(title = "题目", updatedAt = now), "")
    private fun label(value: HistoryEntry = entry, enabled: Boolean = true) =
        historyRetentionLabel(value, enabled, 7, now)

    @Test fun `remaining partial days round up and exact boundary waits for cleanup`() {
        assertEquals("剩余 7 天", label())
        assertEquals("剩余 6 天", label(entry.copy(conversation = entry.conversation.copy(updatedAt = now.minusSeconds(86_400)))))
        val boundary = entry.copy(conversation = entry.conversation.copy(updatedAt = now.minusSeconds(7 * 86_400)))
        assertEquals("待清理", label(boundary))
        assertEquals("剩余 1 天", label(boundary.copy(conversation = boundary.conversation.copy(updatedAt = boundary.conversation.updatedAt.plusNanos(1)))))
    }

    @Test fun `collections categories and disabled cleanup preserve history but pinning does not`() {
        assertEquals("长期保留", label(entry.copy(favoriteCount = 1)))
        assertEquals("长期保留", label(entry.copy(conversation = entry.conversation.copy(categoryId = "math"))))
        assertEquals("长期保留", label(enabled = false))
        assertEquals("剩余 7 天", label(entry.copy(conversation = entry.conversation.copy(pinned = true))))
    }

    @Test fun `recent request or message activity takes precedence over old conversation timestamp`() {
        assertEquals("剩余 7 天", label(entry.copy(
            conversation = entry.conversation.copy(updatedAt = now.minusSeconds(10 * 86_400)), retentionUpdatedAt = now)))
    }

    @Test fun `active requests cannot show an expired countdown`() {
        assertEquals("生成中保留", label(entry.copy(requestStatus = "PREPARING")))
        assertEquals("生成中保留", label(entry.copy(requestStatus = "RUNNING")))
        assertEquals("生成中保留", historyRetentionLabel(entry, true, 7, now, generating = true))
    }
}
