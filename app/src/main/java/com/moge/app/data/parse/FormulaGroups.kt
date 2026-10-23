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
    /** Inner products, norms and rounding operators must remain one mathematical unit. */
    val allowsInnerWrapping: Boolean = true,
)

internal data class MathDelimiter(
    val start: Int,
    val end: Int,
    val value: String,
    val sized: Boolean,
    val isOpening: Boolean,
)

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

        val token = mathDelimiterAt(latex, start) ?: continue
        cursor = token.end
        if (token.isOpening) stack += token
        else {
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
                    allowsInnerWrapping = OPEN_DELIMITERS.getValue(open.value) !in ATOMIC_DELIMITERS,
                )
            }
        }
    }
    return if (stack.isEmpty() && environmentDepth == 0) groups else emptyList()
}

/** Shared by group discovery and line breaking; '<' and '>' remain relation operators. */
internal fun mathDelimiterAt(source: String, start: Int): MathDelimiter? {
    var end = delimiterEnd(source, start)
    val command = source.substring(start, end).removePrefix("\\")
    val sized = command == "left" || command == "right"
    val hasSize = sized || source[start] == '\\' && command in FIXED_DELIMITER_SIZES
    val delimiterStart = if (hasSize) skipSpaces(source, end) else start
    if (hasSize) end = delimiterEnd(source, delimiterStart)
    val value = source.substring(delimiterStart, end)
    // A period is invisible only with a scalable delimiter, not ordinary punctuation.
    if (value == "." && !sized) return null
    val opening = when {
        sized -> command == "left"
        value in OPEN_DELIMITERS -> true
        value in CLOSE_DELIMITERS -> false
        else -> return null
    }
    return MathDelimiter(start, end, value, command == "left", opening)
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
    "\\langle" to "\\langle", "⟨" to "\\langle", "〈" to "\\langle",
    "\\lfloor" to "\\lfloor", "⌊" to "\\lfloor", "\\lceil" to "\\lceil", "⌈" to "\\lceil",
    "\\lvert" to "\\lvert", "\\lVert" to "\\lVert",
)
private val CLOSE_DELIMITERS = mapOf(
    ")" to ")", "]" to "]", "\\}" to "\\}", "\\rbrace" to "\\}", "\\rbrack" to "]", "." to ".",
    "\\rangle" to "\\rangle", "⟩" to "\\rangle", "〉" to "\\rangle",
    "\\rfloor" to "\\rfloor", "⌋" to "\\rfloor", "\\rceil" to "\\rceil", "⌉" to "\\rceil",
    "\\rvert" to "\\rvert", "\\rVert" to "\\rVert",
)
private val MATCHING_DELIMITERS = mapOf(
    "(" to ")", "[" to "]", "\\{" to "\\}", "\\langle" to "\\rangle",
    "\\lfloor" to "\\rfloor", "\\lceil" to "\\rceil", "\\lvert" to "\\rvert", "\\lVert" to "\\rVert",
)
private val ATOMIC_DELIMITERS = setOf("\\langle", "\\lfloor", "\\lceil", "\\lvert", "\\lVert")
private val FIXED_DELIMITER_SIZES = setOf(
    "big", "Big", "bigg", "Bigg", "bigl", "bigr", "Bigl", "Bigr", "biggl", "biggr", "Biggl", "Biggr",
)
