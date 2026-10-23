package com.moge.app.data.llm

/**
 * Only the complete fixed contract occupies the system role. Per-turn context accompanies
 * its user message, avoiding ambiguous handling of multiple system messages in gateways.
 * Keeping the original context on each turn also makes follow-ups append to a stable prefix.
 */
internal fun promptMessageSequence(
    messages: List<ChatMessage>,
    currentImages: List<String>,
    prompt: PromptSegments,
): List<ChatMessage> = buildList {
    val currentIndex = messages.indexOfLast { it.role == "user" }
    messages.forEachIndexed { index, message ->
        val current = index == currentIndex
        val context = if (current) prompt.volatileSystem else message.turnContext
        val images = if (current && currentImages.isNotEmpty()) currentImages else message.imageBase64s
        val content = if (message.role == "user" && context.isNotBlank()) {
            "【应用上下文】\n$context\n\n【用户消息】\n${message.content}"
        } else message.content
        add(message.copy(content = content, imageBase64s = images, turnContext = ""))
    }
}
