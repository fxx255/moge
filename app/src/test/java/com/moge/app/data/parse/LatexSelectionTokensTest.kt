package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class LatexSelectionTokensTest {
    private fun parts(source: String) = latexSelectionTokens(source).map { source.substring(it.start, it.end).trim() }

    @Test fun `fractions scripts and unbraced font arguments remain copyable units`() {
        assertEquals(listOf("\\mathcal D_1", "=", "\\frac{75^\\circ}{360^\\circ}", "+", "x^2"),
            parts("\\mathcal D_1=\\frac{75^\\circ}{360^\\circ}+x^2"))
    }

    @Test fun `limits radicals and optional root index keep their arguments`() {
        assertEquals(listOf("\\lim_{\\sigma\\to\\infty}", "p_5", "=", "\\sqrt[3]{x+1}"),
            parts("\\lim_{\\sigma\\to\\infty}p_5=\\sqrt[3]{x+1}"))
    }

    @Test fun `scalable braces expose inner items while matrix environments stay intact`() {
        val group = "\\left\\{x_{1,2}+\\left|y\\right|\\right\\}"
        val matrix = "\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}"
        assertEquals(listOf("\\left\\{", "x_{1,2}", "+", "\\left|y\\right|", "\\right\\}", "+", matrix),
            parts("$group+$matrix"))
    }

    @Test fun `nested square and round brackets expose the fraction inside`() {
        val source = "N_0\\left[1+\\cos\\left(\\frac{\\pi f}{B}\\right)\\right]"
        assertEquals(listOf("N_0", "\\left[", "1", "+", "\\cos", "\\left(",
            "\\frac{\\pi f}{B}", "\\right)", "\\right]"), parts(source))
        val fraction = "\\frac{\\pi f}{B}"
        val start = source.indexOf(fraction)
        assertEquals(fraction, selectedLatexSource(source, start, start + fraction.length))
        assertEquals("1+\\cos\\left(\\frac{\\pi f}{B}\\right)",
            selectedLatexSource(source, source.indexOf('1'), source.lastIndexOf("\\right]")))
        assertEquals(source, selectedLatexSource(source, 0, source.length))
    }

    @Test fun `partial fence copies keep scalable delimiters balanced`() {
        val source = "a+\\left[x+y\\right]+b"
        val x = source.indexOf('x')
        val y = source.indexOf('y')
        assertEquals("\\left.x+y\\right]+b", selectedLatexSource(source, x, source.length))
        assertEquals("a+\\left[x+\\right.", selectedLatexSource(source, 0, y))
        assertEquals("x+", selectedLatexSource(source, x, y))
    }

    @Test fun `empty parentheses retain their visible copyable delimiter pair`() {
        assertEquals(listOf("f", "\\left( \\right)", "+", "x"), parts("f\\left( \\right)+x"))
    }
}
