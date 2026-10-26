package com.moge.app.ui.markdown

internal enum class TableColumnAlignment { Start, Center, End }

internal fun isMarkdownTableSeparator(line: String): Boolean = tableCells(line).all {
    Regex(":?-+:?").matches(it)
}

internal data class MarkdownTable(val headers: List<String>, val rows: List<List<String>>,
    val alignments: List<TableColumnAlignment> = List(headers.size) { TableColumnAlignment.Start },
) {
    val columns: Int get() = headers.size
}

/** Escaped pipes and pipes in code spans are data, not column separators. */
internal fun tableCells(line: String): List<String> {
    val text = line.trim().removePrefix("|")
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    var cursor = 0
    var codeFence = 0
    while (cursor < text.length) {
        val char = text[cursor]
        if (char == '\\' && cursor + 1 < text.length) {
            cell.append(char).append(text[cursor + 1]); cursor += 2; continue
        }
        if (char == '`') {
            var end = cursor + 1
            while (end < text.length && text[end] == '`') end++
            val run = end - cursor
            if (codeFence == 0) codeFence = run else if (codeFence == run) codeFence = 0
            cell.append(text.substring(cursor, end)); cursor = end; continue
        }
        if (char == '|' && codeFence == 0) { cells += cell.toString().trim(); cell.clear() }
        else cell.append(char)
        cursor++
    }
    if (cell.isNotEmpty() || !text.endsWith('|')) cells += cell.toString().trim()
    return cells.ifEmpty { listOf("") }
}

internal fun parseMarkdownTable(source: String): MarkdownTable? {
    val lines = source.lineSequence().filter { it.isNotBlank() }.toList()
    if (lines.size < 2) return null
    val separators = tableCells(lines[1])
    if (!isMarkdownTableSeparator(lines[1])) return null
    val header = tableCells(lines[0])
    val rows = lines.drop(2).map(::tableCells)
    val count = maxOf(header.size, separators.size, rows.maxOfOrNull { it.size } ?: 0)
    // Keep surplus cells from malformed model tables instead of truncating to header width.
    return MarkdownTable(
        List(count) { header.getOrNull(it) ?: "列 ${it + 1}" },
        rows.map { row -> List(count) { row.getOrNull(it).orEmpty() } },
        List(count) { column ->
            val separator = separators.getOrNull(column).orEmpty()
            when {
                separator.startsWith(':') && separator.endsWith(':') -> TableColumnAlignment.Center
                separator.endsWith(':') -> TableColumnAlignment.End
                else -> TableColumnAlignment.Start
            }
        },
    )
}
