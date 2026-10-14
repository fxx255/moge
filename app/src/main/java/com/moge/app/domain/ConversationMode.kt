package com.moge.app.domain

/** Fixed for a saved conversation; independent of the solving detail setting. */
enum class ConversationMode(val label: String) {
    SOLVE("解题"),
    CHAT("对话");

    companion object {
        fun fromName(value: String?): ConversationMode = entries.firstOrNull { it.name == value } ?: SOLVE
    }
}
