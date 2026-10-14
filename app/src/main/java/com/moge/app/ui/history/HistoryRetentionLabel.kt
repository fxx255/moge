package com.moge.app.ui.history

import com.moge.app.data.db.HistoryEntry
import java.time.Duration
import java.time.Instant

/** The countdown uses elapsed 24-hour days, matching database expiry across time zones. */
internal fun historyRetentionLabel(
    entry: HistoryEntry,
    autoCleanupEnabled: Boolean,
    retentionDays: Int,
    now: Instant,
    generating: Boolean = false,
): String {
    if (!autoCleanupEnabled || entry.favoriteCount > 0 || entry.conversation.categoryId != null) {
        return "长期保留"
    }
    if (generating || entry.requestStatus in setOf("PREPARING", "RUNNING")) return "生成中保留"
    val activity = entry.retentionUpdatedAt ?: entry.conversation.updatedAt
    val remaining = Duration.between(now, activity.plusSeconds(retentionDays * 86_400L))
    if (remaining.isNegative || remaining.isZero) return "待清理"
    val days = remaining.seconds / 86_400L +
        if (remaining.seconds % 86_400L != 0L || remaining.nano != 0) 1 else 0
    return "剩余 $days 天"
}
