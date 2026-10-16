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

    @Test fun `paired delimiters and nested environments stay valid when copied`() {
        val group = "\\left\\{x_{1,2}+\\left|y\\right|\\right\\}"
        val matrix = "\\begin{pmatrix}1&2\\\\3&4\\end{pmatrix}"
        assertEquals(listOf(group, "+", matrix), parts("$group+$matrix"))
    }
}
