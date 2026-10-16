package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormulaGroupsTest {
    @Test fun `only complete outer visible groups are exposed for wrapping`() {
        for ((open, close) in listOf("[" to "]", "\\left[" to "\\right]", "\\bigl[" to "\\bigr]")) {
            val source = "\\iint_{D_{xy}}${open}a+(b+c)+d${close}dxdy"
            val group = formulaGroups(source).single()
            assertEquals("a+(b+c)+d", source.substring(group.bodyStart, group.bodyEnd))
            assertEquals("\\iint_{D_{xy}}", source.substring(0, group.start))
            assertEquals("dxdy", source.substring(group.end))
            assertEquals("\\left[", group.opening)
            assertEquals("\\right]", group.closing)
        }
        val framed = "\\boxed{a+\\left[b+c\\right]}"
        assertEquals(FormulaGroupKind.Frame, formulaGroups(framed).single().kind)
        val continuation = "\\left.a+b+c\\right\\}."
        val group = formulaGroups(continuation).single()
        assertEquals("a+b+c", continuation.substring(group.bodyStart, group.bodyEnd))
        assertEquals("\\left.", group.opening)
        assertEquals("\\right\\}", group.closing)
    }

    @Test fun `arguments roots and matrix contents never become visible wrapping groups`() {
        for (source in listOf(
            "\\frac{a+(b+c)}{d+[e+f]}",
            "\\sqrt[3]{a+(b+c)}",
            "\\text{keep [a+b] intact}",
            "\\begin{pmatrix}(a+b)&[c+d]\\\\e&f\\end{pmatrix}",
            "\\begin{cases}[a+b]&x>0\\\\(c+d)&x<0\\end{cases}",
            "\\left[a+(b+c]", "[a+b", "\\boxed{a+b",
        )) assertTrue(source, formulaGroups(source).isEmpty())
    }

    @Test fun `escaped and sized set braces retain their complete contents`() {
        for ((open, close) in listOf("\\{" to "\\}", "\\left\\{" to "\\right\\}", "\\lbrace" to "\\rbrace")) {
            val source = "D=$open(x,y):x>0,y>0$close"
            val group = formulaGroups(source).single()
            assertEquals("(x,y):x>0,y>0", source.substring(group.bodyStart, group.bodyEnd))
            assertEquals("\\left\\{", group.opening)
            assertEquals("\\right\\}", group.closing)
        }
    }
}
