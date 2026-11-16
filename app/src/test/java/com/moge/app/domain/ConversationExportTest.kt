package com.moge.app.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ConversationExportTest {
    private fun item(id: String, time: String, category: String? = null) =
        ConversationExportItem(id, id, "", category, Instant.parse(time))

    @Test fun `inclusive date range respects timezone and 25 hour daylight saving day`() {
        val day = LocalDate.parse("2026-11-01")
        val entries = listOf(
            item("before", "2026-11-01T03:59:59Z"), item("first", "2026-11-01T04:00:00Z"),
            item("last", "2026-11-02T04:59:59Z"), item("after", "2026-11-02T05:00:00Z"),
        )
        assertEquals(listOf("last", "first"), filterConversationExports(entries,
            ConversationExportFilter(from = day, through = day), ZoneId.of("America/New_York")).map { it.id })
    }

    @Test fun `subject date and order combine and ties remain deterministic`() {
        val entries = listOf(item("b", "2026-11-16T06:00:00Z", "math"),
            item("a", "2026-11-16T06:00:00Z", "math"), item("c", "2026-11-15T06:00:00Z", "math"),
            item("other", "2026-11-16T06:00:00Z", "english"), item("none", "2026-11-16T06:00:00Z"))
        val filter = ConversationExportFilter(categoryId = "math", from = LocalDate.parse("2026-11-15"))
        assertEquals(listOf("a", "b", "c"), filterConversationExports(entries, filter).map { it.id })
        assertEquals(listOf("c", "a", "b"), filterConversationExports(entries, filter.copy(order = ConversationExportOrder.OLDEST_FIRST)).map { it.id })
        assertEquals(listOf("none"), filterConversationExports(entries, ConversationExportFilter(uncategorizedOnly = true)).map { it.id })
    }

    @Test fun `open ended and reversed ranges do not silently widen selection`() {
        val entries = listOf(item("a", "2026-11-01T12:00:00Z"), item("b", "2026-11-16T12:00:00Z"))
        val date = LocalDate.parse("2026-11-10")
        assertEquals(listOf("a"), filterConversationExports(entries, ConversationExportFilter(through = date)).map { it.id })
        assertEquals(listOf("b"), filterConversationExports(entries, ConversationExportFilter(from = date)).map { it.id })
        assertTrue(filterConversationExports(entries, ConversationExportFilter(from = date, through = date.minusDays(1))).isEmpty())
    }
}
