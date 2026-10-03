package com.moge.app.ui.solve

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle

data class ConversationViewport(val messageId: String, val index: Int, val offset: Int) {
    fun indexIn(messageIds: List<String>): Int =
        messageIds.indexOf(messageId).takeIf { it >= 0 }
            ?: index.coerceIn(0, (messageIds.size - 1).coerceAtLeast(0))
}

/** Activity-owned saved state survives removing and recreating a conversation's navigation entry. */
internal class ConversationViewports(private val savedState: SavedStateHandle) {
    fun read(id: String): ConversationViewport? {
        val entry = savedState.get<Bundle>(KEY)?.getBundle(id) ?: return null
        return ConversationViewport(entry.getString("message").orEmpty(), entry.getInt("index"), entry.getInt("offset"))
    }

    fun save(id: String, viewport: ConversationViewport) {
        if (id.isBlank() || viewport.messageId.isBlank()) return
        val entries = Bundle(savedState.get<Bundle>(KEY) ?: Bundle())
        entries.putBundle(id, Bundle().apply {
            putString("message", viewport.messageId)
            putInt("index", viewport.index.coerceAtLeast(0))
            putInt("offset", viewport.offset.coerceAtLeast(0))
        })
        // Bound the saved-state payload; anchors are small and contain no answer text or photos.
        val recent = savedState.get<ArrayList<String>>(RECENT) ?: arrayListOf()
        val order = ArrayList(recent.filter { it != id } + id)
        while (order.size > 80) entries.remove(order.removeAt(0))
        savedState[KEY] = entries
        savedState[RECENT] = order
    }

    private companion object {
        const val KEY = "conversationViewports"
        const val RECENT = "conversationViewportOrder"
    }
}
