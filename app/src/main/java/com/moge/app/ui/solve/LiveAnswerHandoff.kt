package com.moge.app.ui.solve

import com.moge.app.data.db.RequestEntity
import com.moge.app.domain.RequestStatus
import com.moge.app.runtime.GenerationManager.ActiveState

/** Keep the displayed ink until the observer delivers the transaction finishing that same attempt. */
internal class LiveAnswerHandoff(private val conversationId: String?) {
    private var last: ActiveState? = null

    fun forDisplay(active: ActiveState, requests: List<RequestEntity>): ActiveState {
        if (active.phase?.isInFlight == true) {
            last = active.takeIf { conversationId != null && it.conversationId == conversationId }
            return active
        }
        val visible = last ?: return active
        val sameOwner = active.requestId == null || (active.requestId == visible.requestId &&
            active.attemptId == visible.attemptId && active.conversationId == conversationId &&
            active.answerMessageId == visible.answerMessageId)
        if (sameOwner && requests.any { it.conversationId == conversationId && it.requestId == visible.requestId &&
                it.attemptId == visible.attemptId && it.answerMessageId == visible.answerMessageId &&
                RequestStatus.fromName(it.status)?.isInFlight == true }) return visible
        last = null
        return active
    }
}
