package com.moge.app.data.parse

/** Repair model formatting only in prose; code examples must retain their literal escapes. */
internal fun prepareReplyMarkdown(source: String): String {
    val out = StringBuilder(source.length)
    var proseStart = 0
    var scanFrom = 0
    while (scanFrom < source.length) {
        val token = CODE_TOKEN.find(source, scanFrom) ?: break
        val marker = token.groups[1]?.value
        val end = if (marker != null) {
            val closing = Regex("(?m)^[ \\t]{0,3}" + Regex.escape(marker.first().toString()) +
                "{${marker.length},}[ \\t]*(?:\\n|$)").find(source, token.range.last + 1)
            closing?.range?.last?.plus(1) ?: source.length
        } else {
            val ticks = token.value
            if (source.isEscapedMarkdownToken(token.range.first)) {
                scanFrom = token.range.last + 1
                continue
            }
            val closing = Regex("(?<!`)" + Regex.escape(ticks) + "(?!`)").find(source, token.range.last + 1)
            if (closing == null) {
                scanFrom = token.range.last + 1
                continue
            }
            closing.range.last + 1
        }
        out.append(sanitizeReplyLatex(normalizeReplyMarkdown(source.substring(proseStart, token.range.first))))
        out.append(source, token.range.first, end)
        proseStart = end
        scanFrom = end
    }
    out.append(sanitizeReplyLatex(normalizeReplyMarkdown(source.substring(proseStart))))
    return out.toString()
}

private val CODE_TOKEN = Regex("(?m)^[ \\t]{0,3}(`{3,}|~{3,})[^\\n]*(?:\\n|$)|`+")

private fun String.isEscapedMarkdownToken(index: Int): Boolean {
    var backslashes = 0
    var before = index - 1
    while (before >= 0 && this[before--] == '\\') backslashes++
    return backslashes % 2 == 1
}
