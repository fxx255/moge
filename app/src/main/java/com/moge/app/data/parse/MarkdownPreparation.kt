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
    return normalizeStrongEmphasisBoundaries(out.toString(), codeRanges)
}

private fun prepareProse(source: String): String =
    sanitizeReplyLatex(normalizeStandaloneEscapedBreaks(normalizeReplyMarkdown(source)))

/** Repair standalone escaped breaks, keeping TeX commands and braced arguments literal. */
private fun normalizeStandaloneEscapedBreaks(source: String): String {
    if ("\\n" !in source) return source
    var inMath = false
    var mathBraceDepth = 0
    var copiedUntil = 0
    val out = StringBuilder(source.length)
    for (token in STANDALONE_BREAK_TOKENS.findAll(source)) {
        if (source.isEscapedMarkdownToken(token.range.first)) continue
        if (token.value == "$$") {
            if (!inMath && source.hasCodeIndentAt(token.range.first)) continue
            inMath = !inMath
            mathBraceDepth = 0
            if (inMath && !source.hasCodeIndentAt(token.range.first)) {
                // Also repair older saved replies whose JSON decoder already converted newline+e
                // into \ne. Only a formula opener followed by the invalid \ne= sequence qualifies.
                val assignment = LEADING_E_ASSIGNMENT.matchAt(source, token.range.last + 1)
                if (assignment != null) {
                    val slash = assignment.range.first + assignment.value.indexOf('\\')
                    out.append(source, copiedUntil, slash).append('\n')
                    copiedUntil = slash + 2 // leave the variable e and the assignment intact
                }
            }
        } else if (token.value == "{") {
            if (inMath) mathBraceDepth++
        } else if (token.value == "}") {
            if (inMath && mathBraceDepth > 0) mathBraceDepth--
        } else if (if (inMath) mathBraceDepth == 0 else !source.hasCodeIndentAt(token.range.first)) {
            // A standalone \n is not a TeX command. In particular, providers sometimes leave
            // it immediately before the closing $$, causing the entire formula to fail.
            out.append(source, copiedUntil, token.range.first).append('\n')
            copiedUntil = token.range.last + 1
        }
    }
    return out.append(source, copiedUntil, source.length).toString()
}

private val STANDALONE_BREAK_TOKENS = Regex("""\$\$|[{}]|\\n(?=[ \t]*(?:\r?\n|$|\$\$))""")
private val LEADING_E_ASSIGNMENT = Regex("""[ \t\r\n]*\\ne(?=[ \t]*=)""")

private val CODE_TOKEN = Regex("(?m)^[ \\t]{0,3}(`{3,}|~{3,})[^\\n]*(?:\\n|$)|`+")

internal fun String.isEscapedMarkdownToken(index: Int): Boolean {
    var backslashes = 0
    var before = index - 1
    while (before >= 0 && this[before--] == '\\') backslashes++
    return backslashes % 2 == 1
}
