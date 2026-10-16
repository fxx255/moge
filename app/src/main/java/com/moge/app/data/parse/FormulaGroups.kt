package com.moge.app.data.parse

internal enum class FormulaGroupKind { Frame, Delimiter }

/** A complete, top-level math group. Command arguments and matrix contents are excluded. */
internal data class FormulaGroup(
    val kind: FormulaGroupKind,
    val start: Int,
    val bodyStart: Int,
    val bodyEnd: Int,
    val end: Int,
    val opening: String,
    val closing: String,
)

private data class MathDelimiter(val start: Int, val end: Int, val value: String, val sized: Boolean)

internal fun formulaGroups(latex: String): List<FormulaGroup> {
    val groups = mutableListOf<FormulaGroup>()
    val stack = mutableListOf<MathDelimiter>()
    var environmentDepth = 0
    var cursor = 0
    while (cursor < latex.length) {
        val start = cursor
        var command = ""
        if (latex[cursor] == '\\') {
            cursor++
            if (latex.getOrNull(cursor)?.isLetter() == true) {
                while (latex.getOrNull(cursor)?.isLetter() == true) cursor++
            } else if (cursor < latex.length) cursor++
            command = latex.substring(start + 1, cursor)
            if (command == "begin" || command == "end") {
                environmentDepth += if (command == "begin") 1 else -1
                cursor = followingGroupEnd(latex, cursor) ?: return emptyList()
                continue
            }
        } else cursor++
        if (environmentDepth != 0) continue

        if (command in setOf("boxed", "fbox")) {
            val bodyStart = skipSpaces(latex, cursor)
            val end = balancedGroupEnd(latex, bodyStart, '{', '}') ?: continue
            if (stack.isEmpty()) groups += FormulaGroup(
                FormulaGroupKind.Frame, start, bodyStart + 1, end - 1, end, "\\$command{", "}",
            )
            cursor = end
            continue
        }
        // Optional root indices are arguments, not visible square brackets.
        if (command == "sqrt") {
            val index = skipSpaces(latex, cursor)
            if (latex.getOrNull(index) == '[') {
                cursor = balancedGroupEnd(latex, index, '[', ']') ?: return emptyList()
            }
            continue
        }
        if (command.isEmpty() && latex[start] == '{') {
            cursor = balancedGroupEnd(latex, start, '{', '}') ?: return emptyList()
            continue
        }

        val sized = command == "left" || command == "right" || command in FIXED_DELIMITER_SIZES
        val token = if (sized) {
            val delimiterStart = skipSpaces(latex, cursor)
            val delimiterEnd = delimiterEnd(latex, delimiterStart)
            cursor = delimiterEnd
            MathDelimiter(start, cursor, latex.substring(delimiterStart, delimiterEnd), command == "left")
        } else MathDelimiter(start, cursor, latex.substring(start, cursor), false)
        // A period is a delimiter only after \left/\right, never ordinary punctuation.
        if (token.value == "." && command != "left" && command != "right") continue
        val isOpen = command == "left" || command != "right" && token.value in OPEN_DELIMITERS
        val isClose = command == "right" || command != "left" && token.value in CLOSE_DELIMITERS
        if (isOpen) stack += token
        else if (isClose) {
            val open = stack.lastOrNull() ?: continue
            // Scalable delimiters may be mixed (e.g. an interval [a,b)); ordinary
            // delimiters must match, so damaged input never exposes inner cuts.
            if (!open.sized && MATCHING_DELIMITERS[OPEN_DELIMITERS[open.value]] != CLOSE_DELIMITERS[token.value]) {
                return emptyList()
            }
            stack.removeAt(stack.lastIndex)
            if (stack.isEmpty() && open.value in OPEN_DELIMITERS && token.value in CLOSE_DELIMITERS) {
                groups += FormulaGroup(
                    FormulaGroupKind.Delimiter, open.start, open.end, token.start, token.end,
                    "\\left${OPEN_DELIMITERS.getValue(open.value)}", "\\right${CLOSE_DELIMITERS.getValue(token.value)}",
                )
            }
        }
    }
    return if (stack.isEmpty() && environmentDepth == 0) groups else emptyList()
}

private fun skipSpaces(source: String, from: Int): Int {
    var cursor = from
    while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
    return cursor
}

private fun followingGroupEnd(source: String, from: Int): Int? =
    balancedGroupEnd(source, skipSpaces(source, from), '{', '}')

private fun delimiterEnd(source: String, from: Int): Int {
    var cursor = from
    if (source.getOrNull(cursor) == '\\') {
        cursor++
        if (source.getOrNull(cursor)?.isLetter() == true) {
            while (source.getOrNull(cursor)?.isLetter() == true) cursor++
        } else if (cursor < source.length) cursor++
    } else if (cursor < source.length) cursor++
    return cursor
}

private fun balancedGroupEnd(source: String, start: Int, open: Char, close: Char): Int? {
    if (source.getOrNull(start) != open) return null
    var depth = 0
    var cursor = start
    while (cursor < source.length) {
        if (source[cursor] == '\\') { cursor = delimiterEnd(source, cursor); continue }
        if (source[cursor] == open) depth++
        if (source[cursor] == close && --depth == 0) return cursor + 1
        cursor++
    }
    return null
}

private val OPEN_DELIMITERS = mapOf(
    "(" to "(", "[" to "[", "\\{" to "\\{", "\\lbrace" to "\\{", "\\lbrack" to "[", "." to ".",
)
private val CLOSE_DELIMITERS = mapOf(
    ")" to ")", "]" to "]", "\\}" to "\\}", "\\rbrace" to "\\}", "\\rbrack" to "]", "." to ".",
)
private val MATCHING_DELIMITERS = mapOf("(" to ")", "[" to "]", "\\{" to "\\}")
private val FIXED_DELIMITER_SIZES = setOf(
    "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr",
)
