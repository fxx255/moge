package com.moge.app.domain

/** Manual names allow more detail than the model's short generated summary. */
object ConversationName {
    const val MAX_CODE_POINTS = 80
    private val whitespace = Regex("\\s+")

    fun normalize(raw: String): String {
        val name = raw.trim().replace(whitespace, " ")
        require(name.isNotEmpty()) { "主题不能为空" }
        require(name.codePointCount(0, name.length) <= MAX_CODE_POINTS) { "主题最多 $MAX_CODE_POINTS 个字" }
        return name
    }
}
