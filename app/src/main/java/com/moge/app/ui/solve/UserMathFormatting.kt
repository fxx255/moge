package com.moge.app.ui.solve

import com.moge.app.data.parse.normalizeReplyMarkdown

/** Display-only data. Never feed [renderSource] back to the editor or persistence. */
internal data class UserMathSource(
    val renderSource: String,
    val formulas: List<String>,
    val incomplete: Boolean,
)

private val MATH_DELIMITER = "$".repeat(2)
private val MARKDOWN_BACKTICK = 96.toChar()

/**
 * The shared normalizer owns delimiter conversion (including code/currency rules).
 * Inspect its canonical math spans, but pass the ORIGINAL source to the renderer.
 * Only an unambiguous, formula-only paste gets a display-only wrapper.
 */
internal fun userMathSource(raw: String): UserMathSource? {
    val source = raw
    if (source.isBlank()) return null
    val normalized = normalizeReplyMarkdown(source)
    val scan = scanCanonicalMath(normalized)
    if (scan.formulas.isNotEmpty() || scan.incomplete) {
        return UserMathSource(source, scan.formulas, scan.incomplete)
    }
    val formula = source.trim()
    if (!isStandaloneFormula(formula)) return null
    return UserMathSource(
        renderSource = "$MATH_DELIMITER\n$formula\n$MATH_DELIMITER",
        formulas = listOf(formula),
        incomplete = isUnfinishedFormula(formula),
    )
}

private data class MathScan(val formulas: List<String>, val incomplete: Boolean)

/** Recognition only: no second implementation of delimiter conversion. */
private fun scanCanonicalMath(source: String): MathScan {
    val formulas = mutableListOf<String>()
    var fence: Char? = null
    var fenceLength = 0
    var ticks = 0
    var open = -1
    var incomplete = false
    var offset = 0
    for (line in source.split('\n')) {
        val trimmed = line.trimStart()
        val marker = trimmed.firstOrNull()?.takeIf { it == MARKDOWN_BACKTICK || it == '~' }
        val markerLength = if (marker == null) 0 else trimmed.takeWhile { it == marker }.length
        if (open < 0 && markerLength >= 3) {
            if (fence == null) {
                fence = marker
                fenceLength = markerLength
            } else if (fence == marker && markerLength >= fenceLength) {
                fence = null
            }
        } else if (fence == null) {
            var i = 0
            while (i < line.length) {
                val c = line[i]
                if (open < 0 && c == MARKDOWN_BACKTICK) {
                    val count = line.substring(i).takeWhile { it == MARKDOWN_BACKTICK }.length
                    ticks = when {
                        ticks == 0 -> count
                        ticks == count -> 0
                        else -> ticks
                    }
                    i += count
                    continue
                }
                if (ticks == 0 && !source.isMathEscaped(offset + i)) {
                    if (line.startsWith(MATH_DELIMITER, i) &&
                        line.getOrNull(i - 1) != '$' && line.getOrNull(i + 2) != '$') {
                        if (open < 0) open = offset + i + 2
                        else {
                            val formula = source.substring(open, offset + i).trim()
                            if (formula.isNotEmpty()) {
                                formulas += formula
                                incomplete = incomplete || isUnfinishedFormula(formula)
                            }
                            open = -1
                        }
                        i += 2
                        continue
                    }
                    if (open < 0) {
                        // Closed slash delimiters have already been converted above.
                        if (line.startsWith("\\(", i) || line.startsWith("\\[", i)) incomplete = true
                        if (c == '$') {
                            val tail = line.substring(i + 1).trim()
                            if (tail.matches(Regex("[A-Za-z]")) || isStandaloneFormula(tail)) incomplete = true
                        }
                    }
                }
                i++
            }
        }
        offset += line.length + 1
    }
    return MathScan(formulas, incomplete || open >= 0)
}

private fun String.isMathEscaped(index: Int): Boolean {
    var cursor = index - 1
    while (cursor >= 0 && this[cursor] == '\\') cursor--
    return (index - cursor - 1) % 2 == 1
}

/** A small allowlist/lexer, not a TeX parser. Prose, code, currency and paths fail closed. */
private fun isStandaloneFormula(source: String): Boolean {
    var evidence = false
    var i = 0
    while (i < source.length) {
        val c = source[i]
        when {
            c == '\\' -> {
                val start = ++i
                while (i < source.length && (source[i] in 'A'..'Z' || source[i] in 'a'..'z')) i++
                val command = source.substring(start, i)
                if (command.isEmpty()) {
                    if (source.getOrNull(i) !in listOf('\\', ',', ';', ':', '!', '{', '}', '|')) return false
                    i++
                } else {
                    if (command !in STANDALONE_MATH_COMMANDS) return false
                    evidence = true
                    if (command in TEXT_ARGUMENT_COMMANDS) {
                        while (i < source.length && source[i].isWhitespace()) i++
                        if (source.getOrNull(i) == '{') {
                            var depth = 1
                            i++
                            while (i < source.length && depth > 0) {
                                if (!source.isMathEscaped(i)) {
                                    if (source[i] == '{') depth++
                                    if (source[i] == '}') depth--
                                }
                                i++
                            }
                        }
                    }
                }
            }
            c in 'A'..'Z' || c in 'a'..'z' -> {
                val start = i++
                while (i < source.length && (source[i] in 'A'..'Z' || source[i] in 'a'..'z')) i++
                val token = source.substring(start, i)
                if (token.length > 1 && !(evidence && token.length == 2 && token[0] == 'd')) return false
            }
            c == '^' || c == '_' -> {
                if (i == 0 || source[i - 1] !in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789})]") return false
                evidence = true
                i++
            }
            c in "αβγδεθλμνπρστωΓΔΘΛΠΣΦΩ∞∑∏∫√±×÷≤≥≠≈→∂" -> { evidence = true; i++ }
            c.isWhitespace() || c.isDigit() || c in "+-=<>{}()[].,!|&" -> i++
            else -> return false
        }
    }
    return evidence
}

private fun isUnfinishedFormula(source: String): Boolean {
    var depth = 0
    source.forEachIndexed { index, c ->
        if (!source.isMathEscaped(index)) {
            if (c == '{') depth++
            if (c == '}') depth--
        }
    }
    return depth != 0 || source.trimEnd().lastOrNull() in listOf('^', '_', '\\', '+', '-', '=')
}

private val TEXT_ARGUMENT_COMMANDS = setOf("text", "operatorname", "mathrm", "mathbf", "mathbb", "mathcal", "begin", "end")
private val STANDALONE_MATH_COMMANDS = TEXT_ARGUMENT_COMMANDS + setOf(
    "frac", "dfrac", "tfrac", "sqrt", "int", "iint", "iiint", "oint", "sum", "prod", "lim",
    "sin", "cos", "tan", "log", "ln", "exp", "alpha", "beta", "gamma", "delta", "theta",
    "lambda", "mu", "nu", "pi", "rho", "sigma", "tau", "phi", "omega", "infty", "partial",
    "nabla", "cdot", "times", "pm", "le", "leq", "ge", "geq", "neq", "equiv", "approx",
    "left", "right", "quad", "qquad", "ldots", "cdots", "overline", "underline", "vec", "hat",
)
