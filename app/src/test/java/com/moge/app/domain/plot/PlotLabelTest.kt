package com.moge.app.domain.plot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 图内文字的 LaTeX 降级：画布没有公式排版能力，模型却常把 `$S_c(f)$` 写进标题，
 * 不处理的话图上就是一串美元符号和反斜杠。
 */
class PlotLabelTest {

    @Test
    fun `strips dollar delimiters`() {
        assertEquals("S(f)", prettifyPlotLabel("\$S(f)\$"))
        assertEquals("S(f)", prettifyPlotLabel("\$\$S(f)\$\$"))
    }

    @Test
    fun `maps latex commands to unicode`() {
        // 命令之间的空格是原有分隔，保留即可（2π f 而不是 2πf）
        assertEquals("2π f", prettifyPlotLabel("2\\pi f"))
        assertEquals("α + β", prettifyPlotLabel("\\alpha + \\beta"))
        assertEquals("f ≤ g", prettifyPlotLabel("f \\le g"))
        assertEquals("x → 0", prettifyPlotLabel("x \\to 0"))
    }

    @Test
    fun `longer commands win over their prefixes`() {
        // \leq 不能被 \le 抢先匹配掉
        assertEquals("a ≤ b", prettifyPlotLabel("a \\leq b"))
    }

    @Test
    fun `fractions degrade to a slash form`() {
        assertEquals("a/b", prettifyPlotLabel("\\frac{a}{b}"))
    }

    @Test
    fun `superscripts become unicode`() {
        assertEquals("f²", prettifyPlotLabel("f^2"))
        assertEquals("f²", prettifyPlotLabel("f^{2}"))
        assertEquals("x³", prettifyPlotLabel("x^3"))
    }

    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("幅度谱", prettifyPlotLabel("幅度谱"))
        assertEquals("f_c", prettifyPlotLabel("f_c"))
    }

    @Test
    fun `no stray backslash survives`() {
        val out = prettifyPlotLabel("\\frac{\\alpha}{2} + \\gamma")
        assertFalse("不该留下反斜杠: $out", out.contains("\\"))
    }
}
