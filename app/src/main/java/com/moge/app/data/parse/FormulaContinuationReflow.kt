package com.moge.app.data.parse

/** Rejoin prematurely broken sums; equality steps and structured math keep their rows. */
internal fun reflowFormulaContinuations(latex: String, maxWidthPx: Int, measure: (String) -> Int): String {
    val match = EQUATION_ENVIRONMENT.matchEntire(latex) ?: return latex
    val env = match.groupValues[1]
    var body = match.groupValues[2].trim()
    var columns = ""
    if (env == "array") {
        // Markwon preparation converts aligned to a centered two-column array.
        // Exclude general tables and vectors; the continuation must also have
        // an empty left column and follow an equation below.
        val spec = ARRAY_ALIGNMENT.find(body) ?: return latex
        columns = spec.value
        body = body.substring(spec.range.last + 1).trim()
    }
    if (body.contains("\\begin{") || body.contains("\\hline")) return latex
    val (rows, skippedBreak) = splitTopLevelRows(body)
    if (skippedBreak || rows.size < 2 || rows.any { it.count { char -> char == '&' } > 1 }) return latex
    val result = mutableListOf<String>()
    var changed = false
    for (row in rows) {
        val continuation = row.substringAfter('&', row).let(::trimFormulaRow)
        val emptyLeft = '&' !in row || row.substringBefore('&').isBlank()
        val previous = result.lastOrNull()
        val equation = previous?.replace("&", " ").orEmpty()
        val combined = if (previous == null) "" else previous.trimEnd() + " " + continuation
        // Requiring an equality avoids turning signed vector entries into a sum.
        // Width is measured as one equation, without the alignment separator.
        if (previous != null && '=' in equation && emptyLeft && continuation.firstOrNull() in listOf('+', '-') &&
            measure(combined.replace("&", " ")) <= maxWidthPx) {
            result[result.lastIndex] = combined
            changed = true
        } else result += row
    }
    return if (changed) "\\begin{$env}$columns ${result.joinToString(" \\\\ ")} \\end{$env}" else latex
}

private val EQUATION_ENVIRONMENT = Regex(
    """^\\begin\{(aligned\*?|align\*?|split|array)\}(.*)\\end\{\1\}$""", RegexOption.DOT_MATCHES_ALL)
private val ARRAY_ALIGNMENT = Regex("""^\{\s*(?:r\s*l|c\s*c)\s*\}""")
