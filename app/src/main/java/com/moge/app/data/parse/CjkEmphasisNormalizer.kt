package com.moge.app.data.parse

/**
 * CommonMark rejects **评分。**后文 and 前**“重点”** when punctuation touches CJK prose.
 * Fullwidth ASCII symbols have the same problem: **effects on sth.**＝ cannot close because
 * ＝ is a Unicode math symbol, while its ASCII counterpart = counts as Markdown punctuation.
 * Encode only the adjacent CJK character or fullwidth symbol as a numeric character reference:
 * the parser sees a punctuation boundary, then decodes exactly the original text. This retains
 * bold punctuation without inserting spaces or invisible characters into displayed/copied text.
 */
internal fun normalizeCjkStrongEmphasis(source: String, codeRanges: List<IntRange>): String {
    if ("**" !in source) return source
    val encodedCharacters = sortedSetOf<Int>()
    var codeIndex = 0
    var inMath = false
    var opener: Int? = null
    for (token in EMPHASIS_TOKENS.findAll(source)) {
        val start = token.range.first
        while (codeIndex < codeRanges.size && codeRanges[codeIndex].last < start) codeIndex++
        if (codeRanges.getOrNull(codeIndex)?.contains(start) == true || source.isEscapedMarkdownToken(start)) continue
        when (token.value) {
            "$$" -> inMath = !inMath
            "**" -> if (!inMath) {
                if (source.hasCodeIndentAt(start)) {
                    opener = null
                    continue
                }
                val end = token.range.last + 1
                val opening = opener
                if (opening != null && start > opening + 2 && !source[start - 1].isWhitespace()) {
                    collectCjkBoundaryCharacters(source, opening, start, encodedCharacters)
                    opener = null
                } else if (source.getOrNull(end)?.isWhitespace() == false) {
                    opener = start
                }
            }
            else -> if (!inMath && token.value.first() != '*') opener = null // Paragraph boundary.
        }
    }
    if (encodedCharacters.isEmpty()) return source
    return buildString(source.length + encodedCharacters.size * 8) {
        var index = 0
        while (index < source.length) {
            val character = source.codePointAt(index)
            if (index in encodedCharacters) append("&#").append(character).append(';')
            else appendCodePoint(character)
            index += Character.charCount(character)
        }
    }
}

/** Leave four-column indented code (and ambiguous list continuations) to CommonMark. */
internal fun String.hasCodeIndentAt(index: Int): Boolean {
    var cursor = lastIndexOf('\n', index - 1) + 1
    var spaces = 0
    while (cursor < index) {
        when (this[cursor++]) {
            '\t' -> return true
            ' ' -> if (++spaces == 4) return true
            else -> return false
        }
    }
    return false
}

private fun collectCjkBoundaryCharacters(source: String, opening: Int, closing: Int, encodedCharacters: MutableSet<Int>) {
    val beforeOpening = if (opening > 0) source.codePointBefore(opening) else -1
    if (beforeOpening.needsCjkBoundaryEncoding() && source.codePointAt(opening + 2).isMarkdownPunctuation() &&
        !source.isEscapedMarkdownToken(opening - Character.charCount(beforeOpening))
    ) {
        encodedCharacters += opening - Character.charCount(beforeOpening)
    }
    val afterClosing = closing + 2
    if (source.codePointBefore(closing).isMarkdownPunctuation() &&
        afterClosing < source.length && source.codePointAt(afterClosing).needsCjkBoundaryEncoding()
    ) encodedCharacters += afterClosing
}

/** Repair only fullwidth counterparts that CommonMark does not already classify as punctuation. */
private fun Int.needsCjkBoundaryEncoding(): Boolean {
    if (isCjkLetter()) return true
    // Fullwidth U+FF01..U+FF5E maps to ASCII U+0021..U+007E.
    if (this !in 0xff01..0xff5e) return false
    return !isMarkdownPunctuation() && (this - 0xfee0).isMarkdownPunctuation()
}

private fun Int.isCjkLetter(): Boolean = this >= 0 && Character.isLetter(this) && when (Character.UnicodeScript.of(this)) {
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
    else -> false
}

/** CommonMark treats all ASCII punctuation and Unicode punctuation categories as punctuation. */
private fun Int.isMarkdownPunctuation(): Boolean =
    this in 0x21..0x2f || this in 0x3a..0x40 || this in 0x5b..0x60 || this in 0x7b..0x7e ||
        Character.getType(this) in UNICODE_PUNCTUATION_TYPES

private val UNICODE_PUNCTUATION_TYPES = setOf(
    Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
    Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
    Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
    Character.OTHER_PUNCTUATION.toInt(),
)
private val EMPHASIS_TOKENS = Regex("\\*{2,}|\\$\\$|\\n[ \\t]*\\n")
