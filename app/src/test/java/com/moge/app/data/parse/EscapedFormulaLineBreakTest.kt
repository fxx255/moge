package com.moge.app.data.parse

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class EscapedFormulaLineBreakTest {
    private val formulas = listOf(
        """\left|\frac{\partial(x,y)}{\partial(t,m)}\right|""",
        """=\left|\det\begin{pmatrix}1/2&1/2\\1/2&-1/2\end{pmatrix}\right|""",
    )

    @Test fun screenshotFormulaBreaksBecomeWhitespaceForNewAndSavedReplies() {
        for (formula in formulas) {
            for (space in listOf("", " ", "\n", "\n    ")) {
                val source = "\$\$$space$formula\\n$space\$\$"
                val expected = "\$\$$space$formula\n$space\$\$"
                assertEquals(expected, prepareReplyMarkdown(source))
                assertEquals(expected, prepareReplyMarkdown(expected))
                val raw = buildJsonObject { put("reply", source) }.toString()
                assertEquals(expected, prepareReplyMarkdown(ReplyParser.parse(raw).reply))
                for (split in 0..raw.length) {
                    val decoder = IncrementalReplyDecoder()
                    decoder.append(raw.take(split))
                    decoder.append(raw.drop(split))
                    assertEquals("split=$split", expected, prepareReplyMarkdown(decoder.finish()))
                }
            }
        }
    }

    @Test fun standaloneBreaksInsideFormulaDoNotDamageCommandsTextOrMatrixRows() {
        val source = "\$\$\\n\n\\nu+\\nabla f+x\\neq y+x\\notin A\\n\n" +
            "\\text{literal \\n\n}+\\begin{pmatrix}a&b\\\\n&m\\end{pmatrix}\\n\$\$"
        val expected = source.replace("\$\$\\n", "\$\$\n")
            .replace("A\\n", "A\n").replace("}\\n\$\$", "}\n\$\$")
        assertEquals(expected, prepareReplyMarkdown(source))
        val literal = "`\$\$x\\n\$\$`\n\n```latex\n\$\$x\\n\$\$\n```\n\n" +
            "    \$\$x\\n\$\$\n\n\\\\n"
        assertEquals(literal, prepareReplyMarkdown(literal))
    }
}
