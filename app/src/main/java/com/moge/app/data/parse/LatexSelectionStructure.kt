package com.moge.app.data.parse

/** Fences and frames retain their TeX layout while exposing the math items inside them. */
internal data class LatexSelectionNode(
    val range: LatexSelectionToken,
    val group: FormulaGroup? = null,
    val children: List<LatexSelectionNode> = emptyList(),
) {
    fun tokens(): List<LatexSelectionToken> = if (group == null) listOf(range) else buildList {
        add(LatexSelectionToken(range.start, group.bodyStart))
        children.forEach { addAll(it.tokens()) }
        add(LatexSelectionToken(group.bodyEnd, range.end))
    }
}

internal fun latexSelectionStructure(source: String): List<LatexSelectionNode> {
    fun nodes(from: Int, to: Int, depth: Int): List<LatexSelectionNode> {
        val fragment = source.substring(from, to)
        val groups = if (depth < 32) formulaGroups(fragment).filter {
            it.kind == FormulaGroupKind.Frame ||
                it.kind == FormulaGroupKind.Delimiter && fragment.startsWith("\\left", it.start)
        } else emptyList()
        return atomicLatexSelectionTokens(fragment).map { token ->
            val range = LatexSelectionToken(from + token.start, from + token.end)
            val group = groups.firstOrNull {
                it.start >= token.start && it.end <= token.end &&
                    fragment.substring(token.start, it.start).isBlank() && it.bodyStart < it.bodyEnd
            }?.let { it.copy(start = from + it.start, bodyStart = from + it.bodyStart,
                bodyEnd = from + it.bodyEnd, end = from + it.end) }
            val children = group?.let { nodes(it.bodyStart, it.bodyEnd, depth + 1) }.orEmpty()
            if (children.isEmpty()) LatexSelectionNode(range)
            else LatexSelectionNode(range, group, children)
        }
    }
    return nodes(0, source.length, 0)
}

/** Copy only selected items; a partly selected scalable fence gets an invisible missing side. */
internal fun selectedLatexSource(source: String, from: Int, to: Int): String {
    if (from == 0 && to == source.length) return source.trim()
    fun selected(nodes: List<LatexSelectionNode>): String = buildString {
        for (node in nodes) {
            if (node.range.start >= to || node.range.end <= from) continue
            val group = node.group
            if (group == null) append(source, node.range.start, node.range.end)
            else if (group.kind == FormulaGroupKind.Frame) {
                // A partial copy is useful math on its own; never emit an unmatched
                // \boxed{ opener or an orphan closing brace.
                if (from <= node.range.start && to >= node.range.end) append(source, node.range.start, node.range.end)
                else append(selected(node.children))
            }
            else {
                val opening = from < group.bodyStart
                val closing = to > group.bodyEnd
                if (opening) append(source, node.range.start, group.bodyStart)
                else if (closing) append("\\left.")
                append(selected(node.children))
                if (closing) append(source, group.bodyEnd, node.range.end)
                else if (opening) append("\\right.")
            }
        }
    }
    return selected(latexSelectionStructure(source)).trim()
}
