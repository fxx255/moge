package com.moge.app.data.parse

/** Prefer one continuous delimiter/frame around the wrapped mathematical object. */
internal fun wrapFormulaGroup(
    latex: String,
    kind: FormulaGroupKind,
    maxWidthPx: Int,
    measure: (String) -> Int,
): List<String>? {
    for (group in formulaGroups(latex).filter { it.kind == kind }) {
        val body = trimSegment(latex.substring(group.bodyStart, group.bodyEnd))
        if (body.isEmpty()) continue
        val prefix = latex.substring(0, group.start) + group.opening
        val suffix = group.closing + latex.substring(group.end)
        fun render(rows: List<String>) = prefix + (if (rows.size == 1) rows.single() else formulaRows(rows)) + suffix
        // Reserve actual widths of integral limits, differential suffixes, frame
        // padding and the array's column padding, rather than estimating characters.
        val overhead = (measure(prefix + formulaRows(listOf("x")) + suffix) - measure("x")).coerceAtLeast(0)
        var width = maxWidthPx - overhead
        for (attempt in 0 until 3) {
            if (width <= 0) break
            val rows = moveLeadingSeparators(splitFormula(body, width, measure))
            if (rows.isEmpty() || rows == listOf(body)) break
            val candidate = render(rows)
            val overflow = measure(candidate) - maxWidthPx
            if (overflow <= 0) return listOf(candidate)
            // Tall delimiters can be slightly wider than the one-row probe.
            width -= overflow + 1
        }
        if (kind == FormulaGroupKind.Delimiter) {
            // On narrow screens an integral and its differential can occupy more
            // than half the width. Attach them only to the first/last rows, with
            // invisible partners keeping each row independently valid TeX.
            wrapDelimitedRows(body, prefix, suffix, maxWidthPx, measure)?.let { return it }
        }
    }
    return null
}

private fun wrapDelimitedRows(
    body: String,
    prefix: String,
    suffix: String,
    maxWidthPx: Int,
    measure: (String) -> Int,
): List<String>? {
    val overhead = maxOf(measure(prefix + "x\\right."), measure("\\left.x" + suffix)) - measure("x")
    var width = maxWidthPx - overhead.coerceAtLeast(0)
    for (attempt in 0 until 3) {
        if (width <= 0) break
        val rows = moveLeadingSeparators(splitFormula(body, width, measure))
        if (rows.size < 2) break
        val candidates = rows.mapIndexed { index, row ->
            (if (index == 0) prefix else "\\left.") + row +
                (if (index == rows.lastIndex) suffix else "\\right.")
        }
        val overflow = candidates.maxOf(measure) - maxWidthPx
        if (overflow <= 0) return candidates
        width -= overflow + 1
    }
    return null
}

private fun formulaRows(rows: List<String>): String =
    "\\begin{array}{l}" + rows.joinToString(" \\\\ ") + "\\end{array}"

/** Commas/semicolons finish the previous condition; signs stay on continuation rows. */
private fun moveLeadingSeparators(rows: List<String>): List<String> {
    val result = mutableListOf<String>()
    for (source in rows) {
        var row = source
        while (result.isNotEmpty() && (row.startsWith(',') || row.startsWith(';'))) {
            result[result.lastIndex] += row.first()
            row = trimSegment(row.drop(1))
        }
        if (row.isNotEmpty()) result += row
    }
    return result
}
