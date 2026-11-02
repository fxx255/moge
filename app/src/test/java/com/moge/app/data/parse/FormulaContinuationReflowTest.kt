package com.moge.app.data.parse

import org.junit.Assert.*
import org.junit.Test

class FormulaContinuationReflowTest {
    private val measure: (String) -> Int = { it.length * 10 }
    private val source = """\begin{aligned}
        c(x)\bmod g(x)&=c_6(x^2+1)+c_5(x^2+x+1)\\
        &+c_4(x^2+x)+1\\
        &=(c_6+c_5+c_4)x^2\\
        &+(c_5+c_4)x+(c_6+c_5+1).
        \end{aligned}""".trimIndent()

    @Test fun `wide display rejoins a polynomial but retains its equality steps`() {
        val compact = reflowFormulaContinuations(source, 2000, measure)
        assertEquals(2, splitTopLevelRows(compact.substringAfter('}').substringBeforeLast("\\end{")).first.size)
        assertTrue(compact.contains("+c_4(x^2+x)+1"))
        assertTrue(compact.contains("&=(c_6+c_5+c_4)x^2 +(c_5+c_4)x+(c_6+c_5+1)."))
        assertEquals(compact, reflowFormulaContinuations(compact, 2000, measure))
    }

    @Test fun `narrow display keeps overwide continuations and wide display recalculates from source`() {
        assertEquals(source, reflowFormulaContinuations(source, 100, measure))
        assertNotEquals(source, reflowFormulaContinuations(source, 2000, measure))
        assertEquals(source, reflowFormulaContinuations(source, 100, measure))
    }

    @Test fun `normalization to an array still permits width aware equation reflow`() {
        val prepared = prepareReplyMarkdown("$$\n$source\n$$")
        assertTrue(prepared.contains("\\begin{array}"))
        val wrapped = wrapLongFormulas(prepared, 2000, measure)
        assertEquals(1, Regex("""\\\\""").findAll(wrapped).count())
        assertTrue(wrapped.contains("+c_4(x^2+x)+1"))
    }

    @Test fun `matrices cases independent equations and signed arrays retain their row structure`() {
        for (formula in listOf(
            """\begin{pmatrix}a\\-b\end{pmatrix}""",
            """\begin{cases}a&x>0\\-b&x<0\end{cases}""",
            """\begin{array}{cc}a&b\\&-c\end{array}""",
            """\begin{aligned}x&=1\\y&=-2\end{aligned}""",
            """\begin{aligned}x&=a\\&=b\end{aligned}""",
            """\begin{aligned}x&=a\\[6pt]&+b\end{aligned}""",
            """\begin{aligned}x&=\begin{cases}a\\-b\end{cases}\\&+c\end{aligned}""",
        )) assertEquals(formula, formula, reflowFormulaContinuations(formula, 2000, measure))
    }
}
