package com.moge.app.ui.export

import android.app.Application
import android.text.Spanned
import io.noties.markwon.image.AsyncDrawableSpan
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "zh-rCN")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportQuestionMathTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Before fun prepare() { JLatexMathAndroid.init(context) }

    @Test fun `screenshot follow-up renders a loaded lambda in both export choices`() {
        val question = "我怎么算的\$\$\\lambda=-2\$\$时，xy分别为2和-1啊"
        for (choice in ExportChoice.entries) {
            val content = AnswerExportContent("追问", question, answerText = "解答", finalAnswer = "答案")
            val part = exportParts(content, choice).filterIsInstance<ExportPart.Text>().single { it.source == question }
            assertRenderedFormulas(part, listOf("\\lambda=-2"))
        }
    }

    @Test fun `question and transcript share delimiter support with sent messages`() {
        val question = "计算 \$x^2\$、\$\$y_1\$\$、\\(\\frac{1}{2}\\) 和 \\[\\int_0^1 x dx\\]。"
        val transcript = "识别结果：\\(\\lambda=-2\\)"
        val parts = exportParts(AnswerExportContent("题目", question, transcript, answerText = "解答"), ExportChoice.FULL)
            .filterIsInstance<ExportPart.Text>()
        assertRenderedFormulas(parts.single { it.source == question }, listOf("x^2", "y_1", "\\frac{1}{2}", "\\int_0^1 x dx"))
        assertRenderedFormulas(parts.single { it.source == transcript }, listOf("\\lambda=-2"))
    }

    @Test fun `standalone formula paste gets the same display wrapper as a sent question`() {
        val parts = exportParts(AnswerExportContent("题目", "  \\frac{1}{2}\n", answerText = "解答"), ExportChoice.FULL)
            .filterIsInstance<ExportPart.Text>()
        assertRenderedFormulas(parts.single { "\\frac" in it.source }, listOf("\\frac{1}{2}"))
    }

    @Test fun `ordinary code currency and incomplete input keep literal content`() {
        for (source in listOf(
            "普通问题\n# 保留标题符号与 * 星号",
            "价格 \$5 到 \$10 之间",
            "代码 `\$x^2\$`",
            "```latex\n\$\$x^2\$\$\n```",
            "\$\$\\frac{1}{",
            "\\(x^2",
            "\$\$\\frac{1}{2\$\$",
        )) {
            val parts = exportParts(AnswerExportContent("题目", source, source, answerText = "解答"), ExportChoice.FULL)
                .filterIsInstance<ExportPart.Text>().filter { it.source == source }
            assertEquals(2, parts.size)
            parts.forEach { part ->
                val view = exportTextView(context, part.source, ExportLimits.CONTENT_WIDTH, markdown = part.markdown)
                assertEquals(source, view.text.toString())
            }
        }
    }

    @Test fun `invalid question formula retains the full source and reports fallback`() {
        val source = "请检查 \$\$\\unknownMogeCommand{1}\$\$"
        val part = exportParts(AnswerExportContent("题目", source, answerText = "解答"), ExportChoice.FULL)
            .filterIsInstance<ExportPart.Text>().single { it.source == source }
        val warnings = mutableListOf<String>()
        val view = exportTextView(context, part.source, ExportLimits.CONTENT_WIDTH, markdown = part.markdown, warning = { warnings += it })
        assertTrue(view.text.toString().contains(source))
        assertTrue(warnings.isNotEmpty())
    }

    private fun assertRenderedFormulas(part: ExportPart.Text, expected: List<String>) {
        val warnings = mutableListOf<String>()
        val view = exportTextView(context, part.source, ExportLimits.CONTENT_WIDTH, markdown = part.markdown, warning = { warnings += it })
        assertTrue("Question math was exported as plain text", view.text is Spanned)
        val text = view.text as Spanned
        val formulas = text.getSpans(0, text.length, AsyncDrawableSpan::class.java).sortedBy { text.getSpanStart(it) }
        assertEquals(expected, formulas.map { it.drawable.destination.trim() })
        assertTrue(formulas.all { it.drawable.hasResult() && it.drawable.bounds.height() > 0 })
        assertTrue(warnings.isEmpty())
        assertFalse(text.contains('$'))
    }
}
