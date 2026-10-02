package com.moge.app.data.parse

import kotlinx.serialization.json.Json

/** 模型返回的历史标题只作为单行文字保存，不参与正文或操作指令。 */
object ConversationTitle {
    private val leadingTitle = Regex("""^\s*\{\s*"conversation_title"\s*:\s*("(?:\\.|[^"\\])*")""")

    /** 长回答截断时，只接收对象开头已经闭合的标题，避免误取正文中的示例字段。 */
    fun fromIncompleteResponse(raw: String): String? {
        val encoded = leadingTitle.find(raw)?.groupValues?.get(1) ?: return null
        return normalize(runCatching { Json.decodeFromString<String>(encoded) }.getOrNull())
    }

    fun normalize(raw: String?): String? {
        val clean = raw.orEmpty().trim().trim('"', '\'', '「', '」', '“', '”', '`', '#')
            .replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank() || clean in setOf("新对话", "图片题目", "对话标题", "null")) return null
        val end = clean.offsetByCodePoints(0, minOf(40, clean.codePointCount(0, clean.length)))
        return clean.substring(0, end).trim()
    }
}
