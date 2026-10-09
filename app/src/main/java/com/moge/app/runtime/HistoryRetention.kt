package com.moge.app.runtime

import android.util.Log
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.prefs.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Database expiry precedes reference-aware attachment cleanup. */
@Singleton
class HistoryRetention @Inject constructor(
    private val conversations: ConversationRepository,
    private val drafts: DraftStore,
    private val attachments: AttachmentJanitor,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()

    suspend fun sweep(now: Instant = Instant.now()): Int = mutex.withLock {
        val policy = settings.current()
        if (!policy.historyAutoCleanupEnabled) return@withLock 0
        val deleted = conversations.deleteExpired(now, policy.historyRetentionDays)
        for (id in deleted) {
            try {
                drafts.clearConversation(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failed draft deletion must retain its files via the janitor's reference scan.
                Log.w(TAG, "expired conversation draft cleanup failed", e)
            }
        }
        attachments.sweep(now = now.toEpochMilli())
        deleted.size
    }

    private companion object { const val TAG = "HistoryRetention" }
}
