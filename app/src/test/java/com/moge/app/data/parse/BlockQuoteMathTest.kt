package com.moge.app.data.parse

import org.junit.Assert.*
import org.junit.Test

class BlockQuoteMathTest {
    @Test fun `quoted delimiters normalize idempotently while code and escaped brackets stay literal`() {
        for (prefix in listOf("> ", ">>", "  > > ", ">\t")) {
            val source = "${prefix}\\[\n${prefix}P_N=N_0B_T\n${prefix}\\]"
            val expected = source.replace("\\[", "$$").replace("\\]", "$$")
            assertEquals(expected, prepareReplyMarkdown(source))
            assertEquals(expected, prepareReplyMarkdown(expected))
        }
        val literal = "> `\\[x\\]`\n>\n> ```latex\n> \\[\n> x\n> \\]\n> ```\n\n" +
            "> \\\\[\n> x\n> \\\\]"
        assertEquals(literal, prepareReplyMarkdown(literal))
    }

    @Test fun `display formula wrapping strips quote markers and preserves comparison operators`() {
        val source = "> > $$\n> > x>0\n> > >0\n> > =y\n> > $$\n> > 继续。"
        val measured = mutableListOf<String>()
        val wrapped = wrapLongFormulas(source, 1000) { measured += it; it.length * 10 }
        assertEquals(source, wrapped)
        assertTrue(measured.toString(), measured.contains("x>0\n>0\n=y"))
        assertEquals(wrapped, wrapLongFormulas(wrapped, 1000) { it.length * 10 })
    }

    @Test fun `long inline formulas keep quote and list containers when promoted to display rows`() {
        for (prefix in listOf("> ", "> > ", "> - ")) {
            val source = "${prefix}说明 \$\$a+b+c+d=e+f+g+h\$\$，继续。"
            val wrapped = wrapLongFormulas(source, 70) { it.length * 10 }
            val lines = wrapped.lines()
            assertTrue(wrapped, lines.size > 3)
            assertTrue(wrapped, lines.all { it.startsWith("> ") })
            assertTrue(wrapped, lines.last().endsWith("继续。"))
            assertEquals(wrapped, wrapLongFormulas(wrapped, 70) { it.length * 10 })
        }
    }
}
