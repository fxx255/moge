package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class IntegralNormalizationTest {
    @Test
    fun `closed integral continuation gains display delimiters without losing punctuation`() {
        for (ending in listOf(".", ";", "")) {
            val formula = "=\\oiint_S z\\,\\mathrm{d}S$ending"
            val expected = "$$\n$formula\n$$"
            assertEquals(expected, normalizeReplyMarkdown(formula))
            assertEquals(expected, normalizeReplyMarkdown(expected))
        }
    }

    @Test
    fun `duplicate escaping is repaired for all supported integral operators`() {
        for (operator in listOf("int", "iint", "iiint", "iiiint", "oint", "oiint", "oiiint")) {
            val correct = "$$\\${operator}_S f\\,\\mathrm{d}S$$"
            val escaped = correct.replace("\\", "\\\\")
            assertEquals(operator, correct, normalizeReplyMarkdown(escaped))
            assertEquals(correct, normalizeReplyMarkdown(correct))
            val code = "```latex\n$escaped\n```\n`$escaped`"
            assertEquals("Code examples stay literal", code, normalizeReplyMarkdown(code))
        }
    }

    @Test
    fun `repair preserves actual rows and exact command boundaries`() {
        val formula = """\begin{aligned}I&=\iiint_\Omega f\,dV\\&=0\end{aligned}"""
        assertEquals("$$$formula$$", normalizeReplyMarkdown("$$$formula$$"))
        val unknown = """$$\\oiintExtra_S f$$"""
        assertEquals(unknown, normalizeReplyMarkdown(unknown))
    }

    @Test
    fun `exact triple integral repairs a second escaping layer including Greek variables`() {
        val formula = """I=\iiint_\Omega 3r^2\cdot r^2\sin\varphi\,dr\,d\varphi\,d\theta"""
        val correct = "$$$formula$$"
        assertEquals(correct, normalizeReplyMarkdown(correct.replace("\\", "\\\\")))
    }

    @Test
    fun `punctured domain integral preserves Omega set difference limit and bold field`() {
        val formula = """I=\lim_{R\to0^+}\iiint_{\Omega\setminus B_R}
            \operatorname{div}\mathbf F\,dV""".trimIndent()
        val correct = "$$\n$formula\n$$"
        assertEquals(correct, normalizeReplyMarkdown(correct))
        assertEquals(correct, sanitizeReplyLatex(correct))
        assertEquals(correct, normalizeReplyMarkdown(correct.replace("\\", "\\\\")))
        val unknown = """$$\\setminusExtra+\\today$$"""
        assertEquals(unknown, normalizeReplyMarkdown(unknown))
    }
}
