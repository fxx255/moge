package com.moge.app.data.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreparationTest {
    @Test fun standaloneEscapedBreakBeforeFormulaOrAtParagraphEndDoesNotLeakIntoProse() {
        val paragraph = "又在 \$\$\\theta\\in[0,\\frac{\\pi}{4}]\$\$ 上，\$\$\\sqrt2\\cos\\theta\\ge 1\$\$，" +
            "所以每个方向都要在 \$\$r=1\$\$ 处拆开绝对值："
        for (suffix in listOf("\\n\n\n\$\$I\$\$", "\\n\$\$I\$\$", "\\n")) {
            val prepared = prepareReplyMarkdown(paragraph + suffix)
            assertEquals(paragraph + suffix.replace("\\n", "\n"), prepared)
            assertEquals(prepared, prepareReplyMarkdown(prepared))
        }
    }

    @Test fun standaloneEscapedBreakRepairPreservesTexCommandsAndCode() {
        val protected = "`\\n`\n```text\n\\n\n```\n\n" +
            "    \\n\n\n\t\\n\n\n" +
            "\$\$\\nu+\\nabla f+\\neq+\\notin+\\text{\\n}\$\$\n\n" +
            "\\\\n\n\n命令 \\nu 和 \\nabla。"
        assertEquals(protected, prepareReplyMarkdown(protected))
    }

    @Test fun primeSymbolsRenderWithoutTreatingContractionsCurrencyOrCodeAsMath() {
        val source = "这里 \$f'\$ 与 \$f''\$ 的系数，以及 \$g′\$、\$y″\$。"
        val expected = "这里 \$\$f'\$\$ 与 \$\$f''\$\$ 的系数，以及 \$\$g′\$\$、\$\$y″\$\$。"
        assertEquals(expected, prepareReplyMarkdown(source))
        assertEquals(expected, prepareReplyMarkdown(expected))
        val literal = "`\$f'\$`\n\n```text\n\$f''\$\n```\n\n" +
            "价格 \$5 到 \$10；\$don't\$；转义 \\\$f'\\\$。"
        assertEquals(literal, prepareReplyMarkdown(literal))
    }

    @Test fun escapedBreaksBeforeParenthesizedStepsBecomeParagraphsWithoutDamagingLatexOrCode() {
        val source = "所以二维条件密度用联合高斯 pdf，而不是用 erfc/Q 函数。\n\n" +
            "分步解答：\\n(1)\n\n\$\$\n" +
            "E_s=\\frac{4b^2+4\\cdot 4b^2}{8}=\\frac{5}{2} b^2=10\\Rightarrow b^2\n\n=4\n\$\$"
        val prepared = prepareReplyMarkdown(source)
        assertTrue(prepared.contains("分步解答：\n\n(1)"))
        assertTrue(prepared.contains("\\frac{4b^2+4\\cdot 4b^2}{8}"))
        assertEquals(prepared, prepareReplyMarkdown(prepared))
        assertEquals("步骤\n\n（2）继续", prepareReplyMarkdown("步骤\\n（2）继续"))
        val protected = "`\\n(1)`\n\n```text\n\\n(2)\n```\n\n" +
            "\$\$\\nu+\\nabla+\\neq+\\notin\$\$"
        assertEquals(protected, prepareReplyMarkdown(protected))
    }

    @Test fun cjkBoldBoundariesAreRepairedIdempotentlyWithoutChangingCodeMathOrEscapedMarkers() {
        val protected = "`前**“代码。”**后`\n\n```text\n前**“代码。”**后\n```\n\n" +
            "`**effects on sth.**＝`\n\n```text\n**effects on sth.**＝\n```\n\n" +
            "\$\$\\text{**effects on sth.**＝}\$\$\n\n" +
            """\＝**(保留反斜杠)**、\*\*effects on sth.\*\*＝""" + "\n\n" +
            "    前**“缩进代码。”**后\n\n\t前**“制表符缩进代码。”**后\n\n" +
            "\$\$\\text{前**“公式。”**后}\$\$\n\n" +
            """前\*\*“转义。”\*\*后""" + "\n\n**未闭合。后\n\n**正常加粗**，前**正常**后\n\n"
        val prose = "前**“重点。”**后\n\n固定搭配 **effects on sth.**＝对……的影响。"
        assertEquals(protected, prepareReplyMarkdown(protected))
        val prepared = prepareReplyMarkdown(protected + prose)
        assertTrue(prepared.startsWith(protected))
        assertEquals(prepared, prepareReplyMarkdown(prepared))
    }

    @Test fun screenshotStateAndOutputSequencesBecomeMathWithoutChangingTheirOrder() {
        val source = "**输出结果顺序：** 题目明确按 \$00,10,11,01\$ 给前一时刻度量，" +
            "所以答案也应按同一顺序写为 \$3,1,3,3\$；若改按 \$00,01,10,11\$ 才是 \$3,3,1,3\$。"
        val expected = source.replace(Regex("\\$([^$]+)\\$")) { "\$\$${it.groupValues[1]}\$\$" }
        assertEquals(expected, prepareReplyMarkdown(source))
        assertEquals(expected, prepareReplyMarkdown(expected))
    }

    @Test fun numericListsRenderInTablesWhileCodeAndCurrencyStayLiteral() {
        val source = "| 数值 |\n| --- |\n| \$0.5, 1.5; 2\$ |\n\n" +
            "`\$00,10\$`\n```text\n\$3,1,3,3\$\n```\n价格 \$5 到 \$10，转义 \\$00,10\\$。"
        assertEquals(source.replace("\$0.5, 1.5; 2\$", "\$\$0.5, 1.5; 2\$\$"), prepareReplyMarkdown(source))
    }

    @Test fun fencedAndInlineCodeKeepEscapesAndLatexLikeCommandsLiteral() {
        val code = "```json\n" + """{"reply":"第一行\n\n第二行","pattern":"\tag{literal}"}""" + "\n```\n"
        val inline = """`\n中文 \tag{literal}`"""
        assertEquals(code + inline, prepareReplyMarkdown(code + inline))
    }

    @Test fun proseStillRepairsEscapedParagraphsAroundCode() {
        val code = "~~~text\n" + """\n\n\tag{literal}""" + "\n~~~\n"
        val prose = "前段\\n\\n后段\n"
        assertEquals("前段\n\n后段\n" + code + "尾部\n中文", prepareReplyMarkdown(prose + code + "尾部\\n中文"))
    }

    @Test fun longerClosingFencesAndUnclosedStreamingCodeRemainProtected() {
        val closed = "````text\n" + """\n\n\tag{literal}""" + "\n`````\n"
        val open = "```json\n" + """{"reply":"\n\n\tag{literal}"}"""
        assertEquals(closed + open, prepareReplyMarkdown(closed + open))
    }
}
