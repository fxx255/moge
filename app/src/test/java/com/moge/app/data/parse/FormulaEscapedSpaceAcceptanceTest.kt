package com.moge.app.data.parse

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.noties.jlatexmath.JLatexMathAndroid
import ru.noties.jlatexmath.JLatexMathDrawable
import com.moge.app.ui.markdown.createMarkdownTextView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.latex.JLatexMathBlock
import io.noties.markwon.ext.latex.JLatexMathNode
import org.commonmark.node.Node

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FormulaEscapedSpaceAcceptanceTest {
    @Test fun `trimming does not split escaped whitespace or a row separator`() {
        assertEquals("x", trimSegment("x\\   "))
        assertEquals("x\\\\", trimSegment("x\\\\   "))
        assertEquals("x\\\\", trimSegment("x\\\\\\   "))
        assertEquals("x\\ y", trimSegment("  x\\ y  "))
        assertEquals("x\\,", trimSegment("x\\,  "))
        assertEquals(listOf("x", "y"), unwrapEnvironmentRows("\\begin{aligned}x\\ \\\\ y\\ \\end{aligned}"))
    }
    @Before fun init() { JLatexMathAndroid.init(RuntimeEnvironment.getApplication()) }

    @Test fun `complete derivation with escaped spaces stays renderable when wrapped`() {
        // Only the mathematical expression from the reported answer, with no conversation data.
        val formula = """1=\frac{1}{4}\left(\frac{1}{n}+\frac{1}{4}\right)\ \Rightarrow\ \frac{1}{n}+\frac{1}{4}=4\ \Rightarrow\ \frac{1}{n}=\frac{15}{4}\ \Rightarrow\ n=\frac{4}{15}\approx0.267"""
        val delimiter = "\$\$"
        for (width in listOf(240, 480, 800, 1100)) {
            val prepared = wrapLongFormulas(
                sanitizeReplyLatex(normalizeReplyMarkdown("$delimiter\n$formula\n$delimiter")),
                width,
            ) { JLatexMathDrawable.builder(it).textSize(36f).build().intrinsicWidth }
            val pieces = Regex("""\$\$([\s\S]*?)\$\$""").findAll(prepared).map { it.groupValues[1].trim() }.toList()
            assertTrue(prepared, pieces.isNotEmpty())
            for (piece in pieces) {
                try {
                    JLatexMathDrawable.builder(piece).textSize(36f).build()
                } catch (error: Exception) {
                    throw AssertionError("width=$width, piece=$piece", error)
                }
            }
            // Ignore spacing commands only; every mathematical token must survive wrapping.
            fun mathTokens(text: String) = text.replace("\\ ", "").filterNot(Char::isWhitespace)
            assertEquals(mathTokens(formula), mathTokens(pieces.joinToString("")))
        }
    }

    @Test fun `actual Markwon block source including trailing newline remains valid`() {
        val formula = """1=\frac{1}{4}\left(\frac{1}{n}+\frac{1}{4}\right)\ \Rightarrow\ \frac{1}{n}+\frac{1}{4}=4\ \Rightarrow\ \frac{1}{n}=\frac{15}{4}\ \Rightarrow\ n=\frac{4}{15}\approx0.267"""
        val delimiter = "\$\$"
        val context = RuntimeEnvironment.getApplication()
        val markwon = createMarkdownTextView(context, android.graphics.Color.BLACK, android.graphics.Color.BLUE).tag as Markwon
        for (width in listOf(240, 480, 800, 1100)) {
            val prepared = wrapLongFormulas(
                sanitizeReplyLatex(normalizeReplyMarkdown("推导如下：\n\n$delimiter$formula$delimiter\n\n以上为结果。")),
                width,
            ) { JLatexMathDrawable.builder(it).textSize(36f).build().intrinsicWidth }
            val formulas = mutableListOf<String>()
            fun visit(node: Node) {
                when (node) {
                    is JLatexMathBlock -> formulas += node.latex()
                    is JLatexMathNode -> formulas += node.latex()
                }
                var child = node.firstChild
                while (child != null) {
                    visit(child)
                    child = child.next
                }
            }
            visit(markwon.parse(prepared))
            assertTrue(prepared, formulas.isNotEmpty())
            formulas.forEach { source ->
                // The real plugin adds a newline to display blocks; do not trim it away.
                try {
                    JLatexMathDrawable.builder(source).textSize(36f).build()
                } catch (error: Exception) {
                    throw AssertionError("width=$width, Markwon source=${source.replace("\n", "<LF>")}", error)
                }
            }
        }
    }
}
