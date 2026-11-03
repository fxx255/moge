package com.moge.app.data.parse

/**
 * A JSON line break followed by e= or a leading e^ also spells the TeX command \ne.
 * A leading exponential has no left operand for an inequality. Keep superscripted
 * relations inside an expression unchanged. Doubled JSON backslashes bypass decoding repair.
 * Keep this decision shared by complete and incremental JSON decoding.
 */
internal fun isNewlineBeforeEExpression(word: String, following: Char?, atFormulaStart: Boolean): Boolean =
    word == "ne" && (following == '=' || following == '^' && atFormulaStart)

/** Accept decoded whitespace and the escaped whitespace still present on the JSON wire. */
internal fun hasTrailingFormulaOpener(prefix: CharSequence): Boolean {
    var end = prefix.length
    while (end > 0) {
        if (prefix[end - 1].isWhitespace()) {
            end--
            continue
        }
        if (prefix[end - 1] in "nrt" && end >= 2 && prefix[end - 2] == '\\') {
            var start = end - 2
            while (start > 0 && prefix[start - 1] == '\\') start--
            if (end - 1 - start <= 2) {
                end = start
                continue
            }
        }
        break
    }
    if (end == 0) return false
    if (prefix[end - 1] == '$') {
        var start = end - 1
        if (start > 0 && prefix[start - 1] == '$') start--
        return start == 0 || prefix[start - 1] != '\\'
    }
    return end >= 2 && prefix[end - 1] in "[(" && prefix[end - 2] == '\\'
}
