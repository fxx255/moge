package com.moge.app.data.parse

/** Source ranges that can be copied without cutting a command, its arguments or scripts in half. */
internal data class LatexSelectionToken(val start: Int, val end: Int)

internal fun latexSelectionTokens(source: String): List<LatexSelectionToken> {
    val reader = LatexTokenReader(source)
    val tokens = mutableListOf<LatexSelectionToken>()
    var cursor = 0
    while (cursor < source.length) {
        val start = cursor
        while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
        if (cursor == source.length) break
        cursor = reader.atomEnd(cursor)
        if (cursor <= start) return listOf(LatexSelectionToken(0, source.length))
        while (true) {
            var next = cursor
            while (source.getOrNull(next)?.isWhitespace() == true) next++
            if (source.getOrNull(next) !in listOf('^', '_')) break
            cursor = reader.atomEnd(next + 1)
        }
        tokens += LatexSelectionToken(start, cursor)
    }
    if (tokens.isNotEmpty()) tokens[tokens.lastIndex] = tokens.last().copy(end = source.length)
    return tokens
}

private class LatexTokenReader(private val source: String) {
    fun atomEnd(from: Int): Int {
        var start = from
        while (source.getOrNull(start)?.isWhitespace() == true) start++
        val char = source.getOrNull(start) ?: return source.length
        if (char == '{') return groupEnd(start, '{', '}')
        if (char != '\\') return start + Character.charCount(source.codePointAt(start))
        var end = start + 1
        if (source.getOrNull(end)?.isLetter() != true) return (end + 1).coerceAtMost(source.length)
        while (source.getOrNull(end)?.isLetter() == true) end++
        val command = source.substring(start + 1, end)
        // Delimiters and environments need their matching closer. Keep these
        // compound objects intact, just as we keep a fraction and its two arguments.
        if (command == "left") return pairedEnd(end, "\\left", "\\right", delimiter = true)
        if (command == "begin") return pairedEnd(end, "\\begin", "\\end", delimiter = false)
        if (command == "sqrt" && source.getOrNull(end) == '[') end = groupEnd(end, '[', ']')
        val arguments = when (command) {
            "frac", "dfrac", "tfrac", "binom", "dbinom", "tbinom", "overset", "underset" -> 2
            "sqrt", "mathcal", "mathbf", "mathrm", "mathit", "mathbb", "mathsf", "mathtt",
            "boldsymbol", "text", "operatorname", "overline", "underline", "hat", "bar", "vec",
            "tilde", "widehat", "widetilde", "boxed", "fbox" -> 1
            else -> 0
        }
        repeat(arguments) { end = atomEnd(end) }
        // Unknown macros with explicit groups are also safe, indivisible units.
        while (true) {
            var next = end
            while (source.getOrNull(next)?.isWhitespace() == true) next++
            if (source.getOrNull(next) != '{') break
            end = groupEnd(next, '{', '}')
        }
        // Declarations and explicit spaces affect the following atom; they have
        // no independent visible boundary to grab.
        if (command in setOf("displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle", "quad", "qquad")) {
            end = atomEnd(end)
        }
        return end
    }

    private fun groupEnd(start: Int, open: Char, close: Char): Int {
        var depth = 0
        var cursor = start
        while (cursor < source.length) {
            if (source[cursor] == '\\') { cursor += 2; continue }
            if (source[cursor] == open) depth++
            if (source[cursor] == close) {
                depth--
                if (depth == 0) return cursor + 1
            }
            cursor++
        }
        return source.length
    }

    private fun pairedEnd(from: Int, open: String, close: String, delimiter: Boolean): Int {
        var depth = 1
        var cursor = from
        while (cursor < source.length) {
            if (source.startsWith(open, cursor) && source.getOrNull(cursor + open.length)?.isLetter() != true) depth++
            if (source.startsWith(close, cursor) && source.getOrNull(cursor + close.length)?.isLetter() != true) {
                depth--
                if (depth == 0) {
                    cursor += close.length
                    while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
                    return if (delimiter) atomEnd(cursor) else groupEnd(cursor, '{', '}')
                }
            }
            cursor++
        }
        return source.length
    }
}
