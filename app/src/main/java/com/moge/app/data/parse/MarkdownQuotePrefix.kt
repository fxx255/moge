package com.moge.app.data.parse

/** Explicit CommonMark quote containers; the returned prefix never belongs to LaTeX. */
internal fun markdownQuotePrefix(line: String, maxDepth: Int = Int.MAX_VALUE): String {
    var end = 0
    var depth = 0
    while (depth < maxDepth) {
        val marker = BLOCK_QUOTE_MARKER.matchAt(line, end) ?: break
        end = marker.range.last + 1
        depth++
    }
    return line.substring(0, end)
}

private val BLOCK_QUOTE_MARKER = Regex(""" {0,3}>[ \t]?""")
