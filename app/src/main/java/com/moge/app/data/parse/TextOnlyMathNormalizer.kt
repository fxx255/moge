package com.moge.app.data.parse

/**
 * A prose sentence wrapped in $$\text{...}$$ otherwise becomes one scaled, atomic image.
 * Recover only a complete text wrapper with unmistakable prose; text labels inside actual
 * equations, nested TeX and unfinished streaming input must retain their math semantics.
 * This receives normalized $$ delimiters and prose ranges with code already excluded.
 */
internal fun normalizeTextOnlyMath(markdown: String): String {
    if ("\\text" !in markdown && "\\mbox" !in markdown) return markdown
    val delimiters = TEXT_MATH_DELIMITER.toPattern().matcher(markdown)
    val out = StringBuilder(markdown.length)
    var opening: Int? = null
    var copiedUntil = 0
    while (delimiters.find()) {
        val index = delimiters.start()
        if (markdown.isEscapedMarkdownToken(index)) continue
        val start = opening
        if (start == null) {
            opening = index
            continue
        }
        opening = null
        if (markdown.hasCodeIndentAt(start)) continue
        val wrapper = PROSE_TEXT_WRAPPER.matchEntire(markdown.substring(start + 2, index)) ?: continue
        val body = wrapper.groupValues[2]
        if (body.any { it in "*`^=<>≤≥≠≈∑∏∫√" }) continue
        val text = TEX_TEXT_ESCAPE.replace(body) { it.groupValues[1] }
            .replace(PROSE_WHITESPACE, " ").trim()
        if (!text.isProseSentence()) continue
        out.append(markdown, copiedUntil, start)
        // Keep surrounding source line breaks so block text keeps its paragraph position.
        out.append(wrapper.groupValues[1])
        // Literal TeX text must not become Markdown links, HTML, lists or emphasis.
        for (character in text) {
            if (character in '!'..'/' || character in ':'..'@' || character in '['..'`' || character in '{'..'~') {
                out.append("&#").append(character.code).append(';')
            } else out.append(character)
        }
        out.append(wrapper.groupValues[3])
        copiedUntil = index + 2
    }
    if (copiedUntil == 0) return markdown
    return out.append(markdown, copiedUntil, markdown.length).toString()
}

private fun String.isProseSentence(): Boolean {
    // Do not reinterpret short mathematical labels or sequences of variables as body text.
    if (count { it in '\u3400'..'\u9fff' } >= 8) return true
    return PROSE_WORD.findAll(this).take(4).count() == 4 &&
        PROSE_LONG_WORD.findAll(this).take(2).count() == 2
}

private val TEXT_MATH_DELIMITER = Regex("""\$\$""")
private val PROSE_TEXT_WRAPPER = Regex("""([ \t\r\n]*)\\(?:text|textrm|textnormal|mbox)\s*\{((?:[^{}\\]|\\[{}%&#_$ ])*)\}([ \t\r\n]*)""")
private val TEX_TEXT_ESCAPE = Regex("""\\([{}%&#_$ ])""")
private val PROSE_WHITESPACE = Regex("\\s+")
private val PROSE_WORD = Regex("\\p{L}+")
private val PROSE_LONG_WORD = Regex("\\p{L}{3,}")
