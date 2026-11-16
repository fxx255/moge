package com.moge.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class ConversationExportSource { NOTEBOOK, HISTORY }
enum class ConversationExportOrder { NEWEST_FIRST, OLDEST_FIRST }

/** Lightweight catalogue; message bodies are read only after confirming the selection. */
data class ConversationExportItem(
    val id: String,
    val title: String,
    val preview: String,
    val categoryId: String?,
    val updatedAt: Instant,
)

data class ConversationExportFilter(
    val categoryId: String? = null,
    val uncategorizedOnly: Boolean = false,
    val from: LocalDate? = null,
    val through: LocalDate? = null,
    val order: ConversationExportOrder = ConversationExportOrder.NEWEST_FIRST,
) {
    val valid: Boolean get() = from == null || through == null || from <= through
}

/** Both date endpoints are inclusive in the user's timezone, including DST transitions. */
fun filterConversationExports(
    items: List<ConversationExportItem>,
    filter: ConversationExportFilter,
    zone: ZoneId = ZoneId.systemDefault(),
): List<ConversationExportItem> {
    if (!filter.valid) return emptyList()
    val start = filter.from?.atStartOfDay(zone)?.toInstant()
    val end = filter.through?.plusDays(1)?.atStartOfDay(zone)?.toInstant()
    val comparator = compareBy<ConversationExportItem> { it.updatedAt }.thenBy { it.id }
    return items.filter {
        (filter.categoryId == null || it.categoryId == filter.categoryId) &&
            (!filter.uncategorizedOnly || it.categoryId == null) &&
            (start == null || it.updatedAt >= start) && (end == null || it.updatedAt < end)
    }.sortedWith(if (filter.order == ConversationExportOrder.OLDEST_FIRST) comparator
        else compareByDescending<ConversationExportItem> { it.updatedAt }.thenBy { it.id })
}
