package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class TextOnlyMathNormalizationTest {
    private val sentence = "I would appreciate it if these practical, low-cost measures could be piloted next semester."
    private val prose = sentence.replace(",", "&#44;").replace("-", "&#45;").replace(".", "&#46;")

    @Test fun completeTextSentencesBecomeProseAcrossAllSupportedMathDelimiters() {
        for ((open, close) in listOf("\$\$" to "\$\$", "$" to "$", "\\[" to "\\]", "\\(" to "\\)")) {
            for (command in listOf("text", "textrm", "textnormal", "mbox")) {
                val prepared = prepareReplyMarkdown("说明：\n\n$open\\$command{$sentence}$close\n\n结论。")
                assertEquals("说明：\n\n$prose\n\n结论。", prepared)
                assertEquals(prepared, prepareReplyMarkdown(prepared))
            }
        }
        assertEquals("\n$prose\n", prepareReplyMarkdown("\$\$\n\\text{$sentence}\n\$\$"))
        assertEquals("正文 $prose 继续。", prepareReplyMarkdown("正文 \$\$\\text{$sentence}\$\$ 继续。"))
    }

    @Test fun literalPunctuationAndTexEscapesCannotBecomeMarkdownFormatting() {
        val source = "\$\$\\text{Please keep \\{notes\\}, \\#1, 10\\%, \\$5 and \\& together.}\$\$"
        val expected = "Please keep &#123;notes&#125;&#44; &#35;1&#44; 10&#37;&#44; &#36;5 and &#38; together&#46;"
        assertEquals(expected, prepareReplyMarkdown(source))
        assertEquals(expected, prepareReplyMarkdown(expected))
        assertEquals("&#35; Please keep this sentence literal&#46;",
            prepareReplyMarkdown("\$\$\\text{# Please keep this sentence literal.}\$\$"))
        assertEquals("&#91;Please keep this sentence literal&#93;&#40;example&#41;&#46;",
            prepareReplyMarkdown("\$\$\\text{[Please keep this sentence literal](example).}\$\$"))
    }

    @Test fun mathLabelsExpressionsNestedCommandsAndIncompleteTextRetainFormulaSemantics() {
        for (latex in listOf(
            "\\text{SNR}", "\\text{a b c d}", "\\text{if }x>0", "x=\\text{$sentence}",
            "\\text{$sentence}+1", "\\text{$sentence}_n", "\\text{x = a + b + c}",
            "\\text{Please compute \\frac{a}{b} now.}", "\\text{Please keep {this sentence} grouped.}",
            "\\text{**Please keep this sentence literal.**}", "\\operatorname{Please keep this sentence literal.}",
            "\\text{Please keep this sentence open.",
        )) {
            val source = "\$\$$latex\$\$"
            assertEquals(source, prepareReplyMarkdown(source))
        }
        for (source in listOf("\$\$\\text{$sentence}", "\$\$\\text{", "\$\$\\text{$sentence}"))
            assertEquals(source, prepareReplyMarkdown(source))
    }

    @Test fun codeAndEscapedDelimitersRemainLiteralAndDoNotConsumeLaterProse() {
        val math = "\$\$\\text{$sentence}\$\$"
        for (literal in listOf("`$math`", "```latex\n$math\n```", "    $math", "\t$math", "\\\$\$\\text{$sentence}\\\$\$")) {
            assertEquals(literal, prepareReplyMarkdown(literal))
            assertEquals("$literal\n\n$prose", prepareReplyMarkdown("$literal\n\n$math"))
        }
    }
}
