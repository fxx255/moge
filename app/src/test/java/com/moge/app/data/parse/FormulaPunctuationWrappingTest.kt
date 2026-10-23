package com.moge.app.data.parse

import org.junit.Assert.*
import org.junit.Test

class FormulaPunctuationWrappingTest {
    private val measure: (String) -> Int = { it.length * 10 }

    @Test fun `separators belong to the preceding formula including at the exact width limit`() {
        for (separator in listOf(",", ";", "，", "；")) {
            val source = "a=b${separator}c=d${separator}"
            assertEquals(listOf("a=b$separator", "c=d$separator"), splitFormula(source, 40, measure))
            val narrow = splitFormula(source, 30, measure)
            assertEquals(source, narrow.joinToString(""))
            assertTrue(narrow.toString(), narrow.all { measure(it) <= 30 })
            assertTrue(narrow.toString(), narrow.none { it.first() in ",;，；" })
        }
    }

    @Test fun `secondary relation and sign breaks never strand a separator`() {
        val source = "l=a+n_l, n_l=b+c+d+e+f,"
        val rows = splitFormula(source, 100, measure)
        assertEquals(source.filterNot(Char::isWhitespace), rows.joinToString("").filterNot(Char::isWhitespace))
        assertTrue(rows.toString(), rows.none { it.trimStart().first() in ",;" })
        assertTrue(rows.toString(), rows.all { measure(it) <= 100 })
        assertTrue(rows.last().endsWith(','))
    }

    @Test fun `punctuation outside promoted inline math stays on its last display row`() {
        val source = "判决量为 \$\$a+b+c+d\$\$，因此成立。"
        val wrapped = wrapLongFormulas(source, 40, measure)
        assertEquals(listOf("判决量为", "\$\$", "a+b", "\$\$", "\$\$", "+c", "\$\$", "\$\$", "+d，", "\$\$", "因此成立。"), wrapped.lines())
        assertEquals(wrapped, wrapLongFormulas(wrapped, 40, measure))
        val terminal = wrapLongFormulas("\$\$a+b+c+d\$\$;", 40, measure)
        assertTrue(terminal, terminal.endsWith("+d;\n\$\$"))
    }

    @Test fun `display closing punctuation is measured with its formula while following prose stays outside`() {
        val source = "\$\$\na=b\n\$\$，下一段 \$\$x\$\$。"
        assertEquals("\$\$\na=b，\n\$\$\n下一段 \$\$x\$\$。", wrapLongFormulas(source, 40, measure))
        val short = "已知 \$\$a=b\$\$，因此成立。"
        assertEquals(short, wrapLongFormulas(short, 100, measure))
        // The math alone fits, but its comma also needs a place on that row.
        val edge = wrapLongFormulas("\$\$a=b\$\$，", 30, measure)
        assertEquals("\$\$\na\n\$\$\n\$\$\n=b，\n\$\$", edge)
    }

    @Test fun `tuple commas and TeX spacing commands retain their meaning`() {
        val source = "(a,b)=x\\,y+z;u_{1,2}=v\\;w"
        val rows = splitFormula(source, 120, measure)
        assertEquals(source, rows.joinToString(""))
        assertTrue(rows.toString(), rows.any { "(a,b)" in it })
        assertTrue(rows.toString(), rows.any { "u_{1,2}" in it })
        assertTrue(rows.toString(), rows.any { "x\\,y" in it })
        assertTrue(rows.toString(), rows.any { "v\\;w" in it })
    }
}
