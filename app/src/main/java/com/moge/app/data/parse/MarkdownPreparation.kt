package com.moge.app.data.parse

/** Repair model formatting only in prose; code examples must retain their literal escapes. */
internal fun prepareReplyMarkdown(source: String): String {
    val out = StringBuilder(source.length)
    val codeRanges = mutableListOf<IntRange>()
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
        out.append(prepareProse(source.substring(proseStart, token.range.first)))
        val codeStart = out.length
        out.append(source, token.range.first, end)
        codeRanges += codeStart until out.length
        proseStart = end
        scanFrom = end
    }
    out.append(prepareProse(source.substring(proseStart)))
    return normalizeCjkStrongEmphasis(out.toString(), codeRanges)
}

private fun prepareProse(source: String): String =
    sanitizeReplyLatex(normalizeStandaloneEscapedBreaks(normalizeReplyMarkdown(source)))

/** Repair a lone escaped break at a prose boundary, never an n-prefixed TeX command. */
private fun normalizeStandaloneEscapedBreaks(source: String): String {
    if ("\\n" !in source) return source
    var inMath = false
    var copiedUntil = 0
    val out = StringBuilder(source.length)
    for (token in STANDALONE_BREAK_TOKENS.findAll(source)) {
        if (source.isEscapedMarkdownToken(token.range.first)) continue
        if (token.value == "$$") inMath = !inMath
        else if (!inMath && !source.hasCodeIndentAt(token.range.first)) {
            out.append(source, copiedUntil, token.range.first).append('\n')
            copiedUntil = token.range.last + 1
        }
    }
    return out.append(source, copiedUntil, source.length).toString()
}

private val STANDALONE_BREAK_TOKENS = Regex("""\$\$|\\n(?=[ \t]*(?:\r?\n|$|\$\$))""")

private val CODE_TOKEN = Regex("(?m)^[ \\t]{0,3}(`{3,}|~{3,})[^\\n]*(?:\\n|$)|`+")

internal fun String.isEscapedMarkdownToken(index: Int): Boolean {
    var backslashes = 0
    var before = index - 1
    while (before >= 0 && this[before--] == '\\') backslashes++
    return backslashes % 2 == 1
}
